package app.soundbound.core.audiobook

import app.soundbound.core.audiobook.TagFixtures.apicBody
import app.soundbound.core.audiobook.TagFixtures.chapterBody
import app.soundbound.core.audiobook.TagFixtures.id3Frame
import app.soundbound.core.audiobook.TagFixtures.id3Tag
import app.soundbound.core.audiobook.TagFixtures.mp3Audio
import app.soundbound.core.audiobook.TagFixtures.textBody
import app.soundbound.core.audiobook.TagFixtures.utf16Body
import app.soundbound.core.audiobook.TagFixtures.xingAudio
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ID3 tag reader")
class Id3TagReaderTest {

    @Test
    fun `a plainly tagged file gives up its title and author`() {
        val file = id3Tag(
            major = 3,
            frames = listOf(
                id3Frame("TIT2", textBody("Chapter One")),
                id3Frame("TPE1", textBody("Charles Dickens")),
                id3Frame("TALB", textBody("Bleak House")),
                id3Frame("TRCK", textBody("3/24")),
            ),
            audio = mp3Audio(40),
        )

        val tags = Id3TagReader.read(file)

        assertEquals("Chapter One", tags.title)
        assertEquals("Charles Dickens", tags.artist)
        assertEquals("Bleak House", tags.album)
        assertEquals(3, tags.trackNumber)
    }

    @Test
    fun `v2_4 sizes are sync-safe and v2_3 sizes are not`() {
        // The same frame, written both ways. Reading either with the other's rule gives a size
        // that is wrong by a factor of about 128, which is the classic ID3 bug.
        val long = "x".repeat(200)
        val v3 = id3Tag(3, listOf(id3Frame("TIT2", textBody(long), syncSafeSize = false)))
        val v4 = id3Tag(4, listOf(id3Frame("TIT2", textBody(long), syncSafeSize = true)))

        assertEquals(long, Id3TagReader.read(v3).title)
        assertEquals(long, Id3TagReader.read(v4).title)
    }

    @Test
    fun `UTF-16 text with a byte-order mark is decoded`() {
        val file = id3Tag(3, listOf(id3Frame("TIT2", utf16Body("Chapitre Un — Café"))))
        assertEquals("Chapitre Un — Café", Id3TagReader.read(file).title)
    }

    @Test
    fun `a multi-valued frame shows as one value, not a name with a null in it`() {
        val body = textBody("First Author\u0000Second Author")
        val file = id3Tag(4, listOf(id3Frame("TPE1", body, syncSafeSize = true)))
        assertEquals("First Author", Id3TagReader.read(file).artist)
    }

    @Test
    fun `v2_2's three-character frame identifiers are understood`() {
        // v2.2 frames are three characters with a three-byte size and no flags, so the whole
        // frame layout differs; a reader that only knows v2.3 finds nothing at all here.
        val body = textBody("Old Tag Title")
        val frame = "TT2".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0, body.size.toByte()) + body
        val file = id3Tag(2, listOf(frame))

        assertEquals("Old Tag Title", Id3TagReader.read(file).title)
    }

    @Test
    fun `chapters come out in order, with their titles and times`() {
        val file = id3Tag(
            major = 3,
            frames = listOf(
                id3Frame("TIT2", textBody("The Whole Book")),
                id3Frame("CHAP", chapterBody("ch2", 60_000, 120_000, "Second")),
                id3Frame("CHAP", chapterBody("ch1", 0, 60_000, "First")),
            ),
            audio = mp3Audio(10),
        )

        val chapters = Id3TagReader.read(file).chapters

        assertEquals(2, chapters.size)
        assertEquals("First", chapters[0].title)
        assertEquals(0L, chapters[0].startMillis)
        assertEquals(60_000L, chapters[0].endMillis)
        assertEquals("Second", chapters[1].title)
        assertEquals(60_000L, chapters[1].startMillis)
    }

    @Test
    fun `an end time of not-set is reported as absent rather than as a huge number`() {
        val body = "ch1".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            TagFixtures.intBytes(0) + TagFixtures.intBytes(-1) +
            TagFixtures.intBytes(-1) + TagFixtures.intBytes(-1) +
            id3Frame("TIT2", textBody("Only Chapter"))
        val file = id3Tag(3, listOf(id3Frame("CHAP", body)))

        val chapter = Id3TagReader.read(file).chapters.single()
        assertEquals(0L, chapter.startMillis)
        assertNull(chapter.endMillis)
    }

    @Test
    fun `embedded artwork is returned whole`() {
        val image = ByteArray(64) { it.toByte() }
        val file = id3Tag(3, listOf(id3Frame("APIC", apicBody("image/jpeg", image))))

        assertArrayEquals(image, Id3TagReader.read(file).coverBytes)
    }

    @Test
    fun `a constant-bitrate file's length is worked out from its size`() {
        // 40 frames of 1152 samples at 44.1 kHz is 1.045 seconds.
        val file = id3Tag(3, listOf(id3Frame("TIT2", textBody("x"))), audio = mp3Audio(40))
        val duration = requireNotNull(Id3TagReader.read(file).durationMillis)
        assertTrue(duration in 1000..1100, "Expected about 1045ms, got $duration")
    }

    @Test
    fun `a Xing header is believed over the file's size`() {
        // The header claims 10,000 frames — about four and a half minutes — while the file holds
        // two. A variable-bitrate file is exactly this shape, and measuring it by size would
        // report a fraction of a second.
        val file = id3Tag(3, listOf(id3Frame("TIT2", textBody("x"))), audio = xingAudio(10_000))
        val duration = requireNotNull(Id3TagReader.read(file).durationMillis)
        assertTrue(duration in 260_000..262_000, "Expected about 261s, got $duration")
    }

    @Test
    fun `TLEN is believed when the file states it`() {
        val file = id3Tag(
            3,
            listOf(id3Frame("TIT2", textBody("x")), id3Frame("TLEN", textBody("754000"))),
            audio = mp3Audio(5),
        )
        assertEquals(754_000L, Id3TagReader.read(file).durationMillis)
    }

    @Test
    fun `a file with no tag at all still reports its length`() {
        val duration = requireNotNull(Id3TagReader.read(mp3Audio(40)).durationMillis)
        assertTrue(duration in 1000..1100, "Expected about 1045ms, got $duration")
    }

    @Test
    fun `rubbish is read as empty rather than throwing`() {
        val tags = Id3TagReader.read(ByteArray(500) { 0x5A })
        assertNull(tags.title)
        assertTrue(tags.chapters.isEmpty())
    }

    @Test
    fun `a truncated file does not throw`() {
        val whole = id3Tag(3, listOf(id3Frame("TIT2", textBody("Half a title"))), mp3Audio(10))
        // Cut through the middle of the frame body.
        val half = whole.copyOfRange(0, 16)
        Id3TagReader.read(half)
    }

    @Test
    fun `it recognises an MP3 by name and by its tag`() {
        assertTrue(Id3TagReader.canRead("part-01.mp3", ByteArray(0)))
        assertTrue(Id3TagReader.canRead("no-extension", "ID3".toByteArray()))
    }
}
