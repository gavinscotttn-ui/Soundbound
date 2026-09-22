package app.soundbound.desktop.audio

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
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/**
 * Desktop audio output through a `SourceDataLine`.
 *
 * The same shape as the Android sink and for the same reasons: a blocking write provides the
 * back-pressure that throttles synthesis, and which clip is *audible* is read from the line's own
 * frame position rather than inferred from what was last written.
 *
 * `getLongFramePosition` is used rather than `getFramePosition`, which is a 32-bit count and wraps
 * after about a day of playback — long enough that the bug would only ever appear to somebody
 * listening to a very long book.
 */
class DesktopAudioSink(
    private val scope: CoroutineScope,
    private val bufferMillis: Int = 700,
) : AudioSink {

    override var sampleRate: Int = 22_050
        private set

    override var channels: Int = 1
        private set

    private val _position = MutableStateFlow(PlaybackPosition())
    override val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val writeLock = Mutex()
    private var line: SourceDataLine? = null
    private var monitor: Job? = null
    private var volume = 1f

    private val timeline = ArrayDeque<QueuedClip>()
    private var framesWritten = 0L
    private var framesFlushed = 0L

    private data class QueuedClip(val id: Long, val startFrame: Long, val frames: Int) {
        val endFrame: Long get() = startFrame + frames
    }

    override fun start(sampleRate: Int, channels: Int) {
        // The channel count matters as much as the rate: a stereo recording played through a
        // line opened for mono comes out at double speed with only one channel audible.
        if (line != null && this.sampleRate == sampleRate && this.channels == channels) return
        release()
        this.sampleRate = sampleRate
        this.channels = channels

        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate.toFloat(),
            16,
            channels,
            2 * channels, // frame size: two bytes a sample, one sample a channel
            sampleRate.toFloat(),
            false, // little-endian, matching AudioClip.toPcm16
        )

        val info = DataLine.Info(SourceDataLine::class.java, format)
        val opened = try {
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(format, sampleRate * 2 * channels * bufferMillis / 1000)
                it.start()
            }
        } catch (e: LineUnavailableException) {
            throw IllegalStateException(
                "No audio output is available. Another application may have exclusive use of the device.",
                e,
            )
        }

        line = opened
        applyVolume()
        framesWritten = 0
        framesFlushed = 0
        synchronized(timeline) { timeline.clear() }
        _isPlaying.value = true
        startMonitor()
    }

    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                updatePosition()
                delay(60)
            }
        }
    }

    private fun updatePosition() {
        val current = line ?: return
        val head = current.longFramePosition - framesFlushed
        val audible = synchronized(timeline) {
            timeline.firstOrNull { head < it.endFrame } ?: timeline.lastOrNull()
        } ?: return

        _position.value = PlaybackPosition(
            clipId = audible.id,
            frameInClip = (head - audible.startFrame).coerceIn(0, audible.frames.toLong()).toInt(),
            clipFrames = audible.frames,
        )

        synchronized(timeline) {
            while (timeline.size > 1 && head >= timeline.first().endFrame) {
                timeline.removeFirst()
            }
        }
    }

    override suspend fun enqueue(clipId: Long, clip: AudioClip) {
        if (clip.isEmpty) return
        if (clip.sampleRate != sampleRate || clip.channels != channels) {
            start(clip.sampleRate, clip.channels)
        }
        writeLock.withLock {
            val current = line ?: return
            val pcm = clip.toPcm16()
            // Two bytes a sample, and one frame is one sample per channel.
            val frames = pcm.size / 2 / channels

            synchronized(timeline) {
                timeline.addLast(QueuedClip(clipId, framesWritten, frames))
                framesWritten += frames
            }

            withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < pcm.size) {
                    // Blocks until the line has room, which is the back-pressure the pipeline
                    // relies on to stay a sentence or two ahead rather than a chapter.
                    val written = current.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) break
                    offset += written
                }
            }
        }
    }

    override suspend fun enqueueSilence(clipId: Long, millis: Int) {
        if (millis <= 0) return
        enqueue(clipId, AudioClip.silence(millis, sampleRate, channels))
    }

    override fun pause() {
        runCatching { line?.stop() }
        _isPlaying.value = false
    }

    override fun resume() {
        runCatching { line?.start() }
        _isPlaying.value = true
    }

    override fun flushAndStop() {
        val current = line ?: return
        runCatching {
            current.stop()
            // The line's frame position keeps counting across a flush, so the offset is recorded
            // rather than assuming it resets.
            framesFlushed = current.longFramePosition
            current.flush()
            current.start()
        }
        synchronized(timeline) {
            timeline.clear()
            framesWritten = 0
        }
        _position.value = PlaybackPosition()
        _isPlaying.value = false
    }

    override suspend fun drain() {
        val current = line ?: return
        withContext(Dispatchers.IO) { runCatching { current.drain() } }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        val current = line ?: return
        runCatching {
            if (current.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val control = current.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                // Gain is in decibels, so a linear volume has to be converted — and zero has to be
                // special-cased, since log(0) is negative infinity.
                val db = if (volume <= 0.0001f) {
                    control.minimum
                } else {
                    (20.0 * kotlin.math.log10(volume.toDouble())).toFloat()
                }
                control.value = db.coerceIn(control.minimum, control.maximum)
            }
        }
    }

    override fun bufferedMillis(): Int {
        val current = line ?: return 0
        val head = current.longFramePosition - framesFlushed
        val remaining = (framesWritten - head).coerceAtLeast(0)
        return (remaining * 1000 / sampleRate).toInt()
    }

    private fun release() {
        monitor?.cancel()
        monitor = null
        line?.let { current ->
            runCatching {
                current.stop()
                current.flush()
                current.close()
            }
        }
        line = null
        synchronized(timeline) {
            timeline.clear()
            framesWritten = 0
            framesFlushed = 0
        }
    }

    override fun close() {
        release()
        _isPlaying.value = false
        _position.value = PlaybackPosition()
    }
}
