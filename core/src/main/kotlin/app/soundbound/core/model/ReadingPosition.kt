package app.soundbound.core.model

/**
 * Where the reader is, expressed so that it survives re-pagination, font changes and a
 * switch between eyes and ears. [characterOffset] is an offset into the *plain text* of the
 * chapter, which is stable for a given source file regardless of how it is laid out.
 */
data class ReadingPosition(
    val chapter: ChapterIndex,
    val characterOffset: Int = 0,
    /** Index of the sentence containing [characterOffset], when known. Used to resume speech exactly. */
    val sentenceIndex: Int = -1,
    val updatedAtEpochMillis: Long = 0,
) : Comparable<ReadingPosition> {
    init {
        require(characterOffset >= 0) { "characterOffset must be >= 0, was $characterOffset" }
    }

    override fun compareTo(other: ReadingPosition): Int {
        val byChapter = chapter.compareTo(other.chapter)
        return if (byChapter != 0) byChapter else characterOffset.compareTo(other.characterOffset)
    }

    companion object {
        val START = ReadingPosition(ChapterIndex(0), 0, -1, 0)
    }
}

/** Overall progress through a book, 0.0 .. 1.0, plus the human-readable remainder. */
data class ReadingProgress(
    val fraction: Double,
    val charactersRead: Long,
    val charactersTotal: Long,
    val chapterFraction: Double,
) {
    companion object {
        val NONE = ReadingProgress(0.0, 0, 0, 0.0)
    }
}

/** A user-placed bookmark. */
data class Bookmark(
    val id: String,
    val bookId: BookId,
    val position: ReadingPosition,
    val label: String?,
    /** A short excerpt captured at creation time so the list is readable without loading the book. */
    val excerpt: String,
    val createdAtEpochMillis: Long,
)

enum class HighlightColour { YELLOW, GREEN, BLUE, PINK, PURPLE, UNDERLINE }

/** A highlighted range, optionally with a note attached. */
data class Highlight(
    val id: String,
    val bookId: BookId,
    val chapter: ChapterIndex,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val note: String? = null,
    val colour: HighlightColour = HighlightColour.YELLOW,
    val createdAtEpochMillis: Long,
) {
    init {
        require(endOffset >= startOffset) { "Highlight end ($endOffset) precedes start ($startOffset)" }
    }

    val length: Int get() = endOffset - startOffset
}
