package app.soundbound.core.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Stereo survives the signal path.
 *
 * Every one of these operations is a place where interleaved audio can be quietly ruined: a cut
 * on an odd sample swaps the channels for the rest of the book, and an interpolation that runs
 * across the interleaving mixes left into right and collapses the stereo image. Neither failure
 * throws, and both are obvious the moment a listener puts headphones on.
 *
 * The two channels are given plainly different content — a tone on the left, silence or a
 * different tone on the right — so that any leak between them shows up as a number.
 */
@DisplayName("Stereo through the audio path")
class StereoDspTest {

    private val rate = 44_100

    /** A clip with a 440 Hz tone on the left and nothing at all on the right. */
    private fun leftOnly(frames: Int): AudioClip {
        val samples = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            samples[frame * 2] = sin(2.0 * PI * 440 * frame / rate).toFloat() * 0.8f
            samples[frame * 2 + 1] = 0f
        }
        return AudioClip(samples, rate, channels = 2)
    }

    private fun channelPeak(clip: AudioClip, channel: Int): Float {
        var peak = 0f
        var index = channel
        while (index < clip.samples.size) {
            val magnitude = abs(clip.samples[index])
            if (magnitude > peak) peak = magnitude
            index += clip.channels
        }
        return peak
    }

    @Test
    fun `a stereo clip reports its length in frames, not samples`() {
        // The commonest mistake: a stereo clip whose duration reads double its real length,
        // which puts every position in a book out by a factor of two.
        val clip = leftOnly(rate) // exactly one second
        assertEquals(rate, clip.frameCount)
        assertEquals(1000L, clip.durationMillis)
    }

    @Test
    fun `interleaving and splitting apart are exact inverses`() {
        val clip = leftOnly(1000)
        val split = clip.deinterleave()
        val rejoined = AudioClip.interleave(split, rate)

        assertEquals(clip.samples.size, rejoined.samples.size)
        assertTrue(clip.samples.indices.all { abs(clip.samples[it] - rejoined.samples[it]) < 1e-6f })
    }

    @Test
    fun `changing speed keeps the channels apart`() {
        val stretched = Dsp.changeSpeed(leftOnly(rate), 1.5f)

        assertEquals(2, stretched.channels)
        assertTrue(channelPeak(stretched, 0) > 0.5f, "The left channel should still carry the tone")
        assertTrue(
            channelPeak(stretched, 1) < 0.01f,
            "The right channel was silent and must stay silent; it peaked at " +
                channelPeak(stretched, 1),
        )
    }

    @Test
    fun `changing speed changes the duration by the right factor`() {
        val original = leftOnly(rate * 2)
        val faster = Dsp.changeSpeed(original, 2f)
        // Within a frame's worth: the overlap-add leaves a partial window at the end.
        assertTrue(
            abs(faster.durationMillis - 1000L) < 80,
            "Two seconds at double speed should be about one second, was ${faster.durationMillis}ms",
        )
    }

    @Test
    fun `resampling keeps the channels apart`() {
        val resampled = Dsp.resample(leftOnly(rate), 22_050)

        assertEquals(2, resampled.channels)
        assertEquals(22_050, resampled.sampleRate)
        assertTrue(abs(resampled.durationMillis - 1000L) < 20)
        assertTrue(channelPeak(resampled, 0) > 0.5f)
        assertTrue(
            channelPeak(resampled, 1) < 0.01f,
            "Interpolating across the interleaving would leak the tone into the silent channel",
        )
    }

    @Test
    fun `trimming silence cuts on a frame boundary`() {
        // Leading silence of an odd number of frames: a cut that lands mid-frame swaps the
        // channels over for the rest of the clip.
        val quiet = FloatArray(101 * 2)
        val loud = leftOnly(1000)
        val clip = AudioClip(quiet + loud.samples, rate, channels = 2)

        val trimmed = Dsp.trimSilence(clip, keepMillis = 0)

        assertEquals(0, trimmed.samples.size % 2, "The result must be a whole number of frames")
        assertTrue(channelPeak(trimmed, 1) < 0.01f, "The right channel must still be the silent one")
    }

    @Test
    fun `edge fades apply to both channels together`() {
        val faded = Dsp.applyEdgeFades(leftOnly(rate), fadeMillis = 10)

        assertEquals(2, faded.channels)
        // The very first frame is faded to nothing on both sides.
        assertTrue(abs(faded.samples[0]) < 1e-4f)
        assertTrue(abs(faded.samples[1]) < 1e-4f)
    }

    @Test
    fun `crossfading keeps the channels aligned`() {
        val head = leftOnly(1000)
        val tail = leftOnly(1000)
        val joined = Dsp.crossfade(head, tail, millis = 5)

        assertEquals(2, joined.channels)
        assertEquals(0, joined.samples.size % 2)
        assertTrue(
            channelPeak(joined, 1) < 0.01f,
            "A fade that started mid-frame would bleed the left channel into the right",
        )
    }

    @Test
    fun `silence and joining respect the channel count`() {
        val silence = AudioClip.silence(millis = 500, sampleRate = rate, channels = 2)
        assertEquals(2, silence.channels)
        assertEquals(500L, silence.durationMillis)

        val joined = silence.append(leftOnly(1000))
        assertEquals(2, joined.channels)
        assertEquals(silence.frameCount + 1000, joined.frameCount)
    }

    @Test
    fun `mono still behaves exactly as it did`() {
        // The synthesised-speech path is mono and must be untouched by any of this.
        val mono = AudioClip(FloatArray(rate) { sin(2.0 * PI * 440 * it / rate).toFloat() }, rate)
        assertEquals(1, mono.channels)
        assertEquals(1000L, mono.durationMillis)
        assertEquals(rate, mono.frameCount)

        val faster = Dsp.changeSpeed(mono, 2f)
        assertEquals(1, faster.channels)
        assertTrue(abs(faster.durationMillis - 500L) < 60)
    }
}
