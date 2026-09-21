package app.soundbound.core.player

import app.soundbound.core.audio.AudioClip
import app.soundbound.core.audio.Dsp
import app.soundbound.core.book.BookSource
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.ReadingPosition
import app.soundbound.core.model.ReadingProgress
import app.soundbound.core.tts.LoadedVoice
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.tts.VoiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** What read-aloud is doing, as the user would describe it. */
enum class PlaybackStatus { IDLE, PREPARING, PLAYING, PAUSED, BUFFERING, FINISHED, ERROR }

/** The complete, observable state of read-aloud. One object, one source of truth for the UI. */
data class ReadAloudState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val voice: TtsVoice? = null,
    val params: SpeechParams = SpeechParams(),
    val chapter: ChapterIndex = ChapterIndex(0),
    val chapterTitle: String? = null,
    val unitIndex: Int = -1,
    val unitCount: Int = 0,
    /** The sentence being spoken, for display. */
    val currentSentence: String = "",
    /** Character range within the chapter's plain text that is currently audible. */
    val highlightRange: IntRange? = null,
    /** Narrower range for the word being spoken, when word timing is available. */
    val wordRange: IntRange? = null,
    val progress: ReadingProgress = ReadingProgress.NONE,
    val sleepTimerMillisRemaining: Long? = null,
    val errorMessage: String? = null,
    val bufferedMillis: Int = 0,
) {
    val isActive: Boolean
        get() = status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING ||
            status == PlaybackStatus.PREPARING
}

/**
 * Reads a book aloud.
 *
 * The design goal is that listening and reading are the same activity seen from two angles:
 * one position, one progress figure, and a highlight that always matches what is being said.
 *
 * Synthesis runs one coroutine ahead of playback and is throttled by the audio queue rather
 * than by a timer, so a fast device renders further ahead and a slow one simply keeps up.
 * Nothing is ever rendered more than a handful of sentences in advance, because a change of
 * speed, voice or position would throw it away.
 */
class ReadAloudController(
    private val registry: VoiceRegistry,
    private val sink: AudioSink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Where chapter parsing happens. Injectable so that tests can run the whole pipeline on
     * one deterministic dispatcher rather than hopping onto a real IO thread mid-chapter.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val planOptions: SpeechPlanOptions = SpeechPlanOptions(),
    /** Called whenever the listening position moves, so the library can persist it. */
    private val onPositionChanged: (ReadingPosition) -> Unit = {},
) : AutoCloseable {

    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private val lock = Mutex()
    private val planBuilder = SpeechPlanBuilder(planOptions)
    private val clipIds = AtomicLong(0)

    /** Maps an enqueued clip back to the utterance that produced it, for highlighting. */
    private val clipToUnit = java.util.concurrent.ConcurrentHashMap<Long, SpeechUnit>()

    /**
     * Clips that are the pause *after* an utterance. They are mapped to that utterance too, so
     * that the highlight stays put during the pause instead of blinking off between sentences.
     */
    private val pauseClipIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Long, Boolean>(),
    )

    private var book: BookSource? = null
    private var chapterContent: ChapterContent? = null
    private var plan: SpeechPlan? = null
    private var loadedVoice: LoadedVoice? = null
    private var producer: Job? = null
    private var watcher: Job? = null
    private var sleepTimer: Job? = null
    private var sinkStartedAt: Int = 0

    /** Total characters in the book, for whole-book progress. */
    private var bookCharacters: Long = 0
    private var charactersBeforeChapter: Long = 0

    // ---------------------------------------------------------------- lifecycle

    suspend fun open(source: BookSource, position: ReadingPosition, voice: TtsVoice?) = lock.withLock {
        stopInternal()
        book = source
        bookCharacters = source.chapters.sumOf { it.approximateCharacters.toLong() }.coerceAtLeast(1)
        _state.value = ReadAloudState(status = PlaybackStatus.PREPARING, voice = voice, params = _state.value.params)
        prepareChapter(position.chapter)
        positionTo(position)
        voice?.let { selectVoiceInternal(it) }
        _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
    }

    suspend fun setVoice(voice: TtsVoice) = lock.withLock {
        val wasPlaying = _state.value.status == PlaybackStatus.PLAYING
        stopProducerAndAudio()
        selectVoiceInternal(voice)
        if (wasPlaying) startProducer()
    }

    private suspend fun selectVoiceInternal(voice: TtsVoice) {
        try {
            val loaded = registry.load(voice)
            loadedVoice = loaded
            if (sinkStartedAt != loaded.sampleRate) {
                sink.start(loaded.sampleRate)
                sinkStartedAt = loaded.sampleRate
            }
            _state.value = _state.value.copy(voice = voice, errorMessage = null)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = e.message ?: "That voice could not be loaded.",
            )
        }
    }

    /**
     * Changes speed, pitch or expressiveness. Anything already rendered is discarded and
     * re-rendered from the current sentence, because a Piper model re-predicts phoneme
     * durations for the new speed rather than simply playing faster.
     */
    suspend fun setParams(params: SpeechParams) = lock.withLock {
        val wasPlaying = _state.value.status == PlaybackStatus.PLAYING
        stopProducerAndAudio()
        _state.value = _state.value.copy(params = params.coerced())
        if (wasPlaying) startProducer()
    }

    // ---------------------------------------------------------------- transport

    suspend fun play() = lock.withLock {
        if (book == null || plan == null) return@withLock
        if (loadedVoice == null) {
            _state.value = _state.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "No voice is selected. Choose one in Voices.",
            )
            return@withLock
        }
        if (_state.value.status == PlaybackStatus.PLAYING) return@withLock
        sink.resume()
        startProducer()
        _state.value = _state.value.copy(status = PlaybackStatus.PLAYING, errorMessage = null)
    }

    suspend fun pause() = lock.withLock {
        if (_state.value.status != PlaybackStatus.PLAYING) return@withLock
        sink.pause()
        _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
    }

    suspend fun togglePlayPause() {
        if (_state.value.status == PlaybackStatus.PLAYING) pause() else play()
    }

    suspend fun stop() = lock.withLock { stopInternal() }

    /** Moves by [delta] utterances, crossing chapter boundaries where needed. */
    suspend fun skipSentences(delta: Int) = lock.withLock {
        val currentPlan = plan ?: return@withLock
        val target = (_state.value.unitIndex + delta)
        when {
            target < 0 -> {
                val previous = ChapterIndex((currentPlan.chapter.value - 1).coerceAtLeast(0))
                if (previous == currentPlan.chapter) {
                    restartAt(0)
                } else {
                    prepareChapter(previous)
                    restartAt((plan?.units?.lastIndex ?: 0).coerceAtLeast(0))
                }
            }

            target >= currentPlan.units.size -> {
                val next = ChapterIndex(currentPlan.chapter.value + 1)
                if (next.value < (book?.chapters?.size ?: 0)) {
                    prepareChapter(next)
                    restartAt(0)
                } else {
                    stopInternal()
                    _state.value = _state.value.copy(status = PlaybackStatus.FINISHED)
                }
            }

            else -> restartAt(target)
        }
    }

    /** Jumps a whole paragraph in either direction, which is what the ±30 s buttons do. */
    suspend fun skipParagraph(delta: Int) = lock.withLock {
        val currentPlan = plan ?: return@withLock
        val from = _state.value.unitIndex.coerceAtLeast(0)
        val step = if (delta >= 0) 1 else -1
        var index = from + step
        var remaining = kotlin.math.abs(delta)
        var landed = from

        while (index in currentPlan.units.indices && remaining > 0) {
            val unit = currentPlan.units[index]
            val previous = currentPlan.units[index - step]
            // A paragraph boundary is where the source text is not contiguous.
            if (unit.sourceStart > previous.sourceEnd + 1 || unit.blockKind != previous.blockKind) {
                remaining--
                landed = index
            }
            index += step
        }
        restartAt(landed.coerceIn(0, currentPlan.units.lastIndex))
    }

    suspend fun skipChapter(delta: Int) = lock.withLock {
        val source = book ?: return@withLock
        val target = ((plan?.chapter?.value ?: 0) + delta).coerceIn(0, source.chapters.lastIndex)
        prepareChapter(ChapterIndex(target))
        restartAt(0)
    }

    suspend fun seekTo(position: ReadingPosition) = lock.withLock {
        val source = book ?: return@withLock
        if (position.chapter != plan?.chapter) {
            prepareChapter(position.chapter.coerceIn(source.chapters.indices))
        }
        val unit = plan?.unitAtOffset(position.characterOffset)
        restartAt(unit?.index ?: 0)
    }

    private fun ChapterIndex.coerceIn(range: IntRange): ChapterIndex =
        ChapterIndex(value.coerceIn(range.first, range.last))

    // ---------------------------------------------------------------- sleep timer

    /**
     * Stops playback after [millis], fading out over the last few seconds so that it does not
     * simply cut off mid-word. Pass null to cancel.
     */
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
                kotlinx.coroutines.delay(minOf(remaining, 1000L))
                remaining -= 1000L
            }
            if (isActive) {
                _state.value = _state.value.copy(sleepTimerMillisRemaining = null)
                pause()
            }
        }
    }

    /** Extends a running sleep timer, for the inevitable "just one more chapter". */
    fun extendSleepTimer(extraMillis: Long) {
        val current = _state.value.sleepTimerMillisRemaining ?: 0
        setSleepTimer(current + extraMillis)
    }

    // ---------------------------------------------------------------- internals

    private suspend fun prepareChapter(chapter: ChapterIndex) {
        val source = book ?: return
        val index = ChapterIndex(chapter.value.coerceIn(0, source.chapters.lastIndex))
        val content = withContext(ioDispatcher) { source.chapterContent(index) }
        chapterContent = content
        plan = planBuilder.build(content)
        charactersBeforeChapter = source.chapters.take(index.value)
            .sumOf { it.approximateCharacters.toLong() }
        _state.value = _state.value.copy(
            chapter = index,
            chapterTitle = content.title,
            unitCount = plan?.units?.size ?: 0,
        )
    }

    private fun positionTo(position: ReadingPosition) {
        val unit = plan?.unitAtOffset(position.characterOffset)
        _state.value = _state.value.copy(
            unitIndex = unit?.index ?: 0,
            currentSentence = unit?.displayText.orEmpty(),
            highlightRange = unit?.let { it.sourceStart until it.sourceEnd },
            progress = progressFor(unit),
        )
    }

    private suspend fun restartAt(unitIndex: Int) {
        val wasPlaying = _state.value.status == PlaybackStatus.PLAYING
        stopProducerAndAudio()
        val unit = plan?.unitAt(unitIndex)
        _state.value = _state.value.copy(
            unitIndex = unitIndex,
            currentSentence = unit?.displayText.orEmpty(),
            highlightRange = unit?.let { it.sourceStart until it.sourceEnd },
            wordRange = null,
            progress = progressFor(unit),
        )
        unit?.let { onPositionChanged(positionOf(it)) }
        if (wasPlaying) {
            startProducer()
            _state.value = _state.value.copy(status = PlaybackStatus.PLAYING)
        }
    }

    private fun positionOf(unit: SpeechUnit) = ReadingPosition(
        chapter = unit.chapter,
        characterOffset = unit.sourceStart,
        sentenceIndex = unit.index,
        updatedAtEpochMillis = System.currentTimeMillis(),
    )

    private fun progressFor(unit: SpeechUnit?): ReadingProgress {
        val currentPlan = plan ?: return ReadingProgress.NONE
        val chapterLength = currentPlan.chapterCharacters.coerceAtLeast(1)
        val within = (unit?.sourceStart ?: 0).coerceIn(0, chapterLength)
        val chapterFraction = within.toDouble() / chapterLength
        val read = charactersBeforeChapter + within
        return ReadingProgress(
            fraction = (read.toDouble() / bookCharacters.coerceAtLeast(1)).coerceIn(0.0, 1.0),
            charactersRead = read,
            charactersTotal = bookCharacters,
            chapterFraction = chapterFraction.coerceIn(0.0, 1.0),
        )
    }

    private fun startProducer() {
        producer?.cancel()
        watcher?.cancel()
        clipToUnit.clear()
        pauseClipIds.clear()

        watcher = scope.launch { watchPlayback() }
        producer = scope.launch { produce() }
    }

    /**
     * Renders utterances and hands them to the sink. `enqueue` suspends while the audio queue
     * is full, which is what keeps this loop exactly as far ahead as it needs to be.
     */
    private suspend fun produce() {
        try {
            while (currentCoroutineIsActive()) {
                val voice = loadedVoice ?: break
                val currentPlan = plan ?: break
                var index = nextIndexToRender
                if (index < 0) index = _state.value.unitIndex.coerceAtLeast(0)

                if (index > currentPlan.units.lastIndex) {
                    if (!advanceChapter()) {
                        sink.drain()
                        _state.value = _state.value.copy(status = PlaybackStatus.FINISHED)
                        break
                    }
                    continue
                }

                val unit = currentPlan.units[index]
                val clip = renderWithRetry(voice, unit)
                nextIndexToRender = index + 1

                val clipId = clipIds.incrementAndGet()
                clipToUnit[clipId] = unit
                sink.enqueue(clipId, clip)
                if (unit.trailingPauseMillis > 0) {
                    val pauseId = clipIds.incrementAndGet()
                    clipToUnit[pauseId] = unit
                    pauseClipIds.add(pauseId)
                    sink.enqueueSilence(pauseId, scaledPause(unit.trailingPauseMillis))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = e.message ?: "Speech stopped unexpectedly.",
            )
        }
    }

    /** Pauses shorten as the speed goes up, or fast playback feels oddly stilted. */
    private fun scaledPause(millis: Int): Int =
        (millis / _state.value.params.rate.coerceAtLeast(0.25f)).toInt().coerceIn(0, 4000)

    /**
     * Renders one utterance. A single failure — an unpronounceable symbol, a transient model
     * error — yields silence of a plausible length rather than stopping the book.
     */
    private suspend fun renderWithRetry(voice: LoadedVoice, unit: SpeechUnit): AudioClip = try {
        val clip = voice.synthesise(unit.spokenText, _state.value.params)
        if (clip.isEmpty) AudioClip.silence(120, voice.sampleRate) else Dsp.applyEdgeFades(clip)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AudioClip.silence(200, voice.sampleRate)
    }

    private var nextIndexToRender: Int = -1

    private suspend fun advanceChapter(): Boolean {
        val source = book ?: return false
        val current = plan?.chapter ?: return false
        val next = ChapterIndex(current.value + 1)
        if (next.value > source.chapters.lastIndex) return false
        prepareChapter(next)
        nextIndexToRender = 0
        return true
    }

    /** Keeps the highlight in step with what the speaker is actually saying. */
    private suspend fun watchPlayback() {
        sink.position.collectLatest { position ->
            val unit = clipToUnit[position.clipId] ?: return@collectLatest
            val isPause = position.clipId in pauseClipIds
            val words = if (isPause) emptyList() else unit.spokenWordRanges()
            val wordRange = if (words.isEmpty()) null else {
                // Words are assumed evenly spaced within the utterance. It is an
                // approximation, but at sentence lengths it tracks well enough to follow
                // along with, and it costs nothing — no engine here reports word timings.
                val spokenOffset = (position.fractionThroughClip * unit.spokenText.length).toInt()
                words.firstOrNull { spokenOffset in it }
                    ?: words.lastOrNull { it.last <= spokenOffset }
            }

            _state.value = _state.value.copy(
                unitIndex = unit.index,
                currentSentence = unit.displayText,
                highlightRange = unit.sourceStart until unit.sourceEnd,
                wordRange = wordRange?.let { unit.sourceRangeOf(it.first, it.last - it.first + 1) },
                progress = progressFor(unit),
                bufferedMillis = sink.bufferedMillis(),
                chapter = unit.chapter,
            )
            onPositionChanged(positionOf(unit))
        }
    }

    private suspend fun stopProducerAndAudio() {
        producer?.cancelAndJoin()
        producer = null
        watcher?.cancelAndJoin()
        watcher = null
        sink.flushAndStop()
        clipToUnit.clear()
        pauseClipIds.clear()
        nextIndexToRender = -1
    }

    private suspend fun stopInternal() {
        stopProducerAndAudio()
        sleepTimer?.cancel()
        sleepTimer = null
        _state.value = _state.value.copy(status = PlaybackStatus.IDLE, sleepTimerMillisRemaining = null)
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    override fun close() {
        producer?.cancel()
        watcher?.cancel()
        sleepTimer?.cancel()
        runCatching { sink.close() }
        runCatching { loadedVoice?.close() }
        loadedVoice = null
    }
}
