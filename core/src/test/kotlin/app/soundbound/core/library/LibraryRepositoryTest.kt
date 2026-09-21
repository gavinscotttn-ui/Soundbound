package app.soundbound.core.library

import app.soundbound.core.model.Book
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookId
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.HighlightColour
import app.soundbound.core.model.ReadingPosition
import app.soundbound.core.model.VoiceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LibraryRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private fun repository() = LibraryRepository(File(tempDir, "library.json"))

    private fun book(
        id: String,
        title: String,
        author: String = "Jane Austen",
        series: String? = null,
        seriesIndex: Double? = null,
        addedAt: Long = 1_000,
    ) = Book(
        id = BookId(id),
        metadata = BookMetadata(
            title = title,
            authors = listOf(author),
            series = series,
            seriesIndex = seriesIndex,
        ),
        format = BookFormat.EPUB,
        sourceUri = "/books/$id.epub",
        addedAtEpochMillis = addedAt,
        totalCharacters = 100_000,
        chapterCount = 10,
    )

    @Test
    fun `books are added and found again`() {
        val library = repository()
        val entry = library.upsert(book("a", "Persuasion"))
        assertEquals("Persuasion", entry.book.metadata.title)
        assertEquals(1, library.entries.value.size)
        assertNotNull(library.entry(BookId("a")))
        assertNull(library.entry(BookId("nope")))
    }

    @Test
    fun `everything survives a reload from disk`() {
        val file = File(tempDir, "library.json")
        val first = LibraryRepository(file)
        first.upsert(book("a", "Emma"))
        first.savePosition(BookId("a"), ReadingPosition(ChapterIndex(3), 512, 7, 99))
        first.addBookmark(BookId("a"), ReadingPosition(ChapterIndex(3), 512), "…a fine morning…", "Good bit")
        first.addHighlight(BookId("a"), ChapterIndex(2), 10, 40, "a highlighted phrase", HighlightColour.GREEN, "A note")
        first.setTags(BookId("a"), setOf("classics", "reread"))
        first.setFavourite(BookId("a"), true)
        first.setBookVoice(BookId("a"), VoiceId("piper/en_GB-alba-medium"), 1.25f)

        val reloaded = LibraryRepository(file)
        val entry = reloaded.entry(BookId("a"))!!
        assertEquals("Emma", entry.book.metadata.title)
        assertEquals(ChapterIndex(3), entry.position.chapter)
        assertEquals(512, entry.position.characterOffset)
        assertEquals(1, entry.bookmarks.size)
        assertEquals("Good bit", entry.bookmarks.single().label)
        assertEquals(1, entry.highlights.size)
        assertEquals(HighlightColour.GREEN, entry.highlights.single().colour)
        assertEquals("A note", entry.highlights.single().note)
        assertEquals(setOf("classics", "reread"), entry.book.tags)
        assertTrue(entry.book.isFavourite)
        assertEquals("piper/en_GB-alba-medium", entry.voiceId?.value)
        assertEquals(1.25f, entry.speechRate)
    }

    @Test
    fun `re-adding a book keeps the reading position and the tags`() {
        val library = repository()
        library.upsert(book("a", "Emma"))
        library.savePosition(BookId("a"), ReadingPosition(ChapterIndex(5), 100, 3, 50))
        library.setTags(BookId("a"), setOf("classics"))
        library.setFavourite(BookId("a"), true)

        library.upsert(book("a", "Emma (second edition)", addedAt = 9_999))

        val entry = library.entry(BookId("a"))!!
        assertEquals("Emma (second edition)", entry.book.metadata.title) { "Metadata should refresh" }
        assertEquals(ChapterIndex(5), entry.position.chapter) { "Progress must not be lost" }
        assertEquals(setOf("classics"), entry.book.tags)
        assertTrue(entry.book.isFavourite)
        assertEquals(1_000, entry.book.addedAtEpochMillis) { "The original add date is kept" }
    }

    @Test
    fun `a stale position update cannot move the bookmark backwards`() {
        val library = repository()
        library.upsert(book("a", "Emma"))
        library.savePosition(BookId("a"), ReadingPosition(ChapterIndex(5), 900, 4, 2_000))
        // A background save that started before the jump forwards arrives late.
        library.savePosition(BookId("a"), ReadingPosition(ChapterIndex(1), 10, 0, 1_000))

        assertEquals(ChapterIndex(5), library.position(BookId("a")).chapter)
    }

    @Test
    fun `search matches title author series and tags`() {
        val library = repository()
        library.upsert(book("a", "Persuasion", author = "Jane Austen"))
        library.upsert(book("b", "The Hobbit", author = "J. R. R. Tolkien", series = "Middle-earth"))
        library.setTags(BookId("b"), setOf("fantasy"))

        assertEquals(listOf("The Hobbit"), library.view(query = "tolkien").map { it.book.metadata.title })
        assertEquals(listOf("The Hobbit"), library.view(query = "middle").map { it.book.metadata.title })
        assertEquals(listOf("The Hobbit"), library.view(query = "fantasy").map { it.book.metadata.title })
        assertEquals(listOf("Persuasion"), library.view(query = "persu").map { it.book.metadata.title })
        assertEquals(2, library.view(query = "  ").size)
    }

    @Test
    fun `sorting by title ignores a leading article`() {
        val library = repository()
        library.upsert(book("a", "The Zoo"))
        library.upsert(book("b", "Apples"))
        library.upsert(book("c", "Bananas"))
        assertEquals(
            listOf("Apples", "Bananas", "The Zoo"),
            library.view(sort = LibrarySort.TITLE).map { it.book.metadata.title },
        )
    }

    @Test
    fun `sorting by author uses surname first`() {
        val library = repository()
        library.upsert(book("a", "One", author = "Jane Austen"))
        library.upsert(book("b", "Two", author = "Charles Dickens"))
        assertEquals(
            listOf("One", "Two"),
            library.view(sort = LibrarySort.AUTHOR).map { it.book.metadata.title },
        )
    }

    @Test
    fun `sorting by series follows the series order`() {
        val library = repository()
        library.upsert(book("a", "Third", series = "Saga", seriesIndex = 3.0))
        library.upsert(book("b", "First", series = "Saga", seriesIndex = 1.0))
        library.upsert(book("c", "Second", series = "Saga", seriesIndex = 2.0))
        assertEquals(
            listOf("First", "Second", "Third"),
            library.view(sort = LibrarySort.SERIES).map { it.book.metadata.title },
        )
    }

    @Test
    fun `filters separate what is being read from what is finished`() {
        val library = repository()
        library.upsert(book("a", "Started"))
        library.upsert(book("b", "Untouched"))
        library.upsert(book("c", "Done"))
        library.savePosition(BookId("a"), ReadingPosition(ChapterIndex(2), 50, 1, 10))
        library.setFinished(BookId("c"), true)

        assertEquals(listOf("Started"), library.view(filter = LibraryFilter.IN_PROGRESS).map { it.book.metadata.title })
        assertEquals(listOf("Untouched"), library.view(filter = LibraryFilter.NOT_STARTED).map { it.book.metadata.title })
        assertEquals(listOf("Done"), library.view(filter = LibraryFilter.FINISHED).map { it.book.metadata.title })
        assertEquals(3, library.view(filter = LibraryFilter.ALL).size)
    }

    @Test
    fun `progress is estimated from the position and is complete when finished`() {
        val library = repository()
        library.upsert(book("a", "Emma"))
        assertEquals(0.0, library.entry(BookId("a"))!!.progressFraction)

        library.savePosition(BookId("a"), ReadingPosition(ChapterIndex(5), 0, 0, 10))
        assertEquals(0.5, library.entry(BookId("a"))!!.progressFraction, 0.01)

        library.setFinished(BookId("a"), true)
        assertEquals(1.0, library.entry(BookId("a"))!!.progressFraction)
    }

    @Test
    fun `bookmarks and highlights can be added and removed`() {
        val library = repository()
        library.upsert(book("a", "Emma"))

        val bookmark = library.addBookmark(BookId("a"), ReadingPosition(ChapterIndex(1), 20), "excerpt")!!
        val highlight = library.addHighlight(BookId("a"), ChapterIndex(1), 5, 25, "phrase")!!
        assertEquals(1, library.entry(BookId("a"))!!.bookmarks.size)
        assertEquals(1, library.highlightsIn(BookId("a"), ChapterIndex(1)).size)
        assertEquals(0, library.highlightsIn(BookId("a"), ChapterIndex(2)).size)

        library.updateHighlight(BookId("a"), highlight.copy(note = "later thought"))
        assertEquals("later thought", library.entry(BookId("a"))!!.highlights.single().note)

        library.removeBookmark(BookId("a"), bookmark.id)
        library.removeHighlight(BookId("a"), highlight.id)
        assertTrue(library.entry(BookId("a"))!!.bookmarks.isEmpty())
        assertTrue(library.entry(BookId("a"))!!.highlights.isEmpty())
    }

    @Test
    fun `removing a book removes it from disk too`() {
        val file = File(tempDir, "library.json")
        val library = LibraryRepository(file)
        library.upsert(book("a", "Emma"))
        library.remove(BookId("a"))
        assertTrue(LibraryRepository(file).entries.value.isEmpty())
    }

    @Test
    fun `a corrupt library file falls back rather than refusing to start`() {
        val file = File(tempDir, "library.json")
        val library = LibraryRepository(file)
        library.upsert(book("a", "Emma"))
        // A second save, so that the first version has been copied aside as the backup.
        library.setFavourite(BookId("a"), true)

        // Truncate the file the way a process killed mid-write would.
        file.writeText("{\"version\":1,\"books\":[{\"id\":\"a\",\"tit")

        val recovered = LibraryRepository(file)
        // The backup written during the previous save is used, so nothing is lost.
        assertEquals(1, recovered.entries.value.size)
        assertEquals("Emma", recovered.entries.value.single().book.metadata.title)
    }

    @Test
    fun `an unreadable library with no backup starts empty instead of crashing`() {
        val file = File(tempDir, "broken.json")
        file.writeText("this is not JSON at all")
        assertTrue(LibraryRepository(file).entries.value.isEmpty())
    }

    @Test
    fun `annotations are collected across the whole library`() {
        val library = repository()
        library.upsert(book("a", "Emma"))
        library.upsert(book("b", "Persuasion"))
        library.addHighlight(BookId("a"), ChapterIndex(0), 0, 5, "first")
        library.addHighlight(BookId("b"), ChapterIndex(0), 0, 5, "second")
        assertEquals(2, library.allAnnotations().size)
    }

    @Test
    fun `tags and series can be listed for the filter menus`() {
        val library = repository()
        library.upsert(book("a", "One", series = "Saga"))
        library.upsert(book("b", "Two"))
        library.setTags(BookId("a"), setOf("classics", "reread"))
        library.setTags(BookId("b"), setOf("classics"))
        assertEquals(listOf("classics", "reread"), library.tags())
        assertEquals(listOf("Saga"), library.series())
    }

    @Test
    fun `marking a book finished can be undone`() {
        val library = repository()
        library.upsert(book("a", "Emma"))
        library.setFinished(BookId("a"), true)
        assertTrue(library.entry(BookId("a"))!!.book.isFinished)
        library.setFinished(BookId("a"), false)
        assertFalse(library.entry(BookId("a"))!!.book.isFinished)
    }
}
