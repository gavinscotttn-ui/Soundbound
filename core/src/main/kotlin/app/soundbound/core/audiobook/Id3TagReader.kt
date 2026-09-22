package app.soundbound.core.audiobook

/**
 * Reads ID3v2 tags, and the MPEG stream behind them, out of an MP3.
 *
 * Soundbound already writes ID3v2.3 when it exports a book; this is the other direction, and it
 * has to cope with rather more. Files in the wild carry v2.2, v2.3 and v2.4 — which disagree
 * about frame identifiers, about how sizes are encoded, and about text encodings — plus the
 * occasional unsynchronisation scheme from the days when a decoder might mistake tag bytes for
 * audio.
 *
 * Chapters matter here. An audiobook distributed as one long MP3 carries its chapter list in
 * CHAP frames, and without them a nine-hour file has no structure at all.
 */
object Id3TagReader : AudioTagReader {

    override fun canRead(fileName: String, header: ByteArray): Boolean =
        AudioFormats.extensionOf(fileName) == "mp3" ||
            (header.size >= 3 && header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte())

    override fun read(bytes: ByteArray): AudioTags = runCatching { readOrThrow(bytes) }
        .getOrElse { AudioTags(durationMillis = runCatching { Mpeg.duration(bytes, 0) }.getOrNull()) }

    private fun readOrThrow(bytes: ByteArray): AudioTags {
        val tag = parseHeader(bytes) ?: return AudioTags(durationMillis = Mpeg.duration(bytes, 0))
        val frames = readFrames(bytes, tag)

        val chapters = frames.filter { it.id == "CHAP" }
            .mapNotNull { parseChapter(it.body, tag.major) }
            .sortedBy { it.startMillis }

        fun text(vararg ids: String): String? = ids.firstNotNullOfOrNull { id ->
            frames.firstOrNull { it.id == id }?.let { decodeText(it.body) }?.takeIf { it.isNotBlank() }
        }

        val declaredLength = text("TLEN", "TLE")?.trim()?.toLongOrNull()

        return AudioTags(
            title = text("TIT2", "TT2"),
            artist = text("TPE1", "TP1"),
            album = text("TALB", "TAL"),
            albumArtist = text("TPE2", "TP2"),
            // Some tools write the narrator as the "composer"; others use a TXXX frame.
            narrator = text("TCOM", "TCM") ?: userText(frames, "NARRATOR"),
            year = text("TDRC", "TYER", "TYE")?.take(4),
            comment = frames.firstOrNull { it.id == "COMM" || it.id == "COM" }
                ?.let { decodeComment(it.body) },
            trackNumber = text("TRCK", "TRK")?.substringBefore('/')?.trim()?.toIntOrNull(),
            discNumber = text("TPOS", "TPA")?.substringBefore('/')?.trim()?.toIntOrNull(),
            durationMillis = declaredLength ?: Mpeg.duration(bytes, tag.totalSize),
            chapters = chapters,
            coverBytes = frames.firstOrNull { it.id == "APIC" || it.id == "PIC" }
                ?.let { parsePicture(it.body, it.id == "PIC") },
        )
    }

    // ---------------------------------------------------------------- header and frames

    private data class TagHeader(val major: Int, val flags: Int, val bodySize: Int) {
        val totalSize: Int get() = bodySize + 10
        val unsynchronised: Boolean get() = flags and 0x80 != 0
        val hasExtendedHeader: Boolean get() = flags and 0x40 != 0
    }

    private data class Frame(val id: String, val body: ByteArray)

    private fun parseHeader(bytes: ByteArray): TagHeader? {
        if (bytes.size < 10) return null
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() ||
            bytes[2] != '3'.code.toByte()
        ) {
            return null
        }
        val major = bytes[3].toInt() and 0xFF
        if (major !in 2..4) return null
        return TagHeader(major, bytes[5].toInt() and 0xFF, syncSafe(bytes, 6))
    }

    private fun readFrames(bytes: ByteArray, tag: TagHeader): List<Frame> {
        // A whole-tag unsynchronisation scheme inserts a zero after every 0xFF. Undo it once,
        // for the tag body only, before any frame is looked at.
        val body = if (tag.unsynchronised) {
            desynchronise(bytes, 10, minOf(bytes.size, 10 + tag.bodySize))
        } else {
            bytes.copyOfRange(10, minOf(bytes.size, 10 + tag.bodySize))
        }

        var offset = 0
        if (tag.hasExtendedHeader && body.size >= 6) {
            offset += if (tag.major == 4) syncSafe(body, 0) else readInt(body, 0) + 4
        }
        return readFramesFrom(body, offset, tag.major)
    }

    private fun readFramesFrom(body: ByteArray, from: Int, major: Int): List<Frame> {
        val idLength = if (major == 2) 3 else 4
        val sizeLength = if (major == 2) 3 else 4
        val flagLength = if (major == 2) 0 else 2

        val frames = mutableListOf<Frame>()
        var offset = from
        while (offset + idLength + sizeLength + flagLength <= body.size) {
            val id = String(body, offset, idLength, Charsets.ISO_8859_1)
            // Padding: the rest of the tag is zeroes.
            if (id[0] == '\u0000') break
            if (!id.all { it.isLetterOrDigit() }) break

            val size = when {
                major == 2 -> readInt24(body, offset + idLength)
                major == 4 -> syncSafe(body, offset + idLength)
                else -> readInt(body, offset + idLength)
            }
            val start = offset + idLength + sizeLength + flagLength
            if (size <= 0 || start + size > body.size) break

            frames += Frame(id, body.copyOfRange(start, start + size))
            offset = start + size
        }
        return frames
    }

    // ---------------------------------------------------------------- field decoding

    /**
     * Decodes an ID3 text frame: one byte of encoding, then the text.
     *
     * v2.4 added UTF-8 and terminates multi-valued frames with a null; earlier versions used
     * ISO-8859-1 or UTF-16 with a byte-order mark. Anything past the first null is dropped,
     * because a multi-valued artist frame should show as one name, not "Name\u0000Name".
     */
    private fun decodeText(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val text = decode(body, 0, body.size)
        return text.substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun decode(body: ByteArray, from: Int, until: Int): String {
        if (from >= until) return ""
        val charset = when (body[from].toInt() and 0xFF) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(body, from + 1, until - from - 1, charset)
    }

    /** COMM is an encoding byte, a three-byte language, a short description, then the text. */
    private fun decodeComment(body: ByteArray): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xFF
        val wide = encoding == 1 || encoding == 2
        var index = 4
        // Skip the description, which is terminated by one or two nulls depending on encoding.
        while (index < body.size) {
            if (wide) {
                if (index + 1 < body.size && body[index] == 0.toByte() && body[index + 1] == 0.toByte()) {
                    index += 2
                    break
                }
                index += 2
            } else {
                if (body[index] == 0.toByte()) {
                    index += 1
                    break
                }
                index += 1
            }
        }
        if (index >= body.size) return null
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(body, index, body.size - index, charset)
            .substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    /** TXXX carries a description and a value, both encoded; used for anything non-standard. */
    private fun userText(frames: List<Frame>, description: String): String? {
        for (frame in frames) {
            if (frame.id != "TXXX" && frame.id != "TXX") continue
            val whole = decode(frame.body, 0, frame.body.size)
            val parts = whole.split('\u0000')
            if (parts.size >= 2 && parts[0].trim().equals(description, ignoreCase = true)) {
                return parts[1].trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * APIC: encoding, MIME type, picture type, description, then the image.
     *
     * v2.2's PIC replaces the MIME type with a three-character format code, which is the only
     * structural difference.
     */
    private fun parsePicture(body: ByteArray, isV2: Boolean): ByteArray? {
        if (body.size < 4) return null
        val encoding = body[0].toInt() and 0xFF
        var index = 1
        if (isV2) {
            index += 3
        } else {
            while (index < body.size && body[index] != 0.toByte()) index++
            index++
        }
        if (index >= body.size) return null
        index++ // picture type

        val wide = encoding == 1 || encoding == 2
        while (index < body.size) {
            if (wide) {
                if (index + 1 < body.size && body[index] == 0.toByte() && body[index + 1] == 0.toByte()) {
                    index += 2
                    break
                }
                index += 2
            } else {
                if (body[index] == 0.toByte()) {
                    index += 1
                    break
                }
                index += 1
            }
        }
        if (index >= body.size) return null
        return body.copyOfRange(index, body.size).takeIf { it.isNotEmpty() }
    }

    /**
     * CHAP: an identifier, four 32-bit times, then sub-frames — normally a TIT2 with the title.
     *
     * The two byte offsets after the times are almost always 0xFFFFFFFF, meaning "not set", and
     * are ignored: the times are what a listener moves about by.
     */
    private fun parseChapter(body: ByteArray, major: Int): EmbeddedChapter? {
        var index = 0
        while (index < body.size && body[index] != 0.toByte()) index++
        index++ // the element id's terminating null
        if (index + 16 > body.size) return null

        val start = readInt(body, index).toLong() and 0xFFFFFFFFL
        val end = readInt(body, index + 4).toLong() and 0xFFFFFFFFL
        index += 16

        val title = readFramesFrom(body, index, major)
            .firstOrNull { it.id == "TIT2" || it.id == "TT2" }
            ?.let { decodeText(it.body) }

        return EmbeddedChapter(
            title = title.orEmpty(),
            startMillis = start,
            endMillis = end.takeIf { it > start && it != 0xFFFFFFFFL },
        )
    }

    // ---------------------------------------------------------------- byte helpers

    /**
     * A sync-safe integer: seven bits per byte, so the value can never contain a run of eleven
     * set bits that a decoder would mistake for the start of an audio frame.
     */
    private fun syncSafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readInt24(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    /** Removes the zero byte inserted after every 0xFF by the unsynchronisation scheme. */
    private fun desynchronise(bytes: ByteArray, from: Int, until: Int): ByteArray {
        val out = ByteArray(until - from)
        var written = 0
        var index = from
        while (index < until) {
            val value = bytes[index]
            out[written++] = value
            index++
            if (value == 0xFF.toByte() && index < until && bytes[index] == 0.toByte()) index++
        }
        return out.copyOf(written)
    }
}
