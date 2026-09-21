package app.soundbound.core.session

import app.soundbound.core.book.BookSource
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.model.BookId
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.Highlight
import app.soundbound.core.model.ReadingPosition
import app.soundbound.core.model.TocEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One hit from a within-book search. */
data class SearchHit(
    val chapter: ChapterIndex,
    val chapterTitle: String?,
    val characterOffset: Int,
    /** The match with a little context either side, for the results list. */
    val snippet: String,
    /** Where the match itself sits inside [snippet]. */
    val matchInSnippet: IntRange,
)

/** Progress while a long book is being searched. */
data class SearchProgress(
    val chaptersSearched: Int,
    val chaptersTotal: Int,
    val hits: List<SearchHit>,
    val isComplete: Boolean,
) {
    val fraction: Float
        get() = if (chaptersTotal <= 0) 0f else (chaptersSearched.toFloat() / chaptersTotal).coerceIn(0f, 1f)
}

/** What the reader is showing. */
data class ReaderState(
    val bookId: BookId? = null,
    val chapter: ChapterIndex = ChapterIndex(0),
    val content: ChapterContent? = null,
    val toc: List<TocEntry> = emptyList(),
    val chapterCount: Int = 0,
    val position: ReadingPosition = ReadingPosition.START,
    val highlights: List<Highlight> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Set when the reader should scroll to this offset, then cleared once it has. */
    val pendingScrollOffset: Int? = null,
)

/**
 * A book open for reading.
 *
 * Owns the parsed chapter and the navigation around it, and is deliberately separate from
 * read-aloud: the two share a position but nothing else, so that reading with the voice off costs
 * nothing and closing the player does not close the book.
 *
 * Chapter parsing happens on [ioDispatcher] and neighbouring chapters are parsed ahead, because
 * the wait at a chapter boundary is the one place a reader feels slow.
 */
class ReaderSession(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val lock = Mutex()
    private var source: BookSource? = null
    private var bookId: BookId? = null

    val openSource: BookSource? get() = source

    suspend fun open(
        id: BookId,
        source: BookSource,
        position: ReadingPosition,
        highlights: List<Highlight> = emptyList(),
    ) = lock.withLock {
        closeInternal()
        this.source = source
        this.bookId = id
        _state.value = ReaderState(
            bookId = id,
            toc = source.toc,
            chapterCount = source.chapters.size,
            position = position,
            highlights = highlights,
            isLoading = true,
        )
        loadChapter(position.chapter, scrollTo = position.characterOffset)
    }

    /** Moves to a chapter, optionally scrolling to an offset or a named anchor within it. */
    suspend fun goToChapter(
        chapter: ChapterIndex,
        characterOffset: Int? = null,
        anchor: String? = null,
    ) = lock.withLock {
        val book = source ?: return@withLock
        val target = ChapterIndex(chapter.value.coerceIn(0, book.chapters.lastIndex))
        loadChapter(target, scrollTo = characterOffset, anchor = anchor)
    }

    suspend fun goToTocEntry(entry: TocEntry) = goToChapter(entry.chapter, anchor = entry.fragment)

    suspend fun goToPosition(position: ReadingPosition) =
        goToChapter(position.chapter, characterOffset = position.characterOffset)

    suspend fun nextChapter() {
        val current = _state.value.chapter.value
        if (current + 1 < _state.value.chapterCount) goToChapter(ChapterIndex(current + 1))
    }

    suspend fun previousChapter() {
        val current = _state.value.chapter.value
        if (current > 0) goToChapter(ChapterIndex(current - 1))
    }

    /** Records where the reader has scrolled to, without reloading anything. */
    fun updatePosition(characterOffset: Int) {
        val chapter = _state.value.chapter
        _state.value = _state.value.copy(
            position = ReadingPosition(
                chapter = chapter,
                characterOffset = characterOffset.coerceAtLeast(0),
                sentenceIndex = -1,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    /** Called by the reader once it has honoured [ReaderState.pendingScrollOffset]. */
    fun consumePendingScroll() {
        if (_state.value.pendingScrollOffset != null) {
            _state.value = _state.value.copy(pendingScrollOffset = null)
        }
    }

    fun setHighlights(highlights: List<Highlight>) {
        _state.value = _state.value.copy(highlights = highlights)
    }

    /**
     * Follows an internal link, which in an EPUB is a path plus an optional fragment.
     * Returns false when the target is outside the book, so the caller can open it externally.
     */
    suspend fun followLink(href: String): Boolean {
        val book = source ?: return false
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
            return false
        }
        val path = href.substringBefore('#')
        val anchor = href.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val chapterIndex = book.chapters.indexOfFirst { it.contentRef == path }
        return if (chapterIndex >= 0) {
            goToChapter(ChapterIndex(chapterIndex), anchor = anchor)
            true
        } else if (anchor != null) {
            // A fragment with no path is a link within the current chapter.
            val offset = _state.value.content?.offsetOfAnchor(anchor)
            if (offset != null) {
                _state.value = _state.value.copy(pendingScrollOffset = offset)
                true
            } else {
                false
            }
        } else {
            false
        }
    }

    /**
     * Searches the whole book, emitting results as it goes.
     *
     * Implemented as a callback rather than a Flow of the complete list because a long PDF takes
     * seconds to search and showing the first hits immediately is the difference between a search
     * that feels instant and one that feels broken.
     */
    suspend fun search(
        query: String,
        maxHits: Int = 400,
        onProgress: suspend (SearchProgress) -> Unit,
    ) {
        val book = source ?: return
        val needle = query.trim()
        if (needle.length < 2) {
            onProgress(SearchProgress(0, 0, emptyList(), isComplete = true))
            return
        }

        val hits = ArrayList<SearchHit>()
        val total = book.chapters.size

        for (index in book.chapters.indices) {
            val chapter = ChapterIndex(index)
            val content = withContext(ioDispatcher) {
                runCatching { book.chapterContent(chapter) }.getOrNull()
            }
            if (content != null) {
                findIn(content, needle, hits, maxHits)
            }
            onProgress(SearchProgress(index + 1, total, hits.toList(), isComplete = index == total - 1))
            if (hits.size >= maxHits) {
                onProgress(SearchProgress(index + 1, total, hits.toList(), isComplete = true))
                return
            }
        }
    }

    private fun findIn(content: ChapterContent, needle: String, into: MutableList<SearchHit>, maxHits: Int) {
        val text = content.plainText
        var from = 0
        while (into.size < maxHits) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) return
            val snippetStart = (at - SNIPPET_CONTEXT).coerceAtLeast(0)
            val snippetEnd = (at + needle.length + SNIPPET_CONTEXT).coerceAtMost(text.length)
            val snippet = text.substring(snippetStart, snippetEnd)
                .replace('\n', ' ')
                .trim()
            // Trimming moves the match, so recompute where it landed inside the snippet.
            val leading = text.substring(snippetStart, at).replace('\n', ' ')
            val offsetInSnippet = (leading.length - (leading.length - leading.trimStart().length))
                .coerceIn(0, snippet.length)
            into.add(
                SearchHit(
                    chapter = content.chapterIndex,
                    chapterTitle = content.title,
                    characterOffset = at,
                    snippet = snippet,
                    matchInSnippet = offsetInSnippet until
                        (offsetInSnippet + needle.length).coerceAtMost(snippet.length),
                ),
            )
            from = at + needle.length
        }
    }

    private suspend fun loadChapter(chapter: ChapterIndex, scrollTo: Int? = null, anchor: String? = null) {
        val book = source ?: return
        _state.value = _state.value.copy(isLoading = true, errorMessage = null)

        val content = withContext(ioDispatcher) {
            runCatching { book.chapterContent(chapter) }
        }

        content.fold(
            onSuccess = { parsed ->
                val offset = when {
                    anchor != null -> parsed.offsetOfAnchor(anchor) ?: 0
                    scrollTo != null -> scrollTo.coerceIn(0, parsed.plainText.length)
                    else -> 0
                }
                _state.value = _state.value.copy(
                    chapter = chapter,
                    content = parsed,
                    isLoading = false,
                    errorMessage = null,
                    pendingScrollOffset = offset,
                    position = ReadingPosition(
                        chapter = chapter,
                        characterOffset = offset,
                        sentenceIndex = -1,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "This chapter could not be opened.",
                )
            },
        )
    }

    /**
     * Parses the chapter on either side in the background.
     *
     * The source's own cache holds them, so turning the page at a chapter boundary is instant.
     * Called by the reader once it has settled, not during a jump, so it never competes with the
     * chapter the user is actually waiting for.
     */
    suspend fun prefetchNeighbours() {
        val book = source ?: return
        val current = _state.value.chapter.value
        withContext(ioDispatcher) {
            listOf(current + 1, current - 1)
                .filter { it in book.chapters.indices }
                .forEach { index -> runCatching { book.chapterContent(ChapterIndex(index)) } }
        }
    }

    /** Closes the book, waiting for any load in flight so the file is not pulled from under it. */
    suspend fun closeBook() = lock.withLock { closeInternal() }

    private fun closeInternal() {
        source?.let { runCatching { it.close() } }
        source = null
        bookId = null
        _state.value = ReaderState()
    }

    /**
     * Releases the file without waiting for the lock, for the case where the whole app is going
     * away and there is nobody left to wait for.
     */
    override fun close() {
        source?.let { runCatching { it.close() } }
        source = null
        bookId = null
    }

    private companion object {
        const val SNIPPET_CONTEXT = 60
    }
}
