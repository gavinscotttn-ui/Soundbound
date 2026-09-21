package app.soundbound.core.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WavCodecTest {

    /** Builds a WAV by hand so the decoder is tested against bytes, not against our encoder. */
    private fun wav(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        format: Int,
        data: ByteArray,
        extraChunk: Boolean = false,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun le32(value: Int) {
            out.write(value and 0xFF); out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF); out.write((value shr 24) and 0xFF)
        }
        fun le16(value: Int) { out.write(value and 0xFF); out.write((value shr 8) and 0xFF) }

        val extra = if (extraChunk) 4 + 4 + 8 else 0
        ascii("RIFF"); le32(36 + extra + data.size); ascii("WAVE")
        ascii("fmt "); le32(16)
        le16(format); le16(channels); le32(sampleRate)
        le32(sampleRate * channels * bitsPerSample / 8)
        le16(channels * bitsPerSample / 8); le16(bitsPerSample)
        if (extraChunk) {
            // Windows SAPI likes to slip a LIST chunk in before the data.
            ascii("LIST"); le32(8); out.write(ByteArray(8))
        }
        ascii("data"); le32(data.size); out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `16 bit mono decodes`() {
        val data = byteArrayOf(0, 0, 0x00, 0x40, 0x00, 0xC0.toByte())  // 0, +0.5, -0.5
        val clip = WavCodec.decode(wav(22_050, 1, 16, 1, data))
        assertEquals(22_050, clip.sampleRate)
        assertEquals(3, clip.samples.size)
        assertEquals(0f, clip.samples[0], 1e-4f)
        assertEquals(0.5f, clip.samples[1], 1e-3f)
        assertEquals(-0.5f, clip.samples[2], 1e-3f)
    }

    @Test
    fun `stereo is folded down to mono`() {
        // Left +0.5, right -0.5 in one frame: the average is silence.
        val data = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
        val clip = WavCodec.decode(wav(16_000, 2, 16, 1, data))
        assertEquals(1, clip.samples.size)
        assertTrue(abs(clip.samples[0]) < 1e-3f)
    }

    @Test
    fun `8 bit unsigned decodes`() {
        val data = byteArrayOf(-128, 0, -1)   // 128 -> 0, 0 -> -1, 255 -> ~+1
        val clip = WavCodec.decode(wav(8_000, 1, 8, 1, data))
        assertEquals(0f, clip.samples[0], 1e-3f)
        assertEquals(-1f, clip.samples[1], 1e-3f)
        assertTrue(clip.samples[2] > 0.9f)
    }

    @Test
    fun `32 bit float decodes`() {
        val out = java.io.ByteArrayOutputStream()
        listOf(0f, 0.25f, -0.75f).forEach { value ->
            val bits = value.toRawBits()
            out.write(bits and 0xFF); out.write((bits shr 8) and 0xFF)
            out.write((bits shr 16) and 0xFF); out.write((bits shr 24) and 0xFF)
        }
        val clip = WavCodec.decode(wav(24_000, 1, 32, 3, out.toByteArray()))
        assertEquals(0f, clip.samples[0], 1e-6f)
        assertEquals(0.25f, clip.samples[1], 1e-6f)
        assertEquals(-0.75f, clip.samples[2], 1e-6f)
    }

    @Test
    fun `unexpected chunks before the data are skipped`() {
        val data = byteArrayOf(0x00, 0x40)
        val clip = WavCodec.decode(wav(22_050, 1, 16, 1, data, extraChunk = true))
        assertEquals(1, clip.samples.size)
        assertEquals(0.5f, clip.samples[0], 1e-3f)
    }

    @Test
    fun `nonsense input is rejected with a readable message`() {
        val error = assertThrows(WavCodec.WavFormatException::class.java) {
            WavCodec.decode(ByteArray(64) { 0x41 })
        }
        assertTrue(error.message!!.contains("not a WAV"))
    }

    @Test
    fun `compressed audio is refused rather than decoded as noise`() {
        val error = assertThrows(WavCodec.WavFormatException::class.java) {
            WavCodec.decode(wav(22_050, 1, 16, 0x11, byteArrayOf(1, 2, 3, 4)))
        }
        assertTrue(error.message!!.contains("compressed"))
    }
}
