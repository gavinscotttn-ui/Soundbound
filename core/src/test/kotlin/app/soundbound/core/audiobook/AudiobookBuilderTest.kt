package app.soundbound.core.audiobook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Assembling an audiobook")
class AudiobookBuilderTest {

    private fun file(
        name: String,
        durationMillis: Long = 60_000,
        track: Int? = null,
        disc: Int? = null,
        title: String? = null,
        album: String? = null,
        artist: String? = null,
        albumArtist: String? = null,
        chapters: List<EmbeddedChapter> = emptyList(),
    ) = AudioFileEntry(
        uri = "/books/$name",
        fileName = name,
        tags = AudioTags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = track,
            discNumber = disc,
            durationMillis = durationMillis,
            chapters = chapters,
        ),
    )

    // ---------------------------------------------------------------- ordering

    @Test
    fun `files are ordered by track number when they have one`() {
        val book = AudiobookBuilder.build(
            listOf(
                file("zzz.mp3", track = 1),
                file("aaa.mp3", track = 3),
                file("mmm.mp3", track = 2),
            ),
        )!!

        // Track 1 is zzz, track 2 is mmm, track 3 is aaa: the numbers win over the names.
        assertEquals(
            listOf("/books/zzz.mp3", "/books/mmm.mp3", "/books/aaa.mp3"),
            book.tracks.map { it.uri },
        )
    }

    @Test
    fun `file names are ordered the way a person reads them, not the way a computer sorts them`() {
        // Plain text ordering puts 10 before 2, which silently shuffles the middle of a book.
        val names = listOf("part2.mp3", "part10.mp3", "part1.mp3", "part20.mp3", "part3.mp3")
        val book = AudiobookBuilder.build(names.map { file(it) })!!

        assertEquals(
            listOf("part1.mp3", "part2.mp3", "part3.mp3", "part10.mp3", "part20.mp3"),
            book.tracks.map { it.uri.removePrefix("/books/") },
        )
    }

    @Test
    fun `discs are ordered before tracks`() {
        val book = AudiobookBuilder.build(
            listOf(
                file("d2t1.mp3", disc = 2, track = 1),
                file("d1t2.mp3", disc = 1, track = 2),
                file("d1t1.mp3", disc = 1, track = 1),
            ),
        )!!

        assertEquals(listOf("d1t1.mp3", "d1t2.mp3", "d2t1.mp3"), book.tracks.map { it.uri.removePrefix("/books/") })
    }

    @Test
    fun `an untagged extra file sorts after the numbered ones rather than into the middle`() {
        val book = AudiobookBuilder.build(
            listOf(
                file("intro.mp3"),
                file("ch1.mp3", track = 1),
                file("ch2.mp3", track = 2),
            ),
        )!!

        assertEquals("intro.mp3", book.tracks.last().uri.removePrefix("/books/"))
    }

    @Test
    fun `leading zeroes do not change the order`() {
        val book = AudiobookBuilder.build(
            listOf(file("Track 9.mp3"), file("Track 010.mp3"), file("Track 08.mp3")),
        )!!

        assertEquals(
            listOf("Track 08.mp3", "Track 9.mp3", "Track 010.mp3"),
            book.tracks.map { it.uri.removePrefix("/books/") },
        )
    }

    // ---------------------------------------------------------------- the timeline

    @Test
    fun `tracks are laid end to end on one timeline`() {
        val book = AudiobookBuilder.build(
            listOf(
                file("a.mp3", durationMillis = 90_000, track = 1),
                file("b.mp3", durationMillis = 120_000, track = 2),
                file("c.mp3", durationMillis = 30_000, track = 3),
            ),
        )!!

        assertEquals(listOf(0L, 90_000L, 210_000L), book.tracks.map { it.startMillis })
        assertEquals(240_000L, book.totalDurationMillis)
    }

    @Test
    fun `a point in the book resolves to the right file and offset`() {
        val book = AudiobookBuilder.build(
            listOf(
                file("a.mp3", durationMillis = 90_000, track = 1),
                file("b.mp3", durationMillis = 120_000, track = 2),
            ),
        )!!

        assertEquals("/books/a.mp3", book.trackAt(0)?.uri)
        assertEquals("/books/a.mp3", book.trackAt(89_999)?.uri)
        assertEquals("/books/b.mp3", book.trackAt(90_000)?.uri)
        assertEquals(10_000L, book.offsetWithinTrack(100_000))
        assertNull(book.trackAt(210_000))
    }

    @Test
    fun `a four-hundred file book still resolves quickly and correctly`() {
        val files = (1..400).map { file("part%03d.mp3".format(it), durationMillis = 60_000, track = it) }
        val book = AudiobookBuilder.build(files)!!

        assertEquals(400 * 60_000L, book.totalDurationMillis)
        assertEquals("/books/part200.mp3", book.trackAt(199 * 60_000L + 30_000)?.uri)
        assertEquals(30_000L, book.offsetWithinTrack(199 * 60_000L + 30_000))
    }

    // ---------------------------------------------------------------- chapters

    @Test
    fun `a folder of files gives one chapter per file`() {
        val book = AudiobookBuilder.build(
            listOf(
                file("a.mp3", durationMillis = 60_000, track = 1, title = "The Beginning"),
                file("b.mp3", durationMillis = 60_000, track = 2, title = "The Middle"),
            ),
        )!!

        assertEquals(listOf("The Beginning", "The Middle"), book.chapters.map { it.title })
        assertEquals(listOf(0L, 60_000L), book.chapters.map { it.startMillis })
    }

    @Test
    fun `one file's own chapter list is used, shifted into book time`() {
        val book = AudiobookBuilder.build(
            listOf(
                file("intro.mp3", durationMillis = 30_000, track = 1, title = "Introduction"),
                file(
                    "book.m4b",
                    durationMillis = 600_000,
                    track = 2,
                    chapters = listOf(
                        EmbeddedChapter("One", 0, 200_000),
                        EmbeddedChapter("Two", 200_000, 600_000),
                    ),
                ),
            ),
        )!!

        // The intro is its own chapter; the M4B's two are offset past it.
        assertEquals(listOf("Introduction", "One", "Two"), book.chapters.map { it.title })
        assertEquals(listOf(0L, 30_000L, 230_000L), book.chapters.map { it.startMillis })
    }

    @Test
    fun `a chapter starting past the end of its file is discarded`() {
        // Files are sometimes split after being tagged, leaving chapters pointing into thin air.
        val book = AudiobookBuilder.build(
            listOf(
                file(
                    "a.mp3",
                    durationMillis = 100_000,
                    chapters = listOf(
                        EmbeddedChapter("Real", 0, 100_000),
                        EmbeddedChapter("Beyond the end", 500_000, 600_000),
                    ),
                ),
            ),
        )!!

        assertEquals(listOf("Real"), book.chapters.map { it.title })
    }

    @Test
    fun `previous restarts the chapter partway in, and steps back at the start`() {
        val book = AudiobookBuilder.build(
            listOf(
                file("a.mp3", durationMillis = 60_000, track = 1),
                file("b.mp3", durationMillis = 60_000, track = 2),
                file("c.mp3", durationMillis = 60_000, track = 3),
            ),
        )!!

        // Ten seconds into the second chapter: back to its own start.
        assertEquals(60_000L, book.chapterBoundary(70_000, -1))
        // One second in, which is within the threshold: back to the previous chapter.
        assertEquals(0L, book.chapterBoundary(61_000, -1))
        // Forward is always the next one.
        assertEquals(120_000L, book.chapterBoundary(70_000, 1))
        // And it cannot run off either end.
        assertEquals(0L, book.chapterBoundary(1_000, -1))
        assertEquals(120_000L, book.chapterBoundary(130_000, 1))
    }

    // ---------------------------------------------------------------- titles

    @Test
    fun `the album tag is the book's title`() {
        val book = AudiobookBuilder.build(
            listOf(file("01.mp3", album = "Bleak House", albumArtist = "Charles Dickens")),
        )!!

        assertEquals("Bleak House", book.metadata.title)
        assertEquals(listOf("Charles Dickens"), book.metadata.authors)
    }

    @Test
    fun `without an album tag the folder name is used`() {
        val book = AudiobookBuilder.build(listOf(file("01.mp3")), folderName = "Great Expectations")!!
        assertEquals("Great Expectations", book.metadata.title)
    }

    @Test
    fun `without a folder name the shared part of the file names is used`() {
        val book = AudiobookBuilder.build(
            listOf(file("Bleak House 01.mp3"), file("Bleak House 02.mp3"), file("Bleak House 03.mp3")),
        )!!

        assertEquals("Bleak House", book.metadata.title)
    }

    @Test
    fun `file names with nothing in common do not produce a nonsense title`() {
        val book = AudiobookBuilder.build(listOf(file("alpha.mp3"), file("beta.mp3")))!!
        assertEquals("Untitled audiobook", book.metadata.title)
    }

    @Test
    fun `the album artist wins over the artist, because the artist is often the narrator`() {
        val book = AudiobookBuilder.build(
            listOf(file("01.mp3", album = "A Book", artist = "The Narrator", albumArtist = "The Author")),
        )!!

        assertEquals(listOf("The Author"), book.metadata.authors)
    }

    @Test
    fun `a narrator different from the author is recorded`() {
        val entry = AudioFileEntry(
            uri = "/b/01.m4b",
            fileName = "01.m4b",
            tags = AudioTags(
                album = "A Book",
                albumArtist = "The Author",
                narrator = "The Narrator",
                durationMillis = 1000,
            ),
        )
        val book = AudiobookBuilder.build(listOf(entry))!!

        assertTrue(book.metadata.subjects.contains("Narrated by The Narrator"))
    }

    @Test
    fun `a track title falls back to a tidied file name`() {
        val book = AudiobookBuilder.build(listOf(file("03 - The Long Road.mp3")))!!
        assertEquals("The Long Road", book.tracks.single().title)
    }

    @Test
    fun `nothing at all gives nothing, rather than an empty book`() {
        assertNull(AudiobookBuilder.build(emptyList()))
    }
}
