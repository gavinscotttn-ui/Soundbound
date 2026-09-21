package app.soundbound.core.model

import kotlin.jvm.JvmInline

/** Stable identity for a book in the library. Derived from the content hash of the source file. */
@JvmInline
value class BookId(val value: String) {
    init { require(value.isNotBlank()) { "BookId must not be blank" } }
    override fun toString(): String = value
}

/** Identity for an installed voice, e.g. `piper/en_GB-alba-medium`. */
@JvmInline
value class VoiceId(val value: String) {
    init { require(value.isNotBlank()) { "VoiceId must not be blank" } }
    override fun toString(): String = value
}

/**
 * Index of a spine item (EPUB) or a page group (PDF) inside a book.
 * Always zero-based and always dense: chapter N is `book.chapters[N]`.
 */
@JvmInline
value class ChapterIndex(val value: Int) : Comparable<ChapterIndex> {
    init { require(value >= 0) { "ChapterIndex must be >= 0, was $value" } }
    override fun compareTo(other: ChapterIndex): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
}
