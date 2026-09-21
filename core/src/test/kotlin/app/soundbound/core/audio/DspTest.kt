package app.soundbound.core.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspTest {

    private fun tone(frequency: Double, millis: Int, sampleRate: Int = 22_050): AudioClip {
        val count = sampleRate * millis / 1000
        return AudioClip(
            FloatArray(count) { (0.5 * sin(2.0 * PI * frequency * it / sampleRate)).toFloat() },
            sampleRate,
        )
    }

    /** Estimates the dominant frequency by counting zero crossings. */
    private fun estimateFrequency(clip: AudioClip): Double {
        var crossings = 0
        for (i in 1 until clip.samples.size) {
            if ((clip.samples[i - 1] < 0f) != (clip.samples[i] < 0f)) crossings++
        }
        val seconds = clip.samples.size.toDouble() / clip.sampleRate
        return crossings / 2.0 / seconds
    }

    @Test
    fun `pcm16 conversion round trips through the WAV codec`() {
        val original = tone(440.0, 100)
        val decoded = WavCodec.decode(WavCodec.encode(original))
        assertEquals(original.sampleRate, decoded.sampleRate)
        assertEquals(original.samples.size, decoded.samples.size)
        original.samples.forEachIndexed { index, value ->
            assertTrue(abs(value - decoded.samples[index]) < 1e-3f) {
                "Sample $index drifted from $value to ${decoded.samples[index]}"
            }
        }
    }

    @Test
    fun `pcm16 clamps rather than wrapping around`() {
        val loud = AudioClip(floatArrayOf(2f, -2f, 0f), 22_050)
        val decoded = WavCodec.decode(WavCodec.encode(loud))
        assertTrue(decoded.samples[0] > 0.99f)
        assertTrue(decoded.samples[1] < -0.99f)
    }

    @Test
    fun `resampling preserves pitch and changes length`() {
        val original = tone(440.0, 500, sampleRate = 22_050)
        val resampled = Dsp.resample(original, 16_000)
        assertEquals(16_000, resampled.sampleRate)
        // Duration must be preserved to within a few milliseconds.
        assertTrue(abs(resampled.durationMillis - original.durationMillis) < 10) {
            "Duration went from ${original.durationMillis} ms to ${resampled.durationMillis} ms"
        }
        assertTrue(abs(estimateFrequency(resampled) - 440.0) < 15.0) {
            "Pitch drifted to ${estimateFrequency(resampled)} Hz"
        }
    }

    @Test
    fun `speed change shortens the clip without moving the pitch`() {
        val original = tone(300.0, 1000)
        val faster = Dsp.changeSpeed(original, 1.5f)

        val expected = original.durationMillis / 1.5
        assertTrue(abs(faster.durationMillis - expected) < expected * 0.15) {
            "Expected about ${expected.toLong()} ms, got ${faster.durationMillis} ms"
        }
        // The whole point of WSOLA: the pitch must not move.
        assertTrue(abs(estimateFrequency(faster) - 300.0) < 25.0) {
            "Pitch moved to ${estimateFrequency(faster)} Hz at 1.5x speed"
        }
    }

    @Test
    fun `slowing down also holds the pitch`() {
        val original = tone(300.0, 1000)
        val slower = Dsp.changeSpeed(original, 0.75f)
        val expected = original.durationMillis / 0.75
        assertTrue(abs(slower.durationMillis - expected) < expected * 0.2) {
            "Expected about ${expected.toLong()} ms, got ${slower.durationMillis} ms"
        }
        assertTrue(abs(estimateFrequency(slower) - 300.0) < 25.0) {
            "Pitch moved to ${estimateFrequency(slower)} Hz at 0.75x speed"
        }
    }

    @Test
    fun `pitch change moves the pitch without changing duration`() {
        val original = tone(300.0, 1000)
        val raised = Dsp.changePitch(original, 12f)  // one octave
        assertTrue(abs(raised.durationMillis - original.durationMillis) < original.durationMillis * 0.2) {
            "Duration changed from ${original.durationMillis} to ${raised.durationMillis}"
        }
        val frequency = estimateFrequency(raised)
        assertTrue(frequency > 480.0) { "Expected roughly 600 Hz after an octave up, got $frequency" }
    }

    @Test
    fun `edge fades start and end at silence`() {
        val block = AudioClip(FloatArray(22_050) { 0.8f }, 22_050)
        val faded = Dsp.applyEdgeFades(block, fadeMillis = 10)
        assertTrue(abs(faded.samples.first()) < 0.01f) { "First sample was ${faded.samples.first()}" }
        assertTrue(abs(faded.samples.last()) < 0.01f) { "Last sample was ${faded.samples.last()}" }
        assertEquals(0.8f, faded.samples[11_000], 0.001f)
    }

    @Test
    fun `silence trimming keeps the audio and drops the padding`() {
        val silence = FloatArray(5_000)
        val speech = FloatArray(5_000) { 0.5f }
        val padded = AudioClip(silence + speech + silence, 22_050)
        val trimmed = Dsp.trimSilence(padded, keepMillis = 0)
        assertTrue(trimmed.samples.size in 4_900..5_100) {
            "Trimmed to ${trimmed.samples.size} samples, expected about 5000"
        }
    }

    @Test
    fun `crossfade produces the right length and no discontinuity`() {
        val a = AudioClip(FloatArray(10_000) { 0.5f }, 22_050)
        val b = AudioClip(FloatArray(10_000) { -0.5f }, 22_050)
        val joined = Dsp.crossfade(a, b, millis = 20)
        val fade = 22_050 * 20 / 1000
        assertEquals(20_000 - fade, joined.samples.size)
        // No sample may jump by more than a modest step across the join.
        for (i in 1 until joined.samples.size) {
            assertTrue(abs(joined.samples[i] - joined.samples[i - 1]) < 0.05f) {
                "Discontinuity at sample $i"
            }
        }
    }

    @Test
    fun `peak normalisation reaches the target and respects the gain ceiling`() {
        val moderate = AudioClip(FloatArray(1000) { 0.4f }, 22_050)
        assertEquals(0.95f, Dsp.normalisePeak(moderate).peak(), 0.01f)

        // A very quiet clip is only lifted as far as the gain ceiling allows: amplifying
        // 20 dB of near-silence would just bring up the noise floor.
        val veryQuiet = AudioClip(FloatArray(1000) { 0.1f }, 22_050)
        assertEquals(0.4f, Dsp.normalisePeak(veryQuiet, maxGain = 4f).peak(), 0.01f)

        val silent = AudioClip(FloatArray(1000), 22_050)
        assertEquals(0f, Dsp.normalisePeak(silent).peak())
    }

    @Test
    fun `decibel conversion round trips`() {
        assertEquals(1f, Dsp.dbToLinear(0f), 1e-6f)
        assertEquals(-6f, Dsp.linearToDb(Dsp.dbToLinear(-6f)), 1e-3f)
    }
}
