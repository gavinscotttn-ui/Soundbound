package app.soundbound.core.library

import app.soundbound.core.model.Bookmark
import app.soundbound.core.model.Highlight
import app.soundbound.core.model.ReadingPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of the library.
 *
 * Deliberately a separate set of types from the domain model. The domain model is free to
 * change shape; this one has to stay readable by older and newer versions of the app, so it is
 * versioned, every field has a default, and unknown fields are ignored on read. A library that
 * fails to load is the worst bug this app could have.
 */
@Serializable
internal data class LibraryFile(
    @SerialName("version") val version: Int = CURRENT_VERSION,
    @SerialName("books") val books: List<BookRecord> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class BookRecord(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val format: String,
    val sourceUri: String,
    val series: String? = null,
    val seriesIndex: Double? = null,
    val publisher: String? = null,
    val language: String? = null,
    val description: String? = null,
    val subjects: List<String> = emptyList(),
    val identifier: String? = null,
    val publishedDate: String? = null,
    val fileSizeBytes: Long = 0,
    val addedAt: Long = 0,
    val lastOpenedAt: Long? = null,
    val coverRef: String? = null,
    val totalCharacters: Long = 0,
    val chapterCount: Int = 0,
    val tags: List<String> = emptyList(),
    val favourite: Boolean = false,
    val finishedAt: Long? = null,
    val position: PositionRecord? = null,
    val bookmarks: List<BookmarkRecord> = emptyList(),
    val highlights: List<HighlightRecord> = emptyList(),
    /** Per-book speech overrides: a specific voice, speed and so on. */
    val voiceId: String? = null,
    val speechRate: Float? = null,
)

@Serializable
internal data class PositionRecord(
    val chapter: Int = 0,
    val characterOffset: Int = 0,
    val sentenceIndex: Int = -1,
    val updatedAt: Long = 0,
) {
    fun toDomain() = ReadingPosition(
        chapter = app.soundbound.core.model.ChapterIndex(chapter.coerceAtLeast(0)),
        characterOffset = characterOffset.coerceAtLeast(0),
        sentenceIndex = sentenceIndex,
        updatedAtEpochMillis = updatedAt,
    )

    companion object {
        fun of(position: ReadingPosition) = PositionRecord(
            chapter = position.chapter.value,
            characterOffset = position.characterOffset,
            sentenceIndex = position.sentenceIndex,
            updatedAt = position.updatedAtEpochMillis,
        )
    }
}

@Serializable
internal data class BookmarkRecord(
    val id: String,
    val chapter: Int,
    val characterOffset: Int,
    val label: String? = null,
    val excerpt: String = "",
    val createdAt: Long = 0,
) {
    fun toDomain(bookId: app.soundbound.core.model.BookId) = Bookmark(
        id = id,
        bookId = bookId,
        position = ReadingPosition(
            app.soundbound.core.model.ChapterIndex(chapter.coerceAtLeast(0)),
            characterOffset.coerceAtLeast(0),
        ),
        label = label,
        excerpt = excerpt,
        createdAtEpochMillis = createdAt,
    )

    companion object {
        fun of(bookmark: Bookmark) = BookmarkRecord(
            id = bookmark.id,
            chapter = bookmark.position.chapter.value,
            characterOffset = bookmark.position.characterOffset,
            label = bookmark.label,
            excerpt = bookmark.excerpt,
            createdAt = bookmark.createdAtEpochMillis,
        )
    }
}

@Serializable
internal data class HighlightRecord(
    val id: String,
    val chapter: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val note: String? = null,
    val colour: String = "YELLOW",
    val createdAt: Long = 0,
) {
    fun toDomain(bookId: app.soundbound.core.model.BookId) = Highlight(
        id = id,
        bookId = bookId,
        chapter = app.soundbound.core.model.ChapterIndex(chapter.coerceAtLeast(0)),
        startOffset = startOffset.coerceAtLeast(0),
        endOffset = maxOf(startOffset, endOffset),
        text = text,
        note = note,
        colour = runCatching { app.soundbound.core.model.HighlightColour.valueOf(colour) }
            .getOrDefault(app.soundbound.core.model.HighlightColour.YELLOW),
        createdAtEpochMillis = createdAt,
    )

    companion object {
        fun of(highlight: Highlight) = HighlightRecord(
            id = highlight.id,
            chapter = highlight.chapter.value,
            startOffset = highlight.startOffset,
            endOffset = highlight.endOffset,
            text = highlight.text,
            note = highlight.note,
            colour = highlight.colour.name,
            createdAt = highlight.createdAtEpochMillis,
        )
    }
}
