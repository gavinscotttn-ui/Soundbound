package app.soundbound.core.book

import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import okio.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SynchronizedBookSourceTest {

    /**
     * A parser that notices when two threads are inside it at once — which is exactly the
     * situation PDFBox handles by corrupting its own state rather than by complaining.
     */
    private class ReentrancyDetectingSource : BookSource {
        private val inside = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val calls = AtomicInteger(0)
        var closed = false
            private set

        override val format = BookFormat.EPUB
        override val metadata = BookMetadata("Concurrent", listOf("Nobody"))
        override val chapters = (0 until 8).map { Chapter(ChapterIndex(it), "C$it", "c$it", 10) }
        override val toc = emptyList<TocEntry>()

        override fun chapterContent(index: ChapterIndex): ChapterContent {
            val depth = inside.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, depth) }
            calls.incrementAndGet()
            try {
                // Long enough that an unsynchronised source would certainly overlap.
                Thread.sleep(6)
                return ChapterContent(index, "C${index.value}", emptyList(), "text")
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun readResource(ref: String): Source? {
            val depth = inside.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, depth) }
            try {
                Thread.sleep(3)
                return null
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun close() { closed = true }
    }

    @Test
    fun `an unwrapped source really is entered concurrently`() {
        // Establishes that the test itself is capable of detecting the problem, so the wrapped
        // case below is evidence rather than coincidence.
        val source = ReentrancyDetectingSource()
        hammer(source)
        assertTrue(source.maxConcurrent.get() > 1) {
            "The test did not actually produce concurrent access, so it proves nothing"
        }
    }

    @Test
    fun `the wrapper lets only one thread inside the parser`() {
        val underlying = ReentrancyDetectingSource()
        hammer(underlying.synchronised())
        assertEquals(1, underlying.maxConcurrent.get()) {
            "Two threads were inside the parser at once"
        }
        assertTrue(underlying.calls.get() >= 16)
    }

    private fun hammer(source: BookSource) {
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(16) { index ->
                pool.submit {
                    source.chapterContent(ChapterIndex(index % 8))
                    source.readResource("anything")
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS)) { "The workers did not finish" }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `everything else passes straight through`() {
        val underlying = ReentrancyDetectingSource()
        val wrapped = underlying.synchronised()

        assertEquals(BookFormat.EPUB, wrapped.format)
        assertEquals("Concurrent", wrapped.metadata.title)
        assertEquals(8, wrapped.chapters.size)
        assertEquals(emptyList<TocEntry>(), wrapped.toc)

        wrapped.close()
        assertTrue(underlying.closed)
    }

    @Test
    fun `wrapping something already wrapped does not stack`() {
        val once = ReentrancyDetectingSource().synchronised()
        assertSame(once, once.synchronised())
    }

    @Test
    fun `the underlying source can still be reached`() {
        val underlying = ReentrancyDetectingSource()
        val wrapped = underlying.synchronised() as SynchronizedBookSource
        assertSame(underlying, wrapped.unwrap())
    }
}
