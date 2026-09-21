package app.soundbound.core.export

/**
 * Writes an ID3v2.3 tag.
 *
 * Hand-written rather than pulled in as a dependency: the tag Soundbound needs is half a dozen
 * text frames and a cover image, the format for those is simple and stable, and a tagging library
 * is a surprisingly large thing to ship on Android for the sake of it.
 *
 * ID3v2.3 rather than v2.4 on purpose. Every player understands 2.3; several — including some
 * car stereos and older hi-fi units, which is exactly where an exported audiobook ends up —
 * quietly ignore 2.4.
 */
object Id3 {

    /** The tags Soundbound writes. Anything null is left out rather than written empty. */
    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = "Audiobook",
        val trackNumber: Int? = null,
        val trackTotal: Int? = null,
        val year: String? = null,
        val comment: String? = null,
        /** Cover art, as encoded image bytes. */
        val artwork: ByteArray? = null,
        val artworkMimeType: String = "image/jpeg",
        /** Populates the length frame, which lets a player show the duration before scanning. */
        val durationMillis: Long? = null,
    )

    fun build(tags: Tags): ByteArray {
        val frames = ArrayList<ByteArray>()

        tags.title?.let { frames.add(textFrame("TIT2", it)) }
        tags.artist?.let { frames.add(textFrame("TPE1", it)) }
        tags.album?.let { frames.add(textFrame("TALB", it)) }
        tags.albumArtist?.let { frames.add(textFrame("TPE2", it)) }
        tags.genre?.let { frames.add(textFrame("TCON", it)) }
        tags.year?.let { frames.add(textFrame("TYER", it.take(4))) }
        tags.durationMillis?.let { frames.add(textFrame("TLEN", it.toString())) }
        tags.trackNumber?.let { number ->
            val value = if (tags.trackTotal != null) "$number/${tags.trackTotal}" else number.toString()
            frames.add(textFrame("TRCK", value))
        }
        tags.comment?.let { frames.add(commentFrame(it)) }
        tags.artwork?.takeIf { it.isNotEmpty() }?.let {
            frames.add(pictureFrame(it, tags.artworkMimeType))
        }

        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3   // major version
        header[4] = 0   // revision
        header[5] = 0   // flags
        writeSyncSafeSize(header, 6, body.size)
        return header + body
    }

    /**
     * A text frame in UTF-16 with a byte-order mark.
     *
     * Not Latin-1: book titles contain em dashes, accents and the occasional Greek letter, and
     * silently mangling an author's name is not acceptable.
     */
    private fun textFrame(id: String, value: String): ByteArray {
        val content = byteArrayOf(0x01) + utf16WithBom(value)
        return frame(id, content)
    }

    private fun commentFrame(text: String): ByteArray {
        // Encoding, language, short description (empty), then the text.
        val content = byteArrayOf(0x01) +
            "eng".toByteArray(Charsets.US_ASCII) +
            utf16WithBom("") +
            utf16WithBom(text)
        return frame("COMM", content)
    }

    private fun pictureFrame(image: ByteArray, mimeType: String): ByteArray {
        val content = byteArrayOf(0x00) +                                 // Latin-1 description
            mimeType.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) +
            byteArrayOf(0x03) +                                           // front cover
            byteArrayOf(0x00) +                                           // empty description
            image
        return frame("APIC", content)
    }

    private fun frame(id: String, content: ByteArray): ByteArray {
        val out = ByteArray(10 + content.size)
        id.forEachIndexed { index, ch -> out[index] = ch.code.toByte() }
        // v2.3 frame sizes are plain big-endian 32-bit, not sync-safe. Getting this wrong is the
        // classic ID3 bug: the tag parses, and then every frame after the first is garbage.
        out[4] = ((content.size shr 24) and 0xFF).toByte()
        out[5] = ((content.size shr 16) and 0xFF).toByte()
        out[6] = ((content.size shr 8) and 0xFF).toByte()
        out[7] = (content.size and 0xFF).toByte()
        out[8] = 0
        out[9] = 0
        content.copyInto(out, 10)
        return out
    }

    private fun utf16WithBom(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_16LE)
        return byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bytes + byteArrayOf(0, 0)
    }

    /** The tag header's own size is seven bits per byte, so it can never contain a false sync. */
    private fun writeSyncSafeSize(target: ByteArray, at: Int, size: Int) {
        target[at] = ((size shr 21) and 0x7F).toByte()
        target[at + 1] = ((size shr 14) and 0x7F).toByte()
        target[at + 2] = ((size shr 7) and 0x7F).toByte()
        target[at + 3] = (size and 0x7F).toByte()
    }
}
