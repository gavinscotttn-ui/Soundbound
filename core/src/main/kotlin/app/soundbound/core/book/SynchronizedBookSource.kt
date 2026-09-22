package app.soundbound.core.book

import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import okio.Source

/**
 * Serialises access to a [BookSource].
 *
 * The reader and read-aloud both hold the same open book and both parse chapters, on different
 * dispatchers. Parsers are not required to be thread-safe and some emphatically are not — PDFBox's
 * `PDDocument` will corrupt its own parser state if two threads walk it at once, producing garbled
 * text or an exception several chapters later, which is a miserable thing to debug.
 *
 * Rather than requiring every parser to be thread-safe, or trusting that the reader and the player
 * never overlap, the shared instance is wrapped once here. Chapter parsing is the only call that
 * takes any real time, and the source's own cache means the second caller for the same chapter
 * returns immediately, so the lock is almost never contended.
 */
class SynchronizedBookSource(private val delegate: BookSource) : BookSource {

    private val lock = Any()

    override val format: BookFormat get() = delegate.format
    override val metadata: BookMetadata get() = delegate.metadata
    override val chapters: List<Chapter> get() = delegate.chapters
    override val toc: List<TocEntry> get() = delegate.toc

    override fun chapterContent(index: ChapterIndex): ChapterContent =
        synchronized(lock) { delegate.chapterContent(index) }

    override fun readResource(ref: String): Source? =
        synchronized(lock) { delegate.readResource(ref) }

    override fun coverImage(): ByteArray? = synchronized(lock) { delegate.coverImage() }

    override fun styleSheets(): List<String> = synchronized(lock) { delegate.styleSheets() }

    override fun close() = synchronized(lock) { delegate.close() }

    /** The wrapped source, for the rare caller that needs the concrete type. */
    fun unwrap(): BookSource = delegate
}

/** Wraps a source so that concurrent readers cannot corrupt a parser's state. */
fun BookSource.synchronised(): BookSource =
    if (this is SynchronizedBookSource) this else SynchronizedBookSource(this)
