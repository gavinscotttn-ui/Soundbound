package app.soundbound.core.export

import de.sciss.jump3r.mp3.MP3Data
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Round-trips real audio through the encoder and back out through jump3r's decoder.
 *
 * This is the test that matters most in the export path. The encoder is driven through LAME's
 * low-level API by hand, and the two ways to get that subtly wrong — a mis-wired module leaving
 * a null deep in the psychoacoustic model, or the wrong integer scaling producing silence or
 * savage clipping — both produce a file that looks plausible and sounds wrong. Only decoding it
 * back catches them.
 */
class Mp3EncoderTest {

    private val sampleRate = 22_050

    private fun sine(frequency: Double, millis: Int, amplitude: Float = 0.5f): FloatArray {
        val count = sampleRate * millis / 1000
        return FloatArray(count) { (amplitude * sin(2.0 * PI * frequency * it / sampleRate)).toFloat() }
    }

    private fun encode(samples: FloatArray, settings: Mp3Settings = Mp3Settings()): ByteArray {
        val out = ByteArrayOutputStream()
        Mp3Encoder(sampleRate, settings).use { encoder ->
            encoder.encode(samples) { buffer, length -> out.write(buffer, 0, length) }
            out.write(encoder.flush())
        }
        return out.toByteArray()
    }

    /** Decodes an MP3 back to mono floats using jump3r's own decoder, which is pure Java. */
    private fun decode(mp3: ByteArray): Pair<FloatArray, Int> {
        val mpg = MPGLib()
        val mpgInterface = Interface()
        val common = Common()
        val vbrTag = VBRTag()
        val bitStream = BitStream()
        val gainAnalysis = GainAnalysis()
        val version = Version()
        bitStream.setModules(gainAnalysis, mpg, version, vbrTag)
        mpg.setModules(mpgInterface, common)
        mpgInterface.setModules(vbrTag, common)

        val stream = mpg.hip_decode_init()
        val left = ShortArray(1 shl 16)
        val right = ShortArray(1 shl 16)
        val info = MP3Data()
        val decoded = ArrayList<Float>(mp3.size)
        var decodedRate = 0

        // MPGLib is fed incrementally and hands back one frame at a time, so after every chunk
        // we keep pulling with an empty input until it runs dry. Feeding a chunk and reading a
        // single frame — the obvious way to write this — silently drops most of the file, and
        // drops *all* of a VBR file whose first frame is the zero-filled Xing placeholder.
        val empty = ByteArray(0)
        var offset = 0
        var pending: ByteArray? = null

        while (true) {
            val input = pending ?: run {
                if (offset >= mp3.size) return@run null
                val chunk = minOf(1 shl 12, mp3.size - offset)
                val slice = mp3.copyOfRange(offset, offset + chunk)
                offset += chunk
                slice
            }
            if (input == null && offset >= mp3.size) {
                // Everything is in; drain whatever frames remain buffered.
                var produced = mpg.hip_decode1_headers(stream, empty, 0, left, right, info)
                while (produced > 0) {
                    if (info.header_parsed && decodedRate == 0) decodedRate = info.samplerate
                    for (i in 0 until produced) decoded.add(left[i] / 32768f)
                    produced = mpg.hip_decode1_headers(stream, empty, 0, left, right, info)
                }
                break
            }
            pending = null

            var produced = mpg.hip_decode1_headers(stream, input ?: empty, input?.size ?: 0, left, right, info)
            while (produced > 0) {
                if (info.header_parsed && decodedRate == 0) decodedRate = info.samplerate
                for (i in 0 until produced) decoded.add(left[i] / 32768f)
                produced = mpg.hip_decode1_headers(stream, empty, 0, left, right, info)
            }
        }
        mpg.hip_decode_exit(stream)
        return decoded.toFloatArray() to decodedRate
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        samples.forEach { sum += it.toDouble() * it }
        return sqrt(sum / samples.size).toFloat()
    }

    private fun dominantFrequency(samples: FloatArray, rate: Int): Double {
        // Zero crossings are plenty for a single sine and need no FFT.
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] < 0f) != (samples[i] < 0f)) crossings++
        }
        return crossings / 2.0 / (samples.size.toDouble() / rate)
    }

    @Test
    fun `the encoder produces a valid MP3 stream`() {
        val mp3 = encode(sine(440.0, 500))
        assertTrue(mp3.size > 1_000) { "Only ${mp3.size} bytes came out" }
        // Every MP3 frame starts with eleven set bits.
        val firstSync = (0 until mp3.size - 1).firstOrNull { index ->
            (mp3[index].toInt() and 0xFF) == 0xFF && (mp3[index + 1].toInt() and 0xE0) == 0xE0
        }
        assertTrue(firstSync != null) { "No frame sync word found in the output" }
    }

    @Test
    fun `a constant bit rate stream is about the size the bit rate implies`() {
        val seconds = 2
        val mp3 = encode(sine(300.0, seconds * 1000), Mp3Settings(bitrateKbps = 64))
        val expected = 64 * 1000 / 8 * seconds
        assertTrue(abs(mp3.size - expected) < expected * 0.2) {
            "Expected roughly $expected bytes at 64 kbit/s, got ${mp3.size}"
        }
    }

    @Test
    fun `audio survives the round trip at the right level and the right pitch`() {
        val original = sine(440.0, 1_000, amplitude = 0.5f)
        val (decoded, rate) = decode(encode(original))

        assertTrue(decoded.size > original.size / 2) {
            "Only ${decoded.size} of ${original.size} samples came back"
        }
        assertEquals(sampleRate, rate) { "The decoder reported $rate Hz" }

        // The level must survive. A wrong integer shift shows up here as silence or as clipping.
        val originalRms = rms(original)
        val decodedRms = rms(decoded)
        assertTrue(decodedRms > originalRms * 0.7 && decodedRms < originalRms * 1.4) {
            "Level went from $originalRms to $decodedRms — check the sample scaling"
        }

        // Skip the encoder's start-up padding before measuring pitch.
        val settled = decoded.copyOfRange(
            (decoded.size * 0.2).toInt(),
            (decoded.size * 0.8).toInt(),
        )
        val frequency = dominantFrequency(settled, rate)
        assertTrue(abs(frequency - 440.0) < 25.0) { "Pitch came back as $frequency Hz" }
    }

    @Test
    fun `silence encodes to silence rather than to noise`() {
        val (decoded, _) = decode(encode(FloatArray(sampleRate)))
        assertTrue(rms(decoded) < 0.01f) { "Silence came back at ${rms(decoded)}" }
    }

    @Test
    fun `a full-scale signal is not clipped into distortion`() {
        val original = sine(220.0, 500, amplitude = 0.98f)
        val (decoded, _) = decode(encode(original))
        val peak = decoded.maxOfOrNull { abs(it) } ?: 0f
        assertTrue(peak > 0.8f) { "Peak came back as $peak" }
        // A badly scaled encode wraps around and produces a peak pinned at exactly 1.0 with a
        // grossly inflated RMS; check the shape, not just the peak.
        assertTrue(rms(decoded) < 0.85f) { "RMS of ${rms(decoded)} suggests the signal was clipped flat" }
    }

    @Test
    fun `variable bit rate also produces a decodable stream`() {
        val mp3 = encode(sine(440.0, 700), Mp3Settings(variableBitRate = true, vbrQuality = 4))
        val (decoded, _) = decode(mp3)
        assertTrue(decoded.isNotEmpty()) { "Nothing decoded from ${mp3.size} VBR bytes; first bytes: " + mp3.take(8).joinToString(" ") { b -> "%02x".format(b) } }
        assertTrue(rms(decoded) > 0.1f)
    }

    @Test
    fun `flushing is what delivers the final frames`() {
        val samples = sine(440.0, 300)
        val withoutFlush = ByteArrayOutputStream()
        Mp3Encoder(sampleRate).use { encoder ->
            encoder.encode(samples) { buffer, length -> withoutFlush.write(buffer, 0, length) }
        }
        val withFlush = encode(samples)
        assertTrue(withFlush.size > withoutFlush.size()) {
            "Flushing added nothing: ${withoutFlush.size()} then ${withFlush.size}"
        }
    }

    @Test
    fun `an empty input produces an empty or near-empty file without failing`() {
        val mp3 = encode(FloatArray(0))
        assertTrue(mp3.size < 2_000) { "An empty input produced ${mp3.size} bytes" }
    }

    @Test
    fun `sample rates are mapped to the ones MP3 allows`() {
        assertTrue(Mp3Encoder.isSupportedRate(22_050))
        assertTrue(!Mp3Encoder.isSupportedRate(23_000))
        assertEquals(24_000, Mp3Encoder.nearestSupportedRate(23_000))
        assertEquals(22_050, Mp3Encoder.nearestSupportedRate(22_050))
        assertEquals(48_000, Mp3Encoder.nearestSupportedRate(96_000))
    }

    @Test
    fun `using a closed encoder fails loudly rather than writing nothing`() {
        val encoder = Mp3Encoder(sampleRate)
        encoder.close()
        val error = runCatching { encoder.encode(sine(440.0, 50)) { _, _ -> } }.exceptionOrNull()
        assertTrue(error is IllegalStateException) { "Got: $error" }
    }
}
