package app.soundbound.core.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parses the tag back out rather than checking bytes by eye.
 *
 * The classic ID3v2 bug is writing the frame size in the tag header's sync-safe form: the tag
 * parses, the first frame is right, and everything after it is garbage. Only walking the frames
 * catches it.
 */
class Id3Test {

    private data class Frame(val id: String, val body: ByteArray)

    private fun parse(tag: ByteArray): List<Frame> {
        assertEquals("ID3", String(tag, 0, 3, Charsets.US_ASCII))
        assertEquals(3, tag[3].toInt()) { "Expected ID3v2.3" }

        // The header size is sync-safe: seven bits per byte.
        val declared = ((tag[6].toInt() and 0x7F) shl 21) or
            ((tag[7].toInt() and 0x7F) shl 14) or
            ((tag[8].toInt() and 0x7F) shl 7) or
            (tag[9].toInt() and 0x7F)
        assertEquals(tag.size - 10, declared) { "The tag header declares the wrong size" }

        val frames = ArrayList<Frame>()
        var at = 10
        while (at + 10 <= tag.size) {
            val id = String(tag, at, 4, Charsets.US_ASCII)
            if (id.isBlank() || id[0] == '\u0000') break
            // v2.3 frame sizes are plain big-endian, unlike the header's.
            val size = ((tag[at + 4].toInt() and 0xFF) shl 24) or
                ((tag[at + 5].toInt() and 0xFF) shl 16) or
                ((tag[at + 6].toInt() and 0xFF) shl 8) or
                (tag[at + 7].toInt() and 0xFF)
            assertTrue(size >= 0 && at + 10 + size <= tag.size) {
                "Frame $id declares $size bytes, which runs past the end of the tag"
            }
            frames.add(Frame(id, tag.copyOfRange(at + 10, at + 10 + size)))
            at += 10 + size
        }
        return frames
    }

    /** Text frames are UTF-16 with a byte-order mark after the encoding byte. */
    private fun text(body: ByteArray): String {
        assertEquals(1, body[0].toInt()) { "Expected UTF-16 encoding" }
        return String(body, 3, body.size - 3, Charsets.UTF_16LE).trimEnd('\u0000')
    }

    @Test
    fun `every frame is walkable and carries what was put in it`() {
        val tag = Id3.build(
            Id3.Tags(
                title = "Chapter One",
                artist = "Jane Austen",
                album = "Persuasion",
                albumArtist = "Jane Austen",
                trackNumber = 1,
                trackTotal = 24,
                year = "1817",
                durationMillis = 725_000,
            ),
        )
        val frames = parse(tag).associate { it.id to it.body }

        assertEquals("Chapter One", text(frames["TIT2"]!!))
        assertEquals("Jane Austen", text(frames["TPE1"]!!))
        assertEquals("Persuasion", text(frames["TALB"]!!))
        assertEquals("1/24", text(frames["TRCK"]!!))
        assertEquals("1817", text(frames["TYER"]!!))
        assertEquals("725000", text(frames["TLEN"]!!))
        assertEquals("Audiobook", text(frames["TCON"]!!))
    }

    @Test
    fun `accented and typographic characters survive`() {
        // Latin-1 would mangle all of these, which is why the text frames are UTF-16.
        val title = "Les Misérables — Tome II… «Cosette»"
        val tag = Id3.build(Id3.Tags(title = title, artist = "Victor Hugo"))
        val frames = parse(tag).associate { it.id to it.body }
        assertEquals(title, text(frames["TIT2"]!!))
    }

    @Test
    fun `absent values are left out rather than written empty`() {
        val tag = Id3.build(Id3.Tags(title = "Only a title"))
        val ids = parse(tag).map { it.id }
        assertTrue("TIT2" in ids)
        assertFalse("TPE1" in ids) { "An absent artist must not produce an empty frame" }
        assertFalse("TALB" in ids)
        assertFalse("APIC" in ids)
    }

    @Test
    fun `artwork is embedded with its type and a front-cover marker`() {
        val artwork = ByteArray(4_096) { (it % 251).toByte() }
        val tag = Id3.build(Id3.Tags(title = "x", artwork = artwork, artworkMimeType = "image/png"))
        val picture = parse(tag).first { it.id == "APIC" }.body

        assertEquals(0, picture[0].toInt()) { "Latin-1 description encoding" }
        val mime = String(picture, 1, "image/png".length, Charsets.US_ASCII)
        assertEquals("image/png", mime)
        val pictureType = picture[1 + "image/png".length + 1].toInt()
        assertEquals(3, pictureType) { "3 is the front cover" }

        val imageStart = 1 + "image/png".length + 1 + 1 + 1
        assertEquals(artwork.size, picture.size - imageStart)
        assertEquals(artwork.toList(), picture.copyOfRange(imageStart, picture.size).toList())
    }

    @Test
    fun `a comment frame carries its language and text`() {
        val tag = Id3.build(Id3.Tags(title = "x", comment = "Read by Alba"))
        val comment = parse(tag).first { it.id == "COMM" }.body
        assertEquals(1, comment[0].toInt())
        assertEquals("eng", String(comment, 1, 3, Charsets.US_ASCII))
        assertTrue(String(comment, Charsets.UTF_16LE).contains("Read by Alba"))
    }

    @Test
    fun `a track number without a total is written alone`() {
        val tag = Id3.build(Id3.Tags(title = "x", trackNumber = 7))
        val frames = parse(tag).associate { it.id to it.body }
        assertEquals("7", text(frames["TRCK"]!!))
    }

    @Test
    fun `a large tag still declares a correct sync-safe size`() {
        // Over 16 KB, so the size spills past the lowest two sync-safe bytes.
        val tag = Id3.build(Id3.Tags(title = "x", artwork = ByteArray(40_000) { 1 }))
        val frames = parse(tag)
        assertTrue(frames.any { it.id == "APIC" })
        assertTrue(tag.size > 40_000)
    }
}
