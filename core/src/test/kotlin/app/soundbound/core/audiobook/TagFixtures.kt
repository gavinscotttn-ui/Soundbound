package app.soundbound.core.audiobook

import java.io.ByteArrayOutputStream

/**
 * Builds real ID3 and MP4 bytes for the tag readers to parse.
 *
 * Written independently of the readers, from the format specifications, rather than by reusing
 * anything they contain. A fixture that shares code with the thing it tests only proves the two
 * agree with each other, which is exactly the mistake that lets a wrong byte order through.
 */
object TagFixtures {

    // ---------------------------------------------------------------- ID3

    /** A frame: four-character id, size, two flag bytes, body. */
    fun id3Frame(id: String, body: ByteArray, syncSafeSize: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (syncSafeSize) syncSafeBytes(body.size) else intBytes(body.size))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    /** A text frame body: one encoding byte then the text. 0 = ISO-8859-1, 3 = UTF-8. */
    fun textBody(text: String, encoding: Int = 3): ByteArray {
        val charset = if (encoding == 3) Charsets.UTF_8 else Charsets.ISO_8859_1
        return byteArrayOf(encoding.toByte()) + text.toByteArray(charset)
    }

    /** A UTF-16 text frame body, with the byte-order mark a real encoder writes. */
    fun utf16Body(text: String): ByteArray =
        byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    fun chapterBody(
        elementId: String,
        startMillis: Int,
        endMillis: Int,
        title: String,
        syncSafeSize: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(elementId.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(intBytes(startMillis))
        out.write(intBytes(endMillis))
        out.write(intBytes(-1)) // start byte offset: not set
        out.write(intBytes(-1)) // end byte offset: not set
        out.write(id3Frame("TIT2", textBody(title), syncSafeSize))
        return out.toByteArray()
    }

    fun apicBody(mime: String, image: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0) // ISO-8859-1
        out.write(mime.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(3) // front cover
        out.write("Cover".toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(image)
        return out.toByteArray()
    }

    /** Wraps frames in an ID3v2 tag of the given major version, then appends [audio]. */
    fun id3Tag(major: Int, frames: List<ByteArray>, audio: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major)
        out.write(0) // revision
        out.write(0) // flags
        out.write(syncSafeBytes(body.size)) // the tag size is always sync-safe, in every version
        out.write(body)
        out.write(audio)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- MPEG audio

    /**
     * One MPEG-1 Layer III frame header plus its payload, as silence.
     *
     * 128 kbit/s at 44,100 Hz, stereo: 1152 samples in 417 bytes, which is the commonest shape
     * an MP3 frame takes.
     */
    fun mp3Frame(padding: Boolean = false): ByteArray {
        val header = byteArrayOf(
            0xFF.toByte(),
            0xFB.toByte(), // MPEG-1, Layer III, no CRC
            (if (padding) 0x92 else 0x90).toByte(), // 128 kbit/s, 44.1 kHz, padding bit
            0x00, // stereo
        )
        val length = 1152 / 8 * 128000 / 44100 + if (padding) 1 else 0
        return header + ByteArray(length - 4)
    }

    /** [count] identical frames: 1152 samples each at 44,100 Hz is 26.12ms a frame. */
    fun mp3Audio(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(count) { out.write(mp3Frame()) }
        return out.toByteArray()
    }

    /**
     * An MP3 whose first frame carries a Xing header stating [frames] frames.
     *
     * The header sits after the frame's side information — 32 bytes for MPEG-1 stereo.
     */
    fun xingAudio(frames: Int): ByteArray {
        val frame = mp3Frame()
        val body = frame.copyOf()
        val xing = 4 + 32
        "Xing".toByteArray(Charsets.ISO_8859_1).copyInto(body, xing)
        intBytes(0x01).copyInto(body, xing + 4) // flags: frame count present
        intBytes(frames).copyInto(body, xing + 8)
        val out = ByteArrayOutputStream()
        out.write(body)
        // A following frame, so the sync word is confirmed rather than guessed.
        out.write(frame)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- MP4

    fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { payloads.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(intBytes(body.size + 8))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    /** A version-0 movie header: timescale and duration in that timescale. */
    fun mvhd(timescale: Int, durationUnits: Int): ByteArray = box(
        "mvhd",
        byteArrayOf(0, 0, 0, 0), // version 0, no flags
        intBytes(0), // created
        intBytes(0), // modified
        intBytes(timescale),
        intBytes(durationUnits),
        ByteArray(80), // the rest of the header, none of which is read
    )

    /** An iTunes-style tag: a named box containing a `data` box. */
    fun ilstItem(type: String, text: String): ByteArray = box(
        type,
        box(
            "data",
            intBytes(1), // type 1: UTF-8 text
            intBytes(0), // locale
            text.toByteArray(Charsets.UTF_8),
        ),
    )

    fun ilstNumber(type: String, number: Int): ByteArray = box(
        type,
        box(
            "data",
            intBytes(0), // type 0: binary
            intBytes(0),
            byteArrayOf(0, 0, (number shr 8).toByte(), number.toByte(), 0, 0),
        ),
    )

    fun ilstCover(image: ByteArray): ByteArray = box(
        "covr",
        box("data", intBytes(13), intBytes(0), image), // type 13: JPEG
    )

    /** Nero's chapter list. Start times are in 100-nanosecond units, not the movie timescale. */
    fun chpl(chapters: List<Pair<Long, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 0, 0)) // version 1
        out.write(0) // reserved byte that version 1 carries before the count
        out.write(intBytes(chapters.size))
        for ((startMillis, title) in chapters) {
            out.write(longBytes(startMillis * 10_000))
            val bytes = title.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        return box("chpl", out.toByteArray())
    }

    // ---------------------------------------------------------------- numbers

    fun intBytes(value: Int) = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    fun longBytes(value: Long) = ByteArray(8) { index -> (value shr ((7 - index) * 8)).toByte() }

    fun syncSafeBytes(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
