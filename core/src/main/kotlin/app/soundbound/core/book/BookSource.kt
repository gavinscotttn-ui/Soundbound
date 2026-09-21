package app.soundbound.core.book

import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import okio.Source

/**
 * An opened book. Implementations are format-specific and are expected to be cheap to create:
 * the spine and TOC are read eagerly, chapter prose is read on demand via [chapterContent].
 *
 * Implementations are *not* required to be thread-safe; the reader confines each instance to a
 * single background dispatcher.
 */
interface BookSource : AutoCloseable {
    val format: BookFormat
    val metadata: BookMetadata
    val chapters: List<Chapter>
    val toc: List<TocEntry>

    /** Parses and returns one chapter. Callers should cache; implementations need not. */
    fun chapterContent(index: ChapterIndex): ChapterContent

    /**
     * Opens a resource referenced from the book (a cover, an inline image, a font).
     * Returns null when the reference cannot be resolved, which is common in the wild.
     */
    fun readResource(ref: String): Source?

    /** Raw bytes of the cover image, if the book declares one. */
    fun coverImage(): ByteArray? = null

    /** Publisher CSS, in spine order. Used only when the reader is in "publisher styling" mode. */
    fun styleSheets(): List<String> = emptyList()
}

/** Thrown when a file cannot be parsed. Carries a message fit to show a human. */
class BookParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Opens one format. Registered with [BookOpener]. */
interface BookParser {
    val format: BookFormat

    /** Cheap sniff on the first bytes of the file plus its name. */
    fun canOpen(fileName: String, header: ByteArray): Boolean

    fun open(file: BookFileHandle): BookSource
}

/**
 * Platform-neutral handle to the book file. The desktop app backs this with `java.io.File`;
 * Android backs it with a SAF `DocumentFile` copied into app storage, so both sides get
 * random access without :core knowing anything about either platform.
 */
interface BookFileHandle {
    val displayName: String
    val sizeBytes: Long

    /** A fresh stream over the whole file. */
    fun openSource(): Source

    /**
     * The file as a local, seekable path when one exists. EPUB and PDF parsing want random
     * access; returning null forces the parser to spool the file to a temporary copy first.
     */
    fun localPath(): String?
}
