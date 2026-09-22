package app.soundbound.core.player

import app.soundbound.core.audio.AudioClip
import app.soundbound.core.audio.Dsp
import app.soundbound.core.audiobook.AudioChapter
import app.soundbound.core.audiobook.Audiobook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What audiobook playback is doing, as the listener would describe it. */
data class AudiobookState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    /** Where playback has reached, in book time across every file. */
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val trackIndex: Int = 0,
    val trackTitle: String? = null,
    val chapterIndex: Int = -1,
    val chapterTitle: String? = null,
    val speed: Float = 1f,
    val sleepTimerMillisRemaining: Long? = null,
    val errorMessage: String? = null,
) {
    val fraction: Double
        get() = if (durationMillis <= 0) 0.0 else positionMillis.toDouble() / durationMillis

    val remainingMillis: Long get() = (durationMillis - positionMillis).coerceAtLeast(0)
}

/**
 * Plays an audiobook that already exists as audio.
 *
 * Built to be the same shape as [ReadAloudController] on purpose: the same states, the same
 * transport, the same sleep timer, the same audio sink underneath. Everything above the player —
 * the notification, the media session, the lock screen, the saved position — then works for a
 * recorded book and a synthesised one without knowing which it has.
 *
 * Decoding runs one block ahead of playback and is throttled by the audio queue rather than by a
 * timer, exactly as synthesis is: the sink's enqueue suspends when it is full, so a fast device
 * reads further ahead and a slow one simply keeps up. Nothing is decoded far in advance, because
 * a change of speed or position would throw it away.
 */
class AudiobookController(
    private val sink: AudioSink,
    private val decoders: AudioDecoderFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Where decoding happens. Injectable so tests can run it on one deterministic dispatcher. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Called whenever the position moves, so the library can persist it. */
    private val onPositionChanged: (Long) -> Unit = {},
) : AutoCloseable {

    private val _state = MutableStateFlow(AudiobookState())
    val state: StateFlow<AudiobookState> = _state.asStateFlow()

    private val lock = Mutex()
    private val clipIds = AtomicLong(0)

    /** Where each queued block sits in book time, so the sink's position can be read back. */
    private val queued = ConcurrentHashMap<Long, QueuedBlock>()

    private data class QueuedBlock(val bookMillis: Long, val sourceDurationMillis: Long)

    private var book: Audiobook? = null
    private var producer: Job? = null
    private var watcher: Job? = null
    private var sleepTimer: Job? = null
    private var speed: Float = 1f

    /** Where the next decoded block belongs in book time. Only touched by the producer. */
    private var decodeCursor: Long = 0

    // ---------------------------------------------------------------- lifecycle

    suspend fun open(audiobook: Audiobook, positionMillis: Long = 0) = lock.withLock {
        stopInternal()
        book = audiobook
        decodeCursor = positionMillis.coerceIn(0, audiobook.totalDurationMillis)
        _state.value = AudiobookState(
            status = PlaybackStatus.PAUSED,
            positionMillis = decodeCursor,
            durationMillis = audiobook.totalDurationMillis,
            speed = speed,
        ).withPlaceIn(audiobook, decodeCursor)
    }

    suspend fun play() = lock.withLock {
        val audiobook = book ?: return@withLock
        if (_state.value.status == PlaybackStatus.PLAYING) return@withLock
        if (decodeCursor >= audiobook.totalDurationMillis) decodeCursor = 0

        _state.value = _state.value.copy(status = PlaybackStatus.PLAYING, errorMessage = null)
        sink.resume()
        startProducer()
        startWatcher()
    }

    suspend fun pause() = lock.withLock {
        if (_state.value.status != PlaybackStatus.PLAYING) return@withLock
        // The decoded queue is kept: pausing and resuming should not have to decode again, and
        // the sink resumes from exactly where the hardware stopped.
        sink.pause()
        producer?.cancel()
        producer = null
        _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
    }

    suspend fun togglePlayPause() {
        if (_state.value.status == PlaybackStatus.PLAYING) pause() else play()
    }

    suspend fun stop() = lock.withLock { stopInternal() }

    // ---------------------------------------------------------------- moving about

    /** Jumps to a point in book time. */
    suspend fun seekTo(bookMillis: Long) = lock.withLock {
        val audiobook = book ?: return@withLock
        restartAt(bookMillis.coerceIn(0, audiobook.totalDurationMillis), audiobook)
    }

    suspend fun seekToFraction(fraction: Double) {
        val audiobook = book ?: return
        seekTo((audiobook.totalDurationMillis * fraction.coerceIn(0.0, 1.0)).toLong())
    }

    /**
     * Steps forward or back by a number of seconds — the thirty-second buttons every audiobook
     * player has, and what a headset's fast-forward maps to.
     */
    suspend fun skipSeconds(seconds: Int) = lock.withLock {
        val audiobook = book ?: return@withLock
        val target = _state.value.positionMillis + seconds * 1000L
        restartAt(target.coerceIn(0, audiobook.totalDurationMillis), audiobook)
    }

    suspend fun skipChapter(delta: Int) = lock.withLock {
        val audiobook = book ?: return@withLock
        restartAt(audiobook.chapterBoundary(_state.value.positionMillis, delta), audiobook)
    }

    suspend fun seekToChapter(index: Int) = lock.withLock {
        val audiobook = book ?: return@withLock
        val chapter = audiobook.chapters.getOrNull(index) ?: return@withLock
        restartAt(chapter.startMillis, audiobook)
    }

    // ---------------------------------------------------------------- speed

    suspend fun setSpeed(value: Float) = lock.withLock {
        val clamped = value.coerceIn(0.5f, 3.5f)
        if (clamped == speed) return@withLock
        speed = clamped
        _state.value = _state.value.copy(speed = clamped)
        // Everything already queued was stretched at the old speed, so it has to go; playback
        // restarts from where it had actually reached, not from where decoding had run ahead to.
        book?.let { restartAt(_state.value.positionMillis, it) }
    }

    // ---------------------------------------------------------------- sleep timer

    /** Stops playback after [millis]. Pass null to cancel. */
    fun setSleepTimer(millis: Long?) {
        sleepTimer?.cancel()
        if (millis == null || millis <= 0) {
            _state.value = _state.value.copy(sleepTimerMillisRemaining = null)
            return
        }
        sleepTimer = scope.launch {
            var remaining = millis
            while (remaining > 0 && isActive) {
                _state.value = _state.value.copy(sleepTimerMillisRemaining = remaining)
                delay(minOf(remaining, 1000L))
                remaining -= 1000L
            }
            if (isActive) {
                _state.value = _state.value.copy(sleepTimerMillisRemaining = null)
                pause()
            }
        }
    }

    /** Extends a running timer, for the inevitable "just one more chapter". */
    fun extendSleepTimer(extraMillis: Long) {
        setSleepTimer((_state.value.sleepTimerMillisRemaining ?: 0) + extraMillis)
    }

    /** Stops at the end of the chapter being listened to, rather than after a fixed time. */
    fun sleepAtEndOfChapter() {
        val audiobook = book ?: return
        val chapter = audiobook.chapterAt(_state.value.positionMillis) ?: return
        val remaining = chapter.endMillis - _state.value.positionMillis
        setSleepTimer((remaining / speed).toLong().coerceAtLeast(1000))
    }

    // ---------------------------------------------------------------- internals

    private suspend fun restartAt(bookMillis: Long, audiobook: Audiobook) {
        val wasPlaying = _state.value.status == PlaybackStatus.PLAYING
        stopProducerAndAudio()
        decodeCursor = bookMillis
        _state.value = _state.value
            .copy(positionMillis = bookMillis)
            .withPlaceIn(audiobook, bookMillis)
        onPositionChanged(bookMillis)
        if (wasPlaying) {
            sink.resume()
            startProducer()
            startWatcher()
        }
    }

    private fun startProducer() {
        producer?.cancel()
        producer = scope.launch(ioDispatcher) { produce() }
    }

    /**
     * Decodes ahead of playback, one file at a time.
     *
     * The loop never races: [AudioSink.enqueue] suspends while the device's queue is full, so
     * this coroutine spends most of its life blocked, which is exactly what keeps memory flat
     * and lets a change of position take effect almost immediately.
     */
    private suspend fun produce() {
        val audiobook = book ?: return

        while (currentCoroutineIsActive()) {
            val track = audiobook.trackAt(decodeCursor) ?: run {
                finish()
                return
            }

            val decoder = try {
                decoders.open(track.uri)
            } catch (error: Throwable) {
                fail("This file could not be opened: ${track.title ?: track.uri}", error)
                return
            }

            decoder.use { open ->
                val offsetInTrack = (decodeCursor - track.startMillis).coerceAtLeast(0)
                runCatching { open.seekTo(offsetInTrack) }

                while (currentCoroutineIsActive()) {
                    val block = try {
                        open.read()
                    } catch (error: Throwable) {
                        fail("This file could not be read: ${track.title ?: track.uri}", error)
                        return
                    } ?: break

                    if (block.isEmpty) continue
                    val sourceDuration = block.durationMillis
                    val id = clipIds.incrementAndGet()
                    queued[id] = QueuedBlock(decodeCursor, sourceDuration)
                    decodeCursor += sourceDuration

                    sink.enqueue(id, stretched(block))
                }
            }

            // Ran off the end of this file: carry on into the next one. The cursor is snapped to
            // the file boundary because a decoder's idea of the length is better than the tags'.
            if (decodeCursor < track.endMillis) decodeCursor = track.endMillis
            if (audiobook.trackAt(decodeCursor) == null) {
                finish()
                return
            }
        }
    }

    private fun stretched(block: AudioClip): AudioClip =
        if (speed in 0.995f..1.005f) block else Dsp.changeSpeed(block, speed)

    /**
     * Follows the sink's report of what is actually audible.
     *
     * The queue runs a second or two ahead of the speaker, so the position has to come from the
     * hardware rather than from what was last handed over — otherwise the scrubber sits ahead of
     * the narrator, which is immediately obvious when a chapter changes.
     */
    private fun startWatcher() {
        watcher?.cancel()
        watcher = scope.launch {
            var lastReported = -1L
            while (isActive) {
                val audiobook = book
                val position = sink.position.value
                val block = queued[position.clipId]
                if (audiobook != null && block != null) {
                    val into = (block.sourceDurationMillis * position.fractionThroughClip).toLong()
                    val bookMillis = (block.bookMillis + into)
                        .coerceIn(0, audiobook.totalDurationMillis)

                    _state.value = _state.value
                        .copy(positionMillis = bookMillis)
                        .withPlaceIn(audiobook, bookMillis)

                    // Persisted about once a second rather than on every update: the library
                    // writes to disk, and a book is listened to for hours.
                    if (lastReported < 0 || kotlin.math.abs(bookMillis - lastReported) >= 1000) {
                        lastReported = bookMillis
                        onPositionChanged(bookMillis)
                    }

                    // Blocks behind the audible one can never be needed again.
                    queued.entries.removeIf { it.key < position.clipId }
                }
                delay(POSITION_POLL_MILLIS)
            }
        }
    }

    private fun finish() {
        _state.value = _state.value.copy(
            status = PlaybackStatus.FINISHED,
            positionMillis = _state.value.durationMillis,
        )
        onPositionChanged(_state.value.durationMillis)
    }

    private fun fail(message: String, cause: Throwable) {
        _state.value = _state.value.copy(
            status = PlaybackStatus.ERROR,
            errorMessage = message + (cause.message?.let { ": $it" } ?: ""),
        )
    }

    private suspend fun stopProducerAndAudio() {
        producer?.cancelAndJoin()
        producer = null
        watcher?.cancel()
        watcher = null
        sink.flushAndStop()
        queued.clear()
    }

    private suspend fun stopInternal() {
        stopProducerAndAudio()
        sleepTimer?.cancel()
        sleepTimer = null
        book = null
        decodeCursor = 0
        _state.value = AudiobookState(speed = speed)
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        withContext(kotlin.coroutines.EmptyCoroutineContext) { kotlin.coroutines.coroutineContext.isActive }

    override fun close() {
        producer?.cancel()
        watcher?.cancel()
        sleepTimer?.cancel()
        queued.clear()
    }

    private companion object {
        /**
         * How often the audible position is read back.
         *
         * Fast enough that the scrubber and the chapter title keep up with the narrator, slow
         * enough to be free: this runs for the whole length of a book.
         */
        const val POSITION_POLL_MILLIS = 100L
    }
}

/** Fills in which track and chapter a point in the book falls in. */
private fun AudiobookState.withPlaceIn(book: Audiobook, bookMillis: Long): AudiobookState {
    val track = book.trackAt(bookMillis)
    val chapter: AudioChapter? = book.chapterAt(bookMillis)
    return copy(
        trackIndex = track?.index ?: trackIndex,
        trackTitle = track?.title,
        chapterIndex = chapter?.index ?: -1,
        chapterTitle = chapter?.title,
    )
}
