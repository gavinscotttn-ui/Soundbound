package app.soundbound.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A block of audio as normalised floats in −1.0..1.0, which is what every neural synthesiser
 * emits and the only form the rest of the pipeline deals in. Conversion to 16-bit PCM happens
 * once, at the very edge, where the platform's audio device wants it.
 *
 * Synthesised speech is mono, which is why [channels] defaults to one and almost every clip in
 * the app is one. A recorded audiobook may be stereo, and downmixing it would be audible on
 * headphones the moment a production uses more than one voice, so stereo is carried through
 * intact instead. Stereo samples are interleaved: left, right, left, right.
 */
class AudioClip(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int = 1,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels in 1..2) { "channels must be 1 or 2, was $channels" }
    }

    /** Sample frames — one per instant of time, whatever the channel count. */
    val frameCount: Int get() = samples.size / channels

    val durationMillis: Long get() = (frameCount * 1000L) / sampleRate
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
        require(other.channels == channels) {
            "Cannot join ${other.channels}-channel audio onto $channels-channel audio"
        }
        val joined = FloatArray(samples.size + other.samples.size)
        samples.copyInto(joined)
        other.samples.copyInto(joined, samples.size)
        return AudioClip(joined, sampleRate, channels)
    }

    /** Splits interleaved stereo into one array per channel. Mono is returned unchanged. */
    fun deinterleave(): Array<FloatArray> {
        if (channels == 1) return arrayOf(samples)
        return Array(channels) { channel ->
            FloatArray(frameCount) { frame -> samples[frame * channels + channel] }
        }
    }

    companion object {
        fun silence(millis: Int, sampleRate: Int, channels: Int = 1): AudioClip =
            AudioClip(
                FloatArray((sampleRate.toLong() * millis / 1000).toInt() * channels),
                sampleRate,
                channels,
            )

        fun empty(sampleRate: Int, channels: Int = 1) = AudioClip(FloatArray(0), sampleRate, channels)

        /** Interleaves per-channel arrays back into one clip. */
        fun interleave(channels: Array<FloatArray>, sampleRate: Int): AudioClip {
            if (channels.size == 1) return AudioClip(channels[0], sampleRate, 1)
            val frames = channels.minOf { it.size }
            val out = FloatArray(frames * channels.size)
            for (frame in 0 until frames) {
                for (channel in channels.indices) {
                    out[frame * channels.size + channel] = channels[channel][frame]
                }
            }
            return AudioClip(out, sampleRate, channels.size)
        }
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
        val fadeFrames = min(
            (clip.sampleRate.toLong() * fadeMillis / 1000).toInt(),
            clip.frameCount / 2,
        )
        if (fadeFrames <= 0) return clip
        val out = clip.samples.copyOf()
        val channels = clip.channels
        for (frame in 0 until fadeFrames) {
            val gain = (0.5 - 0.5 * cos(PI * frame / fadeFrames)).toFloat()
            for (channel in 0 until channels) {
                out[frame * channels + channel] *= gain
                out[out.size - 1 - (frame * channels + channel)] *= gain
            }
        }
        return AudioClip(out, clip.sampleRate, channels)
    }

    /** Trims near-silence from both ends, leaving a short tail so words do not sound clipped. */
    fun trimSilence(clip: AudioClip, thresholdDb: Float = -50f, keepMillis: Int = 20): AudioClip {
        if (clip.isEmpty) return clip
        val threshold = dbToLinear(thresholdDb)
        val channels = clip.channels
        var start = 0
        while (start < clip.samples.size && kotlin.math.abs(clip.samples[start]) < threshold) start++
        var end = clip.samples.size - 1
        while (end > start && kotlin.math.abs(clip.samples[end]) < threshold) end--
        if (start >= end) return AudioClip.empty(clip.sampleRate, channels)

        val keep = (clip.sampleRate.toLong() * keepMillis / 1000).toInt() * channels
        // Rounded down to a frame boundary: cutting mid-frame would swap the channels over for
        // the rest of the clip.
        val from = (max(0, start - keep) / channels) * channels
        val to = (min(clip.samples.size, end + keep) / channels) * channels
        return AudioClip(clip.samples.copyOfRange(from, to), clip.sampleRate, channels)
    }

    /** Scales the clip so its peak sits at [targetPeak], leaving quiet clips alone. */
    fun normalisePeak(clip: AudioClip, targetPeak: Float = 0.95f, maxGain: Float = 4f): AudioClip {
        val peak = clip.peak()
        if (peak <= 1e-6f) return clip
        val gain = min(targetPeak / peak, maxGain)
        if (gain in 0.99f..1.01f) return clip
        return AudioClip(
            FloatArray(clip.samples.size) { clip.samples[it] * gain },
            clip.sampleRate,
            clip.channels,
        )
    }

    fun applyGain(clip: AudioClip, gain: Float): AudioClip {
        if (gain == 1f) return clip
        return AudioClip(
            FloatArray(clip.samples.size) { clip.samples[it] * gain },
            clip.sampleRate,
            clip.channels,
        )
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
            return if (clip.sampleRate == targetRate) {
                clip
            } else {
                AudioClip(clip.samples, targetRate, clip.channels)
            }
        }
        // Stereo is resampled a channel at a time. Interpolating across interleaved samples
        // would mix left into right and collapse the stereo image.
        if (clip.channels > 1) {
            val ratio = targetRate.toDouble() / clip.sampleRate
            return AudioClip.interleave(
                clip.deinterleave()
                    .map { resampleMono(it, ratio, (it.size * ratio).toInt().coerceAtLeast(1)) }
                    .toTypedArray(),
                targetRate,
            )
        }
        val ratio = targetRate.toDouble() / clip.sampleRate
        val outputLength = (clip.samples.size * ratio).toInt().coerceAtLeast(1)
        return AudioClip(resampleMono(clip.samples, ratio, outputLength), targetRate, 1)
    }

    private fun resampleMono(source: FloatArray, ratio: Double, outputLength: Int): FloatArray {
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
        return out
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

        // Stereo is stretched a channel at a time. WSOLA chooses where to cut by looking for a
        // matching waveform, and run over interleaved samples it would cut the two channels at
        // different places — which is heard as the stereo image wandering about.
        if (clip.channels > 1) {
            return AudioClip.interleave(
                clip.deinterleave()
                    .map { stretchMono(it, clip.sampleRate, factor) }
                    .toTypedArray(),
                clip.sampleRate,
            )
        }
        return AudioClip(stretchMono(clip.samples, clip.sampleRate, factor), clip.sampleRate, 1)
    }

    private fun stretchMono(source: FloatArray, sampleRate: Int, factor: Float): FloatArray {
        val clip = AudioClip(source, sampleRate, 1)
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
        return result
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
        return AudioClip(resampled.samples, clip.sampleRate, clip.channels)
    }

    /** Crossfades [tail] into [head] over [millis], for seamless joins between clips. */
    fun crossfade(head: AudioClip, tail: AudioClip, millis: Int = 12): AudioClip {
        require(head.sampleRate == tail.sampleRate) { "Crossfade needs matching sample rates" }
        if (head.isEmpty) return tail
        if (tail.isEmpty) return head
        require(head.channels == tail.channels) { "Crossfade needs matching channel counts" }

        val channels = head.channels
        // Counted in frames and multiplied back up, so the fade always starts on a frame
        // boundary and the two channels fade together.
        val fadeFrames = min(
            (head.sampleRate.toLong() * millis / 1000).toInt(),
            min(head.frameCount, tail.frameCount),
        )
        val fade = fadeFrames * channels
        if (fade <= 0) return head.append(tail)

        val out = FloatArray(head.samples.size + tail.samples.size - fade)
        head.samples.copyInto(out, 0, 0, head.samples.size - fade)
        for (frame in 0 until fadeFrames) {
            val t = frame.toFloat() / fadeFrames
            val fadeOut = cos(t * PI / 2).toFloat()
            val fadeIn = sin(t * PI / 2).toFloat()
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                out[head.samples.size - fade + index] =
                    head.samples[head.samples.size - fade + index] * fadeOut +
                    tail.samples[index] * fadeIn
            }
        }
        tail.samples.copyInto(out, head.samples.size, fade, tail.samples.size)
        return AudioClip(out, head.sampleRate, channels)
    }

    fun dbToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()

    fun linearToDb(linear: Float): Float =
        if (linear <= 1e-9f) -180f else (20.0 * kotlin.math.log10(linear.toDouble())).toFloat()
}
