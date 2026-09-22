package app.soundbound.core.player

import app.soundbound.core.audio.AudioClip
import kotlinx.coroutines.flow.StateFlow

/** Where playback has reached, reported by the platform's audio device. */
data class PlaybackPosition(
    /** Identifier of the clip currently audible, or −1 when nothing is playing. */
    val clipId: Long = -1,
    /** Frames of that clip already played. */
    val frameInClip: Int = 0,
    val clipFrames: Int = 0,
) {
    val fractionThroughClip: Float
        get() = if (clipFrames <= 0) 0f else (frameInClip.toFloat() / clipFrames).coerceIn(0f, 1f)
}

/**
 * The platform's audio output.
 *
 * Clips are enqueued with an identifier and played back to back with no gap, which is what
 * makes a synthesised book sound like a recording rather than a list of sentences. The sink
 * reports which clip is *audible* — not which was most recently handed over — because the
 * queue runs a second or two ahead and highlighting the wrong sentence is immediately obvious.
 *
 * Implementations: `SourceDataLine` on the desktop, `AudioTrack` in streaming mode on Android.
 */
interface AudioSink : AutoCloseable {

    val sampleRate: Int

    /** How many channels the device is currently open for: 1 for speech, 2 for a recording. */
    val channels: Int get() = 1

    val position: StateFlow<PlaybackPosition>

    val isPlaying: StateFlow<Boolean>

    /**
     * Opens the device. Called again when the format changes — a different voice runs at a
     * different rate, and a recorded audiobook may be stereo where synthesised speech is not.
     */
    fun start(sampleRate: Int, channels: Int = 1)

    /**
     * Queues a clip, suspending while the queue is full so that synthesis naturally throttles
     * itself to playback rather than racing ahead and filling memory.
     */
    suspend fun enqueue(clipId: Long, clip: AudioClip)

    /** Queues silence, used for the pauses between sentences and paragraphs. */
    suspend fun enqueueSilence(clipId: Long, millis: Int)

    /**
     * Frames of that clip already played — see [PlaybackPosition]. A frame is one instant of
     * time whatever the channel count, so a position means the same thing in mono and stereo.
     */

    fun pause()

    fun resume()

    /** Stops and discards everything queued. */
    fun flushAndStop()

    /** Suspends until everything queued has been played. */
    suspend fun drain()

    fun setVolume(volume: Float)

    /** Approximate milliseconds of audio queued but not yet played. */
    fun bufferedMillis(): Int
}
