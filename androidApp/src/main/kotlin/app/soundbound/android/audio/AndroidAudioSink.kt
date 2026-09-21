package app.soundbound.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import app.soundbound.core.audio.AudioClip
import app.soundbound.core.player.AudioSink
import app.soundbound.core.player.PlaybackPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android audio output, through an [AudioTrack] in streaming mode.
 *
 * Two decisions matter here.
 *
 * Back-pressure comes from the device rather than from a timer: `enqueue` performs a blocking write
 * to the track, which returns only as the hardware consumes the audio. Synthesis is therefore
 * throttled by playback for free, and the app never renders more than the buffer ahead.
 *
 * Which clip is *audible* is read from `playbackHeadPosition`, not inferred from what was last
 * written. Those differ by the whole buffer — a second or two — and using the wrong one is what
 * makes a highlighted sentence run ahead of the voice.
 */
class AndroidAudioSink(
    private val scope: CoroutineScope,
    /** Buffer length. Longer is more robust against stalls; shorter responds to a pause sooner. */
    private val bufferMillis: Int = 900,
) : AudioSink {

    override var sampleRate: Int = 22_050
        private set

    private val _position = MutableStateFlow(PlaybackPosition())
    override val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val writeLock = Mutex()
    private var track: AudioTrack? = null
    private var monitor: Job? = null

    /** Clips written since the last flush, with the frame at which each begins. */
    private val timeline = ArrayDeque<QueuedClip>()
    private var framesWritten = 0L
    private var volume = 1f

    private data class QueuedClip(val id: Long, val startFrame: Long, val frames: Int) {
        val endFrame: Long get() = startFrame + frames
    }

    override fun start(sampleRate: Int) {
        if (track != null && this.sampleRate == sampleRate) return
        release()
        this.sampleRate = sampleRate

        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4_096)
        val wanted = sampleRate * 2 * bufferMillis / 1000
        val bufferBytes = maxOf(minimum, wanted)

        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // SPEECH rather than MUSIC: it tells the system this is an audiobook, which
                    // affects ducking behaviour, accessibility routing and hearing-aid profiles.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()

        created.setVolume(volume)
        created.play()
        track = created
        framesWritten = 0
        timeline.clear()
        _isPlaying.value = true
        startMonitor()
    }

    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                updatePosition()
                // 60 ms is well inside a frame at 60 Hz and costs nothing; polling faster would
                // not make the highlight visibly tighter.
                delay(60)
            }
        }
    }

    private fun updatePosition() {
        val current = track ?: return
        // playbackHeadPosition is frames since the last flush, as an unsigned 32-bit count.
        val head = current.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val audible = synchronized(timeline) {
            timeline.firstOrNull { head < it.endFrame } ?: timeline.lastOrNull()
        } ?: return

        _position.value = PlaybackPosition(
            clipId = audible.id,
            frameInClip = (head - audible.startFrame).coerceIn(0, audible.frames.toLong()).toInt(),
            clipFrames = audible.frames,
        )

        // Drop clips the device has finished with, so the list stays short over a long chapter.
        synchronized(timeline) {
            while (timeline.size > 1 && head >= timeline.first().endFrame) {
                timeline.removeFirst()
            }
        }
    }

    override suspend fun enqueue(clipId: Long, clip: AudioClip) {
        if (clip.isEmpty) return
        if (clip.sampleRate != sampleRate) start(clip.sampleRate)
        writeLock.withLock {
            val current = track ?: return
            val pcm = clip.toPcm16()
            val frames = pcm.size / 2

            synchronized(timeline) {
                timeline.addLast(QueuedClip(clipId, framesWritten, frames))
                framesWritten += frames
            }

            withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < pcm.size) {
                    // A blocking write returns as the hardware drains, which is the back-pressure
                    // the whole pipeline relies on.
                    val written = current.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
            }
        }
    }

    override suspend fun enqueueSilence(clipId: Long, millis: Int) {
        if (millis <= 0) return
        enqueue(clipId, AudioClip.silence(millis, sampleRate))
    }

    override fun pause() {
        runCatching { track?.pause() }
        _isPlaying.value = false
    }

    override fun resume() {
        runCatching { track?.play() }
        _isPlaying.value = true
    }

    override fun flushAndStop() {
        val current = track ?: return
        runCatching {
            current.pause()
            // flush() resets playbackHeadPosition, so the timeline has to be reset with it.
            current.flush()
        }
        synchronized(timeline) {
            timeline.clear()
            framesWritten = 0
        }
        _position.value = PlaybackPosition()
        _isPlaying.value = false
    }

    override suspend fun drain() {
        val current = track ?: return
        while (true) {
            val head = current.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val remaining = synchronized(timeline) { framesWritten - head }
            if (remaining <= 0) return
            delay((remaining * 1000 / sampleRate).coerceIn(10, 250))
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        runCatching { track?.setVolume(this.volume) }
    }

    override fun bufferedMillis(): Int {
        val current = track ?: return 0
        val head = current.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val remaining = (framesWritten - head).coerceAtLeast(0)
        return (remaining * 1000 / sampleRate).toInt()
    }

    /** Lowers the volume for a transient interruption, rather than stopping. */
    fun duck(ducked: Boolean) {
        runCatching { track?.setVolume(if (ducked) volume * 0.25f else volume) }
    }

    private fun release() {
        monitor?.cancel()
        monitor = null
        track?.let { current ->
            runCatching {
                current.pause()
                current.flush()
                current.release()
            }
        }
        track = null
        synchronized(timeline) {
            timeline.clear()
            framesWritten = 0
        }
    }

    override fun close() {
        release()
        _isPlaying.value = false
        _position.value = PlaybackPosition()
    }

    companion object {
        /** The stream Soundbound plays on, for anything that needs to ask the AudioManager. */
        const val STREAM_TYPE = AudioManager.STREAM_MUSIC
    }
}
