package app.soundbound.core.audiobook

import app.soundbound.core.audiobook.TagFixtures.box
import app.soundbound.core.audiobook.TagFixtures.chpl
import app.soundbound.core.audiobook.TagFixtures.ilstCover
import app.soundbound.core.audiobook.TagFixtures.ilstItem
import app.soundbound.core.audiobook.TagFixtures.ilstNumber
import app.soundbound.core.audiobook.TagFixtures.mvhd
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MP4 tag reader")
class Mp4TagReaderTest {

    /** An .m4b as one is actually laid out: a file type box, then the movie box. */
    private fun m4b(vararg moovChildren: ByteArray): ByteArray =
        box("ftyp", "M4B ".toByteArray(Charsets.ISO_8859_1), ByteArray(8)) +
            box("moov", *moovChildren) +
            box("mdat", ByteArray(64))

    private fun ilst(vararg items: ByteArray): ByteArray = box(
        "udta",
        box(
            "meta",
            byteArrayOf(0, 0, 0, 0), // meta is a full box: version and flags first
            box("hdlr", ByteArray(24)),
            box("ilst", *items),
        ),
    )

    @Test
    fun `an audiobook's title, author and narrator are read`() {
        val file = m4b(
            mvhd(timescale = 1000, durationUnits = 3_600_000),
            ilst(
                ilstItem("©nam", "Bleak House"),
                ilstItem("©alb", "Bleak House"),
                ilstItem("aART", "Charles Dickens"),
                ilstItem("©ART", "Sean Barrett"),
                ilstItem("©wrt", "Sean Barrett"),
                ilstItem("©day", "2009-04-01"),
            ),
        )

        val tags = Mp4TagReader.read(file)

        assertEquals("Bleak House", tags.title)
        assertEquals("Charles Dickens", tags.albumArtist)
        assertEquals("Sean Barrett", tags.artist)
        assertEquals("Sean Barrett", tags.narrator)
        assertEquals("2009", tags.year)
    }

    @Test
    fun `the duration comes from the movie header, in its own timescale`() {
        // 44,100 units per second, 88,200,000 units: exactly 2,000 seconds. Reading the timescale
        // as milliseconds instead would give a book 44 times too long.
        val file = m4b(mvhd(timescale = 44_100, durationUnits = 88_200_000))
        assertEquals(2_000_000L, Mp4TagReader.read(file).durationMillis)
    }

    @Test
    fun `Nero chapters are read, and their odd time unit is converted`() {
        // chpl stores 100-nanosecond units, not the movie timescale. Treating them as
        // milliseconds would put every chapter ten thousand times too far into the book.
        val file = m4b(
            mvhd(1000, 7_200_000),
            box(
                "udta",
                chpl(
                    listOf(
                        0L to "Opening Credits",
                        62_000L to "Chapter 1",
                        1_830_500L to "Chapter 2",
                    ),
                ),
            ),
        )

        val chapters = Mp4TagReader.read(file).chapters

        assertEquals(3, chapters.size)
        assertEquals("Opening Credits", chapters[0].title)
        assertEquals(0L, chapters[0].startMillis)
        assertEquals(62_000L, chapters[1].startMillis)
        assertEquals(1_830_500L, chapters[2].startMillis)
        // Each chapter runs to the next, and the last to the end of the book.
        assertEquals(62_000L, chapters[0].endMillis)
        assertEquals(7_200_000L, chapters[2].endMillis)
    }

    @Test
    fun `chapters out of order in the file come back in order`() {
        val file = m4b(
            mvhd(1000, 600_000),
            box("udta", chpl(listOf(300_000L to "Later", 0L to "Earlier"))),
        )

        val chapters = Mp4TagReader.read(file).chapters
        assertEquals(listOf("Earlier", "Later"), chapters.map { it.title })
    }

    @Test
    fun `track and disc numbers are read out of their little record`() {
        val file = m4b(
            mvhd(1000, 1000),
            ilst(ilstNumber("trkn", 7), ilstNumber("disk", 2)),
        )

        val tags = Mp4TagReader.read(file)
        assertEquals(7, tags.trackNumber)
        assertEquals(2, tags.discNumber)
    }

    @Test
    fun `cover art is returned whole`() {
        val image = ByteArray(128) { (it * 3).toByte() }
        val file = m4b(mvhd(1000, 1000), ilst(ilstCover(image)))
        assertArrayEquals(image, Mp4TagReader.read(file).coverBytes)
    }

    @Test
    fun `a meta box written without its version and flags is still read`() {
        // Some encoders omit the four bytes the specification requires, and a reader that
        // insists on them finds no tags at all in those files.
        val file = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1), ByteArray(8)) +
            box(
                "moov",
                mvhd(1000, 1000),
                box("udta", box("meta", box("ilst", ilstItem("©nam", "No Version Bytes")))),
            )

        assertEquals("No Version Bytes", Mp4TagReader.read(file).title)
    }

    @Test
    fun `a file with no metadata at all reads as empty rather than throwing`() {
        val tags = Mp4TagReader.read(m4b(mvhd(1000, 5_000)))
        assertNull(tags.title)
        assertTrue(tags.chapters.isEmpty())
        assertEquals(5_000L, tags.durationMillis)
    }

    @Test
    fun `rubbish does not throw`() {
        val tags = Mp4TagReader.read(ByteArray(300) { 0x7F })
        assertNull(tags.title)
        assertNull(tags.durationMillis)
    }

    @Test
    fun `a truncated file does not throw`() {
        val whole = m4b(mvhd(1000, 1000), ilst(ilstItem("©nam", "Cut Short")))
        Mp4TagReader.read(whole.copyOfRange(0, whole.size / 2))
    }

    @Test
    fun `a box claiming an impossible size does not run away`() {
        // A size field larger than the file is the commonest corruption, and a reader that
        // trusts it either loops or reads past the end of the array.
        val file = box("ftyp", ByteArray(4)) +
            TagFixtures.intBytes(Int.MAX_VALUE) + "moov".toByteArray(Charsets.ISO_8859_1)
        Mp4TagReader.read(file)
    }

    @Test
    fun `it recognises an MP4 container by name and by its file type box`() {
        assertTrue(Mp4TagReader.canRead("book.m4b", ByteArray(0)))
        assertTrue(Mp4TagReader.canRead("book.m4a", ByteArray(0)))
        assertTrue(
            Mp4TagReader.canRead(
                "unnamed",
                TagFixtures.intBytes(24) + "ftyp".toByteArray(Charsets.ISO_8859_1),
            ),
        )
    }
}
