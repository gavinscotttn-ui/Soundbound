package app.soundbound.core.audio

import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8

/**
 * Minimal RIFF/WAVE reader and writer.
 *
 * Needed in two places: the platform speech services (Android's `synthesizeToFile`, macOS
 * `say -o`, Windows SAPI) all hand back a WAV file rather than raw samples, and exporting a
 * chapter as an audio file wants the same format going the other way.
 *
 * Handles 8, 16, 24 and 32-bit PCM plus 32-bit float, mono or multi-channel, which covers
 * everything those three platforms produce. Extra chunks are skipped rather than rejected —
 * Windows in particular likes to add a `LIST` chunk.
 */
object WavCodec {

    class WavFormatException(message: String) : Exception(message)

    fun decode(bytes: ByteArray): AudioClip {
        if (bytes.size < 44) throw WavFormatException("This WAV file is too short to contain any audio.")
        if (readAscii(bytes, 0, 4) != "RIFF" || readAscii(bytes, 8, 4) != "WAVE") {
            throw WavFormatException("This is not a WAV file.")
        }

        var offset = 12
        var channels = 1
        var sampleRate = 22_050
        var bitsPerSample = 16
        var isFloat = false
        var dataStart = -1
        var dataLength = 0

        while (offset + 8 <= bytes.size) {
            val chunkId = readAscii(bytes, offset, 4)
            val chunkSize = readLeInt(bytes, offset + 4)
            val body = offset + 8
            when (chunkId) {
                "fmt " -> {
                    if (body + 16 > bytes.size) throw WavFormatException("This WAV file's format chunk is truncated.")
                    val audioFormat = readLeShort(bytes, body)
                    channels = readLeShort(bytes, body + 2).coerceAtLeast(1)
                    sampleRate = readLeInt(bytes, body + 4)
                    bitsPerSample = readLeShort(bytes, body + 14)
                    isFloat = audioFormat == 3
                    if (audioFormat == 0xFFFE && body + 26 <= bytes.size) {
                        // WAVE_FORMAT_EXTENSIBLE: the real format sits in the sub-format GUID,
                        // whose first two bytes repeat the format tag.
                        isFloat = readLeShort(bytes, body + 24) == 3
                    }
                    if (audioFormat != 1 && audioFormat != 3 && audioFormat != 0xFFFE) {
                        throw WavFormatException("This WAV file is compressed, which Soundbound cannot read.")
                    }
                }

                "data" -> {
                    dataStart = body
                    dataLength = if (chunkSize <= 0 || body + chunkSize > bytes.size) {
                        bytes.size - body
                    } else {
                        chunkSize
                    }
                }
            }
            if (chunkId == "data" && dataStart >= 0) break
            // Chunks are word-aligned, so an odd size is followed by a pad byte.
            offset = body + chunkSize + (chunkSize and 1)
            if (chunkSize <= 0) break
        }

        if (dataStart < 0 || dataLength <= 0) throw WavFormatException("This WAV file contains no audio data.")
        if (sampleRate <= 0) throw WavFormatException("This WAV file declares an impossible sample rate.")

        val samples = readSamples(bytes, dataStart, dataLength, bitsPerSample, isFloat, channels)
        return AudioClip(samples, sampleRate)
    }

    /** Decodes to mono by averaging channels: every voice in Soundbound is mono downstream. */
    private fun readSamples(
        bytes: ByteArray,
        start: Int,
        length: Int,
        bitsPerSample: Int,
        isFloat: Boolean,
        channels: Int,
    ): FloatArray {
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) throw WavFormatException("This WAV file declares $bitsPerSample bits per sample.")
        val frameSize = bytesPerSample * channels
        val frames = length / frameSize
        val out = FloatArray(frames)

        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                val at = start + frame * frameSize + channel * bytesPerSample
                sum += when {
                    isFloat && bitsPerSample == 32 -> Float.fromBits(readLeInt(bytes, at))
                    bitsPerSample == 8 -> ((bytes[at].toInt() and 0xFF) - 128) / 128f
                    bitsPerSample == 16 -> readLeShortSigned(bytes, at) / 32768f
                    bitsPerSample == 24 -> readLe24Signed(bytes, at) / 8_388_608f
                    bitsPerSample == 32 -> readLeInt(bytes, at) / 2_147_483_648f
                    else -> throw WavFormatException("Soundbound cannot read $bitsPerSample-bit WAV audio.")
                }
            }
            out[frame] = sum / channels
        }
        return out
    }

    /** Writes a mono 16-bit WAV. */
    fun encode(clip: AudioClip): ByteArray {
        val pcm = clip.toPcm16()
        val out = ByteArray(44 + pcm.size)
        writeAscii(out, 0, "RIFF")
        writeLeInt(out, 4, 36 + pcm.size)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeLeInt(out, 16, 16)
        writeLeShort(out, 20, 1)                       // PCM
        writeLeShort(out, 22, 1)                       // mono
        writeLeInt(out, 24, clip.sampleRate)
        writeLeInt(out, 28, clip.sampleRate * 2)       // byte rate
        writeLeShort(out, 32, 2)                       // block align
        writeLeShort(out, 34, 16)                      // bits per sample
        writeAscii(out, 36, "data")
        writeLeInt(out, 40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }

    /**
     * Streams a WAV out without holding the whole thing in memory, for exporting a long
     * chapter. The header is written with the final length, so the total sample count must be
     * known in advance.
     */
    fun writeHeader(sink: BufferedSink, sampleRate: Int, totalSamples: Int) {
        val dataSize = totalSamples * 2
        sink.write("RIFF".encodeUtf8())
        sink.writeIntLe(36 + dataSize)
        sink.write("WAVE".encodeUtf8())
        sink.write("fmt ".encodeUtf8())
        sink.writeIntLe(16)
        sink.writeShortLe(1)
        sink.writeShortLe(1)
        sink.writeIntLe(sampleRate)
        sink.writeIntLe(sampleRate * 2)
        sink.writeShortLe(2)
        sink.writeShortLe(16)
        sink.write("data".encodeUtf8())
        sink.writeIntLe(dataSize)
    }

    fun writeSamples(sink: BufferedSink, clip: AudioClip) {
        sink.write(clip.toPcm16())
    }

    fun decode(source: BufferedSource): AudioClip = decode(source.readByteArray())

    // ---------------------------------------------------------------- byte helpers

    private fun readAscii(bytes: ByteArray, at: Int, length: Int): String =
        if (at + length > bytes.size) "" else String(bytes, at, length, Charsets.US_ASCII)

    private fun readLeInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun readLeShort(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readLeShortSigned(bytes: ByteArray, at: Int): Int =
        readLeShort(bytes, at).let { if (it > 32767) it - 65536 else it }

    private fun readLe24Signed(bytes: ByteArray, at: Int): Int {
        val value = (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16)
        return if (value > 8_388_607) value - 16_777_216 else value
    }

    private fun writeAscii(out: ByteArray, at: Int, value: String) {
        value.forEachIndexed { index, ch -> out[at + index] = ch.code.toByte() }
    }

    private fun writeLeInt(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
        out[at + 2] = ((value shr 16) and 0xFF).toByte()
        out[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeLeShort(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
