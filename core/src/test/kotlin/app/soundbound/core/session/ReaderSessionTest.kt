package app.soundbound.core.session

import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.BookSource
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookId
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.ReadingPosition
import app.soundbound.core.model.TocEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSessionTest {

    private val dispatcher = StandardTestDispatcher()

    private class StubBook : BookSource {
        override val format = BookFormat.EPUB
        override val metadata = BookMetadata("A Stub Book", listOf("A Writer"))

        override val chapters = listOf(
            Chapter(ChapterIndex(0), "One", "c1.xhtml", 200),
            Chapter(ChapterIndex(1), "Two", "c2.xhtml", 200),
            Chapter(ChapterIndex(2), "Three", "c3.xhtml", 200),
        )

        override val toc = listOf(
            TocEntry("One", ChapterIndex(0)),
            TocEntry("Two", ChapterIndex(1), fragment = "midway"),
        )

        var loads = 0
            private set
        var closed = false
            private set

        override fun chapterContent(index: ChapterIndex): ChapterContent {
            loads++
            val heading = "Chapter ${index.value + 1}"
            val body = when (index.value) {
                0 -> "The badger appeared at dusk. Nobody was expecting it."
                1 -> "A second mention of the badger, halfway through the book."
                else -> "No badgers whatsoever in this final chapter."
            }
            val plain = StringBuilder()
            plain.append(heading).append("\n\n").append(body)
            return ChapterContent(
                chapterIndex = index,
                title = heading,
                blocks = listOf(
                    ContentBlock(BlockKind.HEADING_1, heading, 0, anchors = listOf("start")),
                    ContentBlock(
                        BlockKind.PARAGRAPH, body, heading.length + 2,
                        anchors = if (index.value == 1) listOf("midway") else emptyList(),
                    ),
                ),
                plainText = plain.toString(),
            )
        }

        override fun readResource(ref: String): Source? = null
        override fun close() { closed = true }
    }

    private fun session() = ReaderSession(dispatcher)

    @Test
    fun `opening loads the chapter at the saved position`() = runTest(dispatcher) {
        val book = StubBook()
        val reader = session()
        reader.open(BookId("b"), book, ReadingPosition(ChapterIndex(1), 12))

        val state = reader.state.value
        assertEquals(ChapterIndex(1), state.chapter)
        assertEquals("Chapter 2", state.content?.title)
        assertEquals(3, state.chapterCount)
        assertEquals(2, state.toc.size)
        assertFalse(state.isLoading)
        assertEquals(12, state.pendingScrollOffset)
        reader.close()
    }

    @Test
    fun `navigation is clamped to the book`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition.START)

        reader.previousChapter()
        assertEquals(ChapterIndex(0), reader.state.value.chapter) { "Cannot go before the first chapter" }

        reader.goToChapter(ChapterIndex(99))
        assertEquals(ChapterIndex(2), reader.state.value.chapter) { "Clamped to the last chapter" }

        reader.nextChapter()
        assertEquals(ChapterIndex(2), reader.state.value.chapter) { "Cannot go past the last chapter" }
        reader.close()
    }

    @Test
    fun `a table of contents entry with a fragment scrolls to the anchor`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition.START)

        reader.goToTocEntry(TocEntry("Two", ChapterIndex(1), fragment = "midway"))

        val state = reader.state.value
        assertEquals(ChapterIndex(1), state.chapter)
        val offset = state.pendingScrollOffset
        assertNotNull(offset)
        assertTrue(state.content!!.plainText.startsWith("A second mention", offset!!)) {
            "Expected to land on the anchored paragraph"
        }
        reader.close()
    }

    @Test
    fun `the pending scroll is cleared once honoured`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition(ChapterIndex(0), 8))
        assertNotNull(reader.state.value.pendingScrollOffset)
        reader.consumePendingScroll()
        assertNull(reader.state.value.pendingScrollOffset)
        reader.close()
    }

    @Test
    fun `an internal link to another chapter is followed`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition.START)

        assertTrue(reader.followLink("c2.xhtml#midway"))
        assertEquals(ChapterIndex(1), reader.state.value.chapter)
        reader.close()
    }

    @Test
    fun `a bare fragment is followed within the current chapter`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition(ChapterIndex(1)))
        reader.consumePendingScroll()

        assertTrue(reader.followLink("#midway"))
        assertNotNull(reader.state.value.pendingScrollOffset)
        reader.close()
    }

    @Test
    fun `an external link is declined so the caller can open it elsewhere`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition.START)
        assertFalse(reader.followLink("https://example.com"))
        assertFalse(reader.followLink("mailto:someone@example.com"))
        assertFalse(reader.followLink("missing.xhtml"))
        reader.close()
    }

    @Test
    fun `search finds every occurrence across the book with context`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition.START)

        val progressUpdates = ArrayList<SearchProgress>()
        reader.search("badger") { progressUpdates.add(it) }

        assertTrue(progressUpdates.isNotEmpty())
        assertTrue(progressUpdates.last().isComplete)
        assertEquals(3, progressUpdates.last().chaptersTotal)

        // Three: the plural "badgers" in the last chapter is a genuine match for "badger".
        val hits = progressUpdates.last().hits
        assertEquals(3, hits.size) { hits.map { it.snippet }.toString() }
        assertEquals(ChapterIndex(0), hits[0].chapter)
        assertEquals(ChapterIndex(1), hits[1].chapter)
        assertEquals(ChapterIndex(2), hits[2].chapter)
        hits.forEach { hit ->
            assertEquals(
                "badger",
                hit.snippet.substring(hit.matchInSnippet.first, hit.matchInSnippet.last + 1).lowercase(),
            ) { "The highlighted range does not cover the match in: ${hit.snippet}" }
        }
        reader.close()
    }

    @Test
    fun `search reports progress as it goes so early hits can be shown`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition.START)

        val updates = ArrayList<SearchProgress>()
        reader.search("badger") { updates.add(it) }

        assertEquals(3, updates.size) { "Expected one update per chapter" }
        assertTrue(updates.first().hits.isNotEmpty()) { "The first chapter's hit should be reported at once" }
        assertTrue(updates[0].fraction < updates[2].fraction)
        reader.close()
    }

    @Test
    fun `search is case-insensitive and ignores very short queries`() = runTest(dispatcher) {
        val reader = session()
        reader.open(BookId("b"), StubBook(), ReadingPosition.START)

        var hits = 0
        reader.search("BADGER") { if (it.isComplete) hits = it.hits.size }
        assertEquals(3, hits)

        var shortQueryComplete = false
        reader.search("a") { shortQueryComplete = it.isComplete }
        assertTrue(shortQueryComplete)
        reader.close()
    }

    @Test
    fun `prefetching parses the neighbouring chapters`() = runTest(dispatcher) {
        val book = StubBook()
        val reader = session()
        reader.open(BookId("b"), book, ReadingPosition(ChapterIndex(1)))
        val loadsAfterOpen = book.loads

        reader.prefetchNeighbours()
        assertEquals(loadsAfterOpen + 2, book.loads) { "Both neighbours should have been parsed" }
        reader.close()
    }

    @Test
    fun `closing the book releases the file`() = runTest(dispatcher) {
        val book = StubBook()
        val reader = session()
        reader.open(BookId("b"), book, ReadingPosition.START)
        reader.closeBook()

        assertTrue(book.closed)
        assertNull(reader.state.value.bookId)
        assertNull(reader.state.value.content)
    }

    @Test
    fun `a chapter that fails to parse reports a readable message`() = runTest(dispatcher) {
        val broken = object : BookSource {
            override val format = BookFormat.EPUB
            override val metadata = BookMetadata("Broken")
            override val chapters = listOf(Chapter(ChapterIndex(0), null, "c1", 0))
            override val toc = emptyList<TocEntry>()
            override fun chapterContent(index: ChapterIndex): ChapterContent =
                throw app.soundbound.core.book.BookParseException("This chapter is damaged.")

            override fun readResource(ref: String): Source? = null
            override fun close() = Unit
        }

        val reader = session()
        reader.open(BookId("b"), broken, ReadingPosition.START)

        assertFalse(reader.state.value.isLoading)
        assertEquals("This chapter is damaged.", reader.state.value.errorMessage)
        reader.close()
    }

    @Test
    fun `updating the position does not reload the chapter`() = runTest(dispatcher) {
        val book = StubBook()
        val reader = session()
        reader.open(BookId("b"), book, ReadingPosition.START)
        val loads = book.loads

        reader.updatePosition(42)
        assertEquals(42, reader.state.value.position.characterOffset)
        assertEquals(loads, book.loads)
        reader.close()
    }
}
