package app.soundbound.core.model

/** How the source file is encoded on disk. */
enum class BookFormat(val displayName: String, val extensions: List<String>) {
    EPUB("EPUB", listOf("epub")),
    PDF("PDF", listOf("pdf")),
    PLAIN_TEXT("Text", listOf("txt", "text", "md", "markdown")),
    ;

    companion object {
        fun fromExtension(ext: String): BookFormat? {
            val normalised = ext.removePrefix(".").lowercase()
            return entries.firstOrNull { normalised in it.extensions }
        }

        fun fromFileName(name: String): BookFormat? =
            name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let(::fromExtension)
    }
}

/**
 * Bibliographic metadata. Everything except [title] is best-effort: real-world EPUBs and
 * PDFs are frequently sloppy, so absent values are modelled as null rather than "".
 */
data class BookMetadata(
    val title: String,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Double? = null,
    val publisher: String? = null,
    val language: String? = null,
    val description: String? = null,
    val subjects: List<String> = emptyList(),
    val identifier: String? = null,
    val publishedDate: String? = null,
) {
    val authorLine: String
        get() = when (authors.size) {
            0 -> "Unknown author"
            1 -> authors[0]
            2 -> "${authors[0]} & ${authors[1]}"
            else -> authors.dropLast(1).joinToString(", ") + " & " + authors.last()
        }

    val sortTitle: String
        get() = title.trim().removeLeadingArticle()

    val sortAuthor: String
        get() = authors.firstOrNull()?.let { author ->
            val parts = author.trim().split(Regex("\\s+"))
            if (parts.size >= 2) parts.last() + ", " + parts.dropLast(1).joinToString(" ") else author
        } ?: "￿"
}

private val LEADING_ARTICLES = listOf("the ", "a ", "an ")

internal fun String.removeLeadingArticle(): String {
    val lower = lowercase()
    for (article in LEADING_ARTICLES) {
        if (lower.startsWith(article)) return substring(article.length)
    }
    return this
}

/**
 * A chapter is the unit of lazy loading: opening a book parses the spine but not the prose.
 * [contentRef] is an opaque handle the format's parser uses to fetch the text on demand
 * (an EPUB manifest href, a PDF page range, and so on).
 */
data class Chapter(
    val index: ChapterIndex,
    val title: String?,
    val contentRef: String,
    /** Characters of readable text, if the parser could work it out cheaply. Used for progress maths. */
    val approximateCharacters: Int = 0,
)

/** A single entry of the table of contents, which may nest arbitrarily deep. */
data class TocEntry(
    val title: String,
    val chapter: ChapterIndex,
    /** Fragment within the chapter, e.g. an EPUB anchor id. Null means "start of chapter". */
    val fragment: String? = null,
    val children: List<TocEntry> = emptyList(),
    val depth: Int = 0,
) {
    fun flatten(): List<TocEntry> = buildList {
        add(this@TocEntry)
        children.forEach { addAll(it.flatten()) }
    }
}

/** The library's record of a book. Cheap to construct; holds no prose. */
data class Book(
    val id: BookId,
    val metadata: BookMetadata,
    val format: BookFormat,
    /** Where the file lives. A filesystem path on desktop, a SAF document URI on Android. */
    val sourceUri: String,
    val fileSizeBytes: Long = 0,
    val addedAtEpochMillis: Long = 0,
    val lastOpenedAtEpochMillis: Long? = null,
    val coverImageRef: String? = null,
    val totalCharacters: Long = 0,
    val chapterCount: Int = 0,
    val tags: Set<String> = emptySet(),
    val isFavourite: Boolean = false,
    val finishedAtEpochMillis: Long? = null,
) {
    val isFinished: Boolean get() = finishedAtEpochMillis != null
}
