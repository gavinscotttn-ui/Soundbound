package app.soundbound.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A block of mono audio as normalised floats in −1.0..1.0, which is what every neural
 * synthesiser emits and the only form the rest of the pipeline deals in. Conversion to
 * 16-bit PCM happens once, at the very edge, where the platform's audio device wants it.
 */
class AudioClip(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    }

    val durationMillis: Long get() = (samples.size * 1000L) / sampleRate
    val isEmpty: Boolean get() = samples.isEmpty()

    /** Peak absolute amplitude, for level metering and normalisation. */
    fun peak(): Float {
        var peak = 0f
        samples.forEach { value ->
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    /** Root-mean-square level, a far better guide to perceived loudness than the peak. */
    fun rms(): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        samples.forEach { sum += it.toDouble() * it }
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    /** Little-endian signed 16-bit PCM, the lowest common denominator of audio output. */
    fun toPcm16(): ByteArray {
        val out = ByteArray(samples.size * 2)
        var index = 0
        samples.forEach { value ->
            val clamped = min(1f, max(-1f, value))
            val scaled = (clamped * 32767f).roundToInt()
            out[index++] = (scaled and 0xFF).toByte()
            out[index++] = ((scaled shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun append(other: AudioClip): AudioClip {
        require(other.sampleRate == sampleRate) {
            "Cannot join ${other.sampleRate} Hz audio onto $sampleRate Hz audio"
        }
        val joined = FloatArray(samples.size + other.samples.size)
        samples.copyInto(joined)
        other.samples.copyInto(joined, samples.size)
        return AudioClip(joined, sampleRate)
    }

    companion object {
        fun silence(millis: Int, sampleRate: Int): AudioClip =
            AudioClip(FloatArray((sampleRate.toLong() * millis / 1000).toInt()), sampleRate)

        fun empty(sampleRate: Int) = AudioClip(FloatArray(0), sampleRate)
    }
}

/** Digital signal processing used between the synthesiser and the speaker. */
object Dsp {

    /**
     * Applies a raised-cosine fade at each end.
     *
     * Neural vocoders often start and stop on a non-zero sample. Concatenating those clips
     * produces an audible tick at every sentence boundary — the single most common reason a
     * home-made read-aloud feature sounds cheap. A 6 ms fade removes it inaudibly.
     */
    fun applyEdgeFades(clip: AudioClip, fadeMillis: Int = 6): AudioClip {
        if (clip.isEmpty) return clip
        val fadeSamples = min(
            (clip.sampleRate.toLong() * fadeMillis / 1000).toInt(),
            clip.samples.size / 2,
        )
        if (fadeSamples <= 0) return clip
        val out = clip.samples.copyOf()
        for (i in 0 until fadeSamples) {
            val gain = (0.5 - 0.5 * cos(PI * i / fadeSamples)).toFloat()
            out[i] *= gain
            out[out.size - 1 - i] *= gain
        }
        return AudioClip(out, clip.sampleRate)
    }

    /** Trims near-silence from both ends, leaving a short tail so words do not sound clipped. */
    fun trimSilence(clip: AudioClip, thresholdDb: Float = -50f, keepMillis: Int = 20): AudioClip {
        if (clip.isEmpty) return clip
        val threshold = dbToLinear(thresholdDb)
        var start = 0
        while (start < clip.samples.size && kotlin.math.abs(clip.samples[start]) < threshold) start++
        var end = clip.samples.size - 1
        while (end > start && kotlin.math.abs(clip.samples[end]) < threshold) end--
        if (start >= end) return AudioClip.empty(clip.sampleRate)

        val keep = (clip.sampleRate.toLong() * keepMillis / 1000).toInt()
        val from = max(0, start - keep)
        val to = min(clip.samples.size, end + keep)
        return AudioClip(clip.samples.copyOfRange(from, to), clip.sampleRate)
    }

    /** Scales the clip so its peak sits at [targetPeak], leaving quiet clips alone. */
    fun normalisePeak(clip: AudioClip, targetPeak: Float = 0.95f, maxGain: Float = 4f): AudioClip {
        val peak = clip.peak()
        if (peak <= 1e-6f) return clip
        val gain = min(targetPeak / peak, maxGain)
        if (gain in 0.99f..1.01f) return clip
        return AudioClip(FloatArray(clip.samples.size) { clip.samples[it] * gain }, clip.sampleRate)
    }

    fun applyGain(clip: AudioClip, gain: Float): AudioClip {
        if (gain == 1f) return clip
        return AudioClip(FloatArray(clip.samples.size) { clip.samples[it] * gain }, clip.sampleRate)
    }

    /**
     * Resamples with Catmull-Rom interpolation.
     *
     * Different voices run at different rates — Piper models are 16, 22.05 or 24 kHz — and
     * switching voice mid-book must not change the device's output format, so everything is
     * converted to one rate before it reaches the speaker. Cubic interpolation is used rather
     * than linear because linear interpolation of speech is audibly dull.
     */
    fun resample(clip: AudioClip, targetRate: Int): AudioClip {
        require(targetRate > 0) { "targetRate must be positive" }
        if (clip.sampleRate == targetRate || clip.isEmpty) {
            return if (clip.sampleRate == targetRate) clip else AudioClip(clip.samples, targetRate)
        }
        val ratio = targetRate.toDouble() / clip.sampleRate
        val outputLength = (clip.samples.size * ratio).toInt().coerceAtLeast(1)
        val source = clip.samples
        val out = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val position = i / ratio
            val index = position.toInt()
            val t = (position - index).toFloat()
            val p0 = source[(index - 1).coerceIn(0, source.size - 1)]
            val p1 = source[index.coerceIn(0, source.size - 1)]
            val p2 = source[(index + 1).coerceIn(0, source.size - 1)]
            val p3 = source[(index + 2).coerceIn(0, source.size - 1)]
            out[i] = catmullRom(p0, p1, p2, p3, t)
        }
        return AudioClip(out, targetRate)
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            (2f * p1) +
                (-p0 + p2) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (-p0 + 3f * p1 - 3f * p2 + p3) * t3
            )
    }

    /**
     * Changes playback speed without changing pitch, using WSOLA (waveform similarity
     * overlap-add).
     *
     * The naive approach — resampling — makes a narrator sound like a chipmunk at 1.5×, which
     * is exactly what listeners complain about. WSOLA finds, for each output frame, the input
     * frame that best matches what has already been written, so the waveform stays continuous
     * and the voice keeps its own pitch.
     */
    fun changeSpeed(clip: AudioClip, speed: Float): AudioClip {
        if (clip.isEmpty) return clip
        if (speed in 0.995f..1.005f) return clip
        val factor = speed.coerceIn(0.25f, 4f)

        val frameSize = (clip.sampleRate * 0.040f).toInt().coerceAtLeast(64)   // 40 ms
        val synthesisHop = frameSize / 2
        val analysisHop = (synthesisHop * factor).toInt().coerceAtLeast(1)
        val searchRadius = (clip.sampleRate * 0.008f).toInt().coerceAtLeast(1) // ±8 ms

        val source = clip.samples
        val outputLength = (source.size / factor).toInt() + frameSize
        val out = FloatArray(outputLength)
        val window = FloatArray(frameSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (frameSize - 1))).toFloat() }
        val normalisation = FloatArray(outputLength)

        var analysisIndex = 0
        var outputIndex = 0
        var expectedNext = 0

        while (analysisIndex + frameSize < source.size && outputIndex + frameSize < outputLength) {
            val best = if (outputIndex == 0) analysisIndex else {
                bestMatchOffset(source, expectedNext, out, outputIndex, frameSize, searchRadius)
            }
            for (i in 0 until frameSize) {
                val sampleIndex = best + i
                if (sampleIndex >= source.size) break
                out[outputIndex + i] += source[sampleIndex] * window[i]
                normalisation[outputIndex + i] += window[i]
            }
            expectedNext = best + synthesisHop
            analysisIndex += analysisHop
            outputIndex += synthesisHop
        }

        val written = (outputIndex + frameSize).coerceAtMost(outputLength)
        val result = FloatArray(written)
        for (i in 0 until written) {
            val divisor = normalisation[i]
            result[i] = if (divisor > 1e-4f) out[i] / divisor else out[i]
        }
        return AudioClip(result, clip.sampleRate)
    }

    /** Finds the input offset whose overlap best matches what has already been synthesised. */
    private fun bestMatchOffset(
        source: FloatArray,
        centre: Int,
        out: FloatArray,
        outputIndex: Int,
        frameSize: Int,
        searchRadius: Int,
    ): Int {
        val overlap = frameSize / 2
        var bestOffset = centre
        var bestScore = Float.NEGATIVE_INFINITY

        val from = (centre - searchRadius).coerceAtLeast(0)
        val to = (centre + searchRadius).coerceAtMost(source.size - frameSize - 1)
        if (to < from) return centre.coerceIn(0, (source.size - frameSize - 1).coerceAtLeast(0))

        for (candidate in from..to) {
            var score = 0f
            var i = 0
            // Stride of 2: at 22 kHz this halves the search cost with no audible difference.
            while (i < overlap) {
                score += source[candidate + i] * out[outputIndex + i]
                i += 2
            }
            if (score > bestScore) {
                bestScore = score
                bestOffset = candidate
            }
        }
        return bestOffset
    }

    /**
     * Shifts pitch by [semitones] without changing duration: speed-change by the inverse
     * ratio, then resample back to the original rate.
     */
    fun changePitch(clip: AudioClip, semitones: Float): AudioClip {
        if (clip.isEmpty || kotlin.math.abs(semitones) < 0.01f) return clip
        val ratio = Math.pow(2.0, semitones / 12.0).toFloat()
        val stretched = changeSpeed(clip, 1f / ratio)
        val resampled = resample(
            AudioClip(stretched.samples, (clip.sampleRate * ratio).toInt().coerceAtLeast(1)),
            clip.sampleRate,
        )
        return AudioClip(resampled.samples, clip.sampleRate)
    }

    /** Crossfades [tail] into [head] over [millis], for seamless joins between clips. */
    fun crossfade(head: AudioClip, tail: AudioClip, millis: Int = 12): AudioClip {
        require(head.sampleRate == tail.sampleRate) { "Crossfade needs matching sample rates" }
        if (head.isEmpty) return tail
        if (tail.isEmpty) return head
        val fade = min(
            (head.sampleRate.toLong() * millis / 1000).toInt(),
            min(head.samples.size, tail.samples.size),
        )
        if (fade <= 0) return head.append(tail)

        val out = FloatArray(head.samples.size + tail.samples.size - fade)
        head.samples.copyInto(out, 0, 0, head.samples.size - fade)
        for (i in 0 until fade) {
            val t = i.toFloat() / fade
            val fadeOut = cos(t * PI / 2).toFloat()
            val fadeIn = sin(t * PI / 2).toFloat()
            out[head.samples.size - fade + i] =
                head.samples[head.samples.size - fade + i] * fadeOut + tail.samples[i] * fadeIn
        }
        tail.samples.copyInto(out, head.samples.size, fade, tail.samples.size)
        return AudioClip(out, head.sampleRate)
    }

    fun dbToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()

    fun linearToDb(linear: Float): Float =
        if (linear <= 1e-9f) -180f else (20.0 * kotlin.math.log10(linear.toDouble())).toFloat()
}
