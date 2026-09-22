package app.soundbound.core.audiobook

/**
 * Works out how long an MP3 is.
 *
 * MP3 has no header that states its duration, which is why a badly-tagged file so often shows
 * the wrong length. There are three ways to find out, in decreasing order of reliability:
 *
 *  1. A Xing, Info or VBRI header in the first audio frame, which records the frame count. This
 *     is what a variable-bitrate encoder writes precisely so that players can seek, and it is
 *     exact.
 *  2. Constant bitrate: the file's length divided by the rate from the first frame header. Exact
 *     for a genuinely constant-bitrate file.
 *  3. Counting every frame in the file. Always right, and slow, so it is not done here — the
 *     platform decoder reports the true duration when the file is opened, and any error in the
 *     estimate is corrected then.
 *
 * Getting this right matters more for an audiobook than for a song: the durations of forty files
 * are added up to make one timeline, and a two-second error in each of them puts the end of the
 * book a minute and a half out.
 */
object Mpeg {

    // Bitrates in kbit/s, indexed by the four-bit field in the frame header.
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val BITRATES_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
    private val BITRATES_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)

    private val SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000, 0)
    private val SAMPLE_RATES_V2 = intArrayOf(22050, 24000, 16000, 0)
    private val SAMPLE_RATES_V25 = intArrayOf(11025, 12000, 8000, 0)

    /** A parsed MPEG audio frame header. */
    data class FrameHeader(
        val offset: Int,
        val version: Int,
        val layer: Int,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val channels: Int,
        val frameLengthBytes: Int,
        val samplesPerFrame: Int,
    )

    /**
     * Duration in milliseconds, or null when no audio frame can be found.
     *
     * [from] is where the audio starts — past any ID3 tag, which would otherwise be mistaken for
     * audio or counted as part of the file's size.
     */
    fun duration(bytes: ByteArray, from: Int): Long? {
        val header = firstFrame(bytes, from) ?: return null

        variableBitrateFrames(bytes, header)?.let { frames ->
            return frames.toLong() * header.samplesPerFrame * 1000 / header.sampleRate
        }

        if (header.bitrateKbps <= 0) return null
        val audioBytes = (bytes.size - header.offset).toLong().coerceAtLeast(0)
        return audioBytes * 8 / header.bitrateKbps
    }

    /**
     * Finds the first real audio frame at or after [from].
     *
     * The sync word — eleven set bits — occurs often enough by chance inside cover art or a
     * comment that a single match cannot be trusted. A candidate is accepted only if a second
     * valid frame sits exactly where the first one's length says it should, which in practice
     * settles it.
     */
    fun firstFrame(bytes: ByteArray, from: Int): FrameHeader? {
        var index = from.coerceAtLeast(0)
        val limit = minOf(bytes.size - 4, from + SEARCH_LIMIT)
        while (index <= limit) {
            val candidate = parseFrame(bytes, index)
            if (candidate != null) {
                val next = candidate.offset + candidate.frameLengthBytes
                if (next + 4 > bytes.size || parseFrame(bytes, next) != null) return candidate
            }
            index++
        }
        return null
    }

    private fun parseFrame(bytes: ByteArray, offset: Int): FrameHeader? {
        if (offset + 4 > bytes.size) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF

        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null

        val versionBits = (b1 shr 3) and 0x03
        if (versionBits == 1) return null // reserved
        val layerBits = (b1 shr 1) and 0x03
        if (layerBits == 0) return null // reserved

        val version = when (versionBits) {
            0 -> 25 // MPEG 2.5
            2 -> 2
            else -> 1
        }
        val layer = 4 - layerBits

        val bitrateIndex = (b2 shr 4) and 0x0F
        if (bitrateIndex == 0 || bitrateIndex == 15) return null
        val sampleIndex = (b2 shr 2) and 0x03
        if (sampleIndex == 3) return null

        val bitrate = when {
            version == 1 && layer == 3 -> BITRATES_V1_L3[bitrateIndex]
            version == 1 && layer == 2 -> BITRATES_V1_L2[bitrateIndex]
            version == 1 -> BITRATES_V1_L1[bitrateIndex]
            else -> BITRATES_V2_L3[bitrateIndex]
        }
        if (bitrate == 0) return null

        val sampleRate = when (version) {
            1 -> SAMPLE_RATES_V1[sampleIndex]
            2 -> SAMPLE_RATES_V2[sampleIndex]
            else -> SAMPLE_RATES_V25[sampleIndex]
        }
        if (sampleRate == 0) return null

        val padding = (b2 shr 1) and 0x01
        val channels = if (((b3 shr 6) and 0x03) == 3) 1 else 2

        val samplesPerFrame = when {
            layer == 1 -> 384
            layer == 2 -> 1152
            version == 1 -> 1152
            else -> 576 // Layer III in MPEG 2 and 2.5 uses half-size granules.
        }

        val frameLength = if (layer == 1) {
            (12 * bitrate * 1000 / sampleRate + padding) * 4
        } else {
            samplesPerFrame / 8 * bitrate * 1000 / sampleRate + padding
        }
        if (frameLength <= 4) return null

        return FrameHeader(
            offset = offset,
            version = version,
            layer = layer,
            bitrateKbps = bitrate,
            sampleRate = sampleRate,
            channels = channels,
            frameLengthBytes = frameLength,
            samplesPerFrame = samplesPerFrame,
        )
    }

    /**
     * Reads the frame count out of a Xing, Info or VBRI header if the first frame carries one.
     *
     * Xing and Info sit after the frame's side information, at a position that depends on the
     * version and channel mode; VBRI, which Fraunhofer's encoder writes, is always 32 bytes in.
     */
    private fun variableBitrateFrames(bytes: ByteArray, header: FrameHeader): Int? {
        val sideInfo = when {
            header.version == 1 -> if (header.channels == 1) 17 else 32
            else -> if (header.channels == 1) 9 else 17
        }

        val xing = header.offset + 4 + sideInfo
        if (xing + 12 <= bytes.size) {
            val tag = String(bytes, xing, 4, Charsets.ISO_8859_1)
            if (tag == "Xing" || tag == "Info") {
                val flags = readInt(bytes, xing + 4)
                // Bit 0 of the flags says a frame count follows immediately.
                if (flags and 0x01 != 0) {
                    val frames = readInt(bytes, xing + 8)
                    if (frames > 0) return frames
                }
                return null
            }
        }

        val vbri = header.offset + 4 + 32
        if (vbri + 26 <= bytes.size && String(bytes, vbri, 4, Charsets.ISO_8859_1) == "VBRI") {
            val frames = readInt(bytes, vbri + 14)
            if (frames > 0) return frames
        }
        return null
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    /**
     * How far past the tag to look for the first frame before giving up.
     *
     * Generous — some files pad heavily between the tag and the audio — but bounded, so that a
     * file which is not an MP3 at all fails quickly rather than scanning hundreds of megabytes.
     */
    private const val SEARCH_LIMIT = 256 * 1024
}
