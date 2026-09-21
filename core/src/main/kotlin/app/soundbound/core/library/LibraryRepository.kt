package app.soundbound.core.library

import app.soundbound.core.model.Book
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookId
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Bookmark
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.Highlight
import app.soundbound.core.model.HighlightColour
import app.soundbound.core.model.ReadingPosition
import app.soundbound.core.model.VoiceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** How the library grid is ordered. */
enum class LibrarySort(val displayName: String) {
    RECENTLY_OPENED("Recently opened"),
    RECENTLY_ADDED("Recently added"),
    TITLE("Title"),
    AUTHOR("Author"),
    SERIES("Series"),
    PROGRESS("Progress"),
}

/** Which books the library shows. */
enum class LibraryFilter(val displayName: String) {
    ALL("All books"),
    IN_PROGRESS("Reading now"),
    NOT_STARTED("Not started"),
    FINISHED("Finished"),
    FAVOURITES("Favourites"),
}

/** Everything the app knows about a book, in one object for the UI to render. */
data class LibraryEntry(
    val book: Book,
    val position: ReadingPosition,
    val bookmarks: List<Bookmark>,
    val highlights: List<Highlight>,
    val voiceId: VoiceId?,
    val speechRate: Float?,
) {
    /**
     * Fraction of the book read, estimated from the character offset. It is an estimate because
     * an exact figure would mean parsing every chapter, which is not worth doing to draw a
     * progress bar.
     */
    val progressFraction: Double
        get() {
            if (book.isFinished) return 1.0
            if (book.totalCharacters <= 0 || book.chapterCount <= 0) return 0.0
            val perChapter = book.totalCharacters.toDouble() / book.chapterCount
            val read = position.chapter.value * perChapter + position.characterOffset
            return (read / book.totalCharacters).coerceIn(0.0, 1.0)
        }

    val hasStarted: Boolean
        get() = position.chapter.value > 0 || position.characterOffset > 0
}

/**
 * The library: what books exist, where the reader is in each, and everything they have marked.
 *
 * Backed by a single JSON document rather than a database. A personal library is hundreds of
 * books, not millions; holding it in memory makes search and sorting instant, and one
 * atomically written file is far easier to back up, sync by hand, and inspect when something
 * goes wrong than a binary database would be.
 */
class LibraryRepository(libraryFile: File) {

    private val store = JsonStore(libraryFile, LibraryFile.serializer()) { LibraryFile() }

    private val _entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    init {
        _entries.value = store.load().books.mapNotNull(::toEntry)
    }

    // ---------------------------------------------------------------- reading

    fun entry(id: BookId): LibraryEntry? = _entries.value.firstOrNull { it.book.id == id }

    fun position(id: BookId): ReadingPosition = entry(id)?.position ?: ReadingPosition.START

    /**
     * The library as the user asked to see it. Search matches title, author, series and tags,
     * because that is what people actually type.
     */
    fun view(
        sort: LibrarySort = LibrarySort.RECENTLY_OPENED,
        filter: LibraryFilter = LibraryFilter.ALL,
        query: String = "",
        tag: String? = null,
    ): List<LibraryEntry> {
        val needle = query.trim().lowercase()
        return _entries.value
            .filter { entry -> matchesFilter(entry, filter) }
            .filter { entry -> tag == null || tag in entry.book.tags }
            .filter { entry -> needle.isEmpty() || matchesQuery(entry, needle) }
            .sortedWith(comparatorFor(sort))
    }

    fun tags(): List<String> = _entries.value
        .flatMap { it.book.tags }
        .distinct()
        .sorted()

    fun series(): List<String> = _entries.value
        .mapNotNull { it.book.metadata.series }
        .distinct()
        .sorted()

    private fun matchesFilter(entry: LibraryEntry, filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.IN_PROGRESS -> entry.hasStarted && !entry.book.isFinished
        LibraryFilter.NOT_STARTED -> !entry.hasStarted && !entry.book.isFinished
        LibraryFilter.FINISHED -> entry.book.isFinished
        LibraryFilter.FAVOURITES -> entry.book.isFavourite
    }

    private fun matchesQuery(entry: LibraryEntry, needle: String): Boolean {
        val metadata = entry.book.metadata
        return metadata.title.contains(needle, ignoreCase = true) ||
            metadata.authors.any { it.contains(needle, ignoreCase = true) } ||
            metadata.series?.contains(needle, ignoreCase = true) == true ||
            entry.book.tags.any { it.contains(needle, ignoreCase = true) } ||
            metadata.publisher?.contains(needle, ignoreCase = true) == true
    }

    private fun comparatorFor(sort: LibrarySort): Comparator<LibraryEntry> = when (sort) {
        LibrarySort.RECENTLY_OPENED -> compareByDescending {
            it.book.lastOpenedAtEpochMillis ?: it.book.addedAtEpochMillis
        }

        LibrarySort.RECENTLY_ADDED -> compareByDescending { it.book.addedAtEpochMillis }
        LibrarySort.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.book.metadata.sortTitle }
        LibrarySort.AUTHOR -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.book.metadata.sortAuthor }
        LibrarySort.SERIES -> compareBy(
            { it.book.metadata.series ?: "￿" },
            { it.book.metadata.seriesIndex ?: Double.MAX_VALUE },
            { it.book.metadata.sortTitle },
        )

        LibrarySort.PROGRESS -> compareByDescending { it.progressFraction }
    }

    // ---------------------------------------------------------------- writing

    /** Adds a book, or refreshes the record of one already present without losing its position. */
    fun upsert(book: Book): LibraryEntry {
        val existing = entry(book.id)
        val merged = if (existing == null) {
            LibraryEntry(
                book = book.copy(
                    addedAtEpochMillis = book.addedAtEpochMillis.takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                ),
                position = ReadingPosition.START,
                bookmarks = emptyList(),
                highlights = emptyList(),
                voiceId = null,
                speechRate = null,
            )
        } else {
            existing.copy(
                book = book.copy(
                    addedAtEpochMillis = existing.book.addedAtEpochMillis,
                    lastOpenedAtEpochMillis = existing.book.lastOpenedAtEpochMillis,
                    tags = existing.book.tags,
                    isFavourite = existing.book.isFavourite,
                    finishedAtEpochMillis = existing.book.finishedAtEpochMillis,
                ),
            )
        }
        replace(merged)
        return merged
    }

    fun remove(id: BookId) {
        _entries.value = _entries.value.filterNot { it.book.id == id }
        persist()
    }

    fun markOpened(id: BookId) = mutate(id) { entry ->
        entry.copy(book = entry.book.copy(lastOpenedAtEpochMillis = System.currentTimeMillis()))
    }

    fun savePosition(id: BookId, position: ReadingPosition) = mutate(id) { entry ->
        // Never move a saved position backwards on a stale update: a background save racing a
        // user's jump forwards would otherwise lose their place.
        if (position.updatedAtEpochMillis < entry.position.updatedAtEpochMillis) entry
        else entry.copy(position = position)
    }

    fun setFavourite(id: BookId, favourite: Boolean) = mutate(id) { entry ->
        entry.copy(book = entry.book.copy(isFavourite = favourite))
    }

    fun setFinished(id: BookId, finished: Boolean) = mutate(id) { entry ->
        entry.copy(
            book = entry.book.copy(
                finishedAtEpochMillis = if (finished) System.currentTimeMillis() else null,
            ),
        )
    }

    fun setTags(id: BookId, tags: Set<String>) = mutate(id) { entry ->
        entry.copy(book = entry.book.copy(tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()))
    }

    fun setMetadata(id: BookId, metadata: BookMetadata) = mutate(id) { entry ->
        entry.copy(book = entry.book.copy(metadata = metadata))
    }

    fun setBookVoice(id: BookId, voiceId: VoiceId?, rate: Float?) = mutate(id) { entry ->
        entry.copy(voiceId = voiceId, speechRate = rate)
    }

    fun addBookmark(id: BookId, position: ReadingPosition, excerpt: String, label: String? = null): Bookmark? {
        var created: Bookmark? = null
        mutate(id) { entry ->
            val bookmark = Bookmark(
                id = newId(),
                bookId = id,
                position = position,
                label = label,
                excerpt = excerpt.take(280),
                createdAtEpochMillis = System.currentTimeMillis(),
            )
            created = bookmark
            entry.copy(bookmarks = (entry.bookmarks + bookmark).sortedBy { it.position })
        }
        return created
    }

    fun removeBookmark(id: BookId, bookmarkId: String) = mutate(id) { entry ->
        entry.copy(bookmarks = entry.bookmarks.filterNot { it.id == bookmarkId })
    }

    fun addHighlight(
        id: BookId,
        chapter: ChapterIndex,
        startOffset: Int,
        endOffset: Int,
        text: String,
        colour: HighlightColour = HighlightColour.YELLOW,
        note: String? = null,
    ): Highlight? {
        var created: Highlight? = null
        mutate(id) { entry ->
            val highlight = Highlight(
                id = newId(),
                bookId = id,
                chapter = chapter,
                startOffset = startOffset,
                endOffset = maxOf(startOffset, endOffset),
                text = text,
                note = note,
                colour = colour,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
            created = highlight
            entry.copy(
                highlights = (entry.highlights + highlight)
                    .sortedWith(compareBy({ it.chapter.value }, { it.startOffset })),
            )
        }
        return created
    }

    fun updateHighlight(id: BookId, highlight: Highlight) = mutate(id) { entry ->
        entry.copy(highlights = entry.highlights.map { if (it.id == highlight.id) highlight else it })
    }

    fun removeHighlight(id: BookId, highlightId: String) = mutate(id) { entry ->
        entry.copy(highlights = entry.highlights.filterNot { it.id == highlightId })
    }

    fun highlightsIn(id: BookId, chapter: ChapterIndex): List<Highlight> =
        entry(id)?.highlights?.filter { it.chapter == chapter }.orEmpty()

    /** Every highlight and note across the library, newest first. For the notebook screen. */
    fun allAnnotations(): List<Pair<Book, Highlight>> = _entries.value
        .flatMap { entry -> entry.highlights.map { entry.book to it } }
        .sortedByDescending { it.second.createdAtEpochMillis }

    // ---------------------------------------------------------------- plumbing

    private fun mutate(id: BookId, transform: (LibraryEntry) -> LibraryEntry) {
        val current = _entries.value
        val index = current.indexOfFirst { it.book.id == id }
        if (index < 0) return
        val updated = transform(current[index])
        if (updated == current[index]) return
        _entries.value = current.toMutableList().also { it[index] = updated }
        persist()
    }

    private fun replace(entry: LibraryEntry) {
        val current = _entries.value
        val index = current.indexOfFirst { it.book.id == entry.book.id }
        _entries.value = if (index < 0) current + entry else {
            current.toMutableList().also { it[index] = entry }
        }
        persist()
    }

    private fun persist() {
        store.write(LibraryFile(books = _entries.value.map(::toRecord)))
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString()

    private fun toEntry(record: BookRecord): LibraryEntry? {
        val format = BookFormat.entries.firstOrNull { it.name == record.format } ?: return null
        val id = runCatching { BookId(record.id) }.getOrNull() ?: return null
        val book = Book(
            id = id,
            metadata = BookMetadata(
                title = record.title,
                authors = record.authors,
                series = record.series,
                seriesIndex = record.seriesIndex,
                publisher = record.publisher,
                language = record.language,
                description = record.description,
                subjects = record.subjects,
                identifier = record.identifier,
                publishedDate = record.publishedDate,
            ),
            format = format,
            sourceUri = record.sourceUri,
            fileSizeBytes = record.fileSizeBytes,
            addedAtEpochMillis = record.addedAt,
            lastOpenedAtEpochMillis = record.lastOpenedAt,
            coverImageRef = record.coverRef,
            totalCharacters = record.totalCharacters,
            chapterCount = record.chapterCount,
            tags = record.tags.toSet(),
            isFavourite = record.favourite,
            finishedAtEpochMillis = record.finishedAt,
        )
        return LibraryEntry(
            book = book,
            position = record.position?.toDomain() ?: ReadingPosition.START,
            bookmarks = record.bookmarks.map { it.toDomain(id) },
            highlights = record.highlights.map { it.toDomain(id) },
            voiceId = record.voiceId?.let { runCatching { VoiceId(it) }.getOrNull() },
            speechRate = record.speechRate,
        )
    }

    private fun toRecord(entry: LibraryEntry): BookRecord {
        val book = entry.book
        return BookRecord(
            id = book.id.value,
            title = book.metadata.title,
            authors = book.metadata.authors,
            format = book.format.name,
            sourceUri = book.sourceUri,
            series = book.metadata.series,
            seriesIndex = book.metadata.seriesIndex,
            publisher = book.metadata.publisher,
            language = book.metadata.language,
            description = book.metadata.description,
            subjects = book.metadata.subjects,
            identifier = book.metadata.identifier,
            publishedDate = book.metadata.publishedDate,
            fileSizeBytes = book.fileSizeBytes,
            addedAt = book.addedAtEpochMillis,
            lastOpenedAt = book.lastOpenedAtEpochMillis,
            coverRef = book.coverImageRef,
            totalCharacters = book.totalCharacters,
            chapterCount = book.chapterCount,
            tags = book.tags.toList().sorted(),
            favourite = book.isFavourite,
            finishedAt = book.finishedAtEpochMillis,
            position = PositionRecord.of(entry.position),
            bookmarks = entry.bookmarks.map(BookmarkRecord::of),
            highlights = entry.highlights.map(HighlightRecord::of),
            voiceId = entry.voiceId?.value,
            speechRate = entry.speechRate,
        )
    }
}
