package app.soundbound.core.book.pdf

import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.book.BookParseException
import app.soundbound.core.book.BookParser
import app.soundbound.core.book.BookSource
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import okio.Buffer
import okio.Source

/**
 * Opens PDFs. The heavy lifting is delegated to a platform [PdfBackend]; this class decides how
 * the document is *divided* — which is the thing a PDF refuses to tell you.
 *
 * Where the document has an outline, each top-level outline entry becomes a chapter, which is
 * what a reader expects. Where it has none, pages are grouped into fixed-size runs so that
 * navigation, resume and background loading all still work on a 900-page scan.
 */
class PdfParser(private val backendFactory: PdfBackendFactory) : BookParser {
    override val format: BookFormat = BookFormat.PDF

    override fun canOpen(fileName: String, header: ByteArray): Boolean {
        if (header.size >= 5) {
            val magic = String(header, 0, 5, Charsets.US_ASCII)
            if (magic == "%PDF-") return true
            // Some files carry junk before the header; PDF readers tolerate up to 1 KB of it.
            val prefix = String(header, 0, minOf(header.size, 1024), Charsets.ISO_8859_1)
            if (prefix.contains("%PDF-")) return true
        }
        return fileName.endsWith(".pdf", ignoreCase = true)
    }

    override fun open(file: BookFileHandle): BookSource {
        val path = file.localPath()
            ?: throw BookParseException("This PDF must be copied to local storage before it can be opened.")
        val backend = try {
            backendFactory.open(path)
        } catch (e: Exception) {
            throw BookParseException("This PDF could not be opened. It may be damaged or password-protected.", e)
        }
        return PdfBookSource(backend, file.displayName)
    }
}

class PdfBookSource internal constructor(
    private val backend: PdfBackend,
    displayName: String,
    private val options: ReflowOptions = ReflowOptions.PROSE,
    /** Pages per chapter when the document has no outline to divide it by. */
    private val pagesPerChapter: Int = 12,
) : BookSource {

    override val format: BookFormat = BookFormat.PDF

    override val metadata: BookMetadata = backend.metadata.let { meta ->
        if (meta.title.isNotBlank()) meta else meta.copy(title = displayName.substringBeforeLast('.'))
    }

    private val outline: List<PdfOutlineEntry> = runCatching { backend.outline() }.getOrDefault(emptyList())

    /** Page ranges, one per chapter, always covering every page exactly once and in order. */
    private val chapterRanges: List<IntRange> = buildChapterRanges()

    private val chapterTitles: List<String?> = buildChapterTitles()

    override val chapters: List<Chapter> = chapterRanges.mapIndexed { index, range ->
        Chapter(
            index = ChapterIndex(index),
            title = chapterTitles.getOrNull(index),
            contentRef = "pages:${range.first}-${range.last}",
            // ~1,800 characters is a fair average for a typeset page; only used for progress
            // weighting before a chapter has actually been read.
            approximateCharacters = (range.last - range.first + 1) * 1_800,
        )
    }

    override val toc: List<TocEntry> = buildToc()

    private val cache = app.soundbound.core.book.epub.ChapterCache(maxEntries = 4)

    private fun buildChapterRanges(): List<IntRange> {
        val pageCount = backend.pageCount
        if (pageCount <= 0) throw BookParseException("This PDF contains no pages.")

        val starts = outline
            .flatMap { flattenOutline(it) }
            .filter { it.depth == 0 }
            .map { it.pageIndex }
            .filter { it in 0 until pageCount }
            .distinct()
            .sorted()

        if (starts.size < 2 || starts.size > pageCount / 2) {
            // Either no usable outline, or one so fine-grained that each "chapter" is a
            // paragraph. Fall back to fixed runs of pages.
            return (0 until pageCount step pagesPerChapter).map { start ->
                start until minOf(start + pagesPerChapter, pageCount)
            }.map { it.first..it.last }
        }

        val boundaries = if (starts.first() == 0) starts else listOf(0) + starts
        return boundaries.mapIndexed { index, start ->
            val end = boundaries.getOrNull(index + 1)?.minus(1) ?: (pageCount - 1)
            start..maxOf(start, end)
        }.filter { !it.isEmpty() }
    }

    private fun buildChapterTitles(): List<String?> {
        if (outline.isEmpty()) {
            return chapterRanges.map { range ->
                if (chapterRanges.size == 1) null else "Pages ${range.first + 1}–${range.last + 1}"
            }
        }
        val byStartPage = outline.flatMap { flattenOutline(it) }
            .filter { it.depth == 0 }
            .associateBy { it.pageIndex }
        return chapterRanges.map { range -> byStartPage[range.first]?.title }
    }

    private fun buildToc(): List<TocEntry> {
        if (outline.isEmpty()) {
            return chapters.map { TocEntry(it.title ?: "Pages ${it.index.value + 1}", it.index) }
        }
        return outline.map { convertOutline(it) }
    }

    private fun convertOutline(entry: PdfOutlineEntry): TocEntry = TocEntry(
        title = entry.title.ifBlank { "Untitled section" },
        chapter = ChapterIndex(chapterIndexForPage(entry.pageIndex)),
        fragment = "page-${entry.pageIndex}",
        children = entry.children.map(::convertOutline),
        depth = entry.depth,
    )

    private fun flattenOutline(entry: PdfOutlineEntry): List<PdfOutlineEntry> =
        listOf(entry) + entry.children.flatMap(::flattenOutline)

    private fun chapterIndexForPage(pageIndex: Int): Int {
        val index = chapterRanges.indexOfFirst { pageIndex in it }
        return if (index >= 0) index else chapterRanges.lastIndex.coerceAtLeast(0)
    }

    override fun chapterContent(index: ChapterIndex): ChapterContent {
        cache.get(index)?.let { return it }
        val range = chapterRanges.getOrNull(index.value)
            ?: throw BookParseException("Chapter ${index.value} is outside this document.")

        val pages = range.map { pageIndex ->
            runCatching { backend.page(pageIndex) }.getOrElse {
                PdfPage(pageIndex, 595f, 842f, emptyList())
            }
        }
        val result = PdfReflow.reflow(pages, options)
        val content = ChapterContent(
            chapterIndex = index,
            title = chapterTitles.getOrNull(index.value)
                ?: result.blocks.firstOrNull { it.kind.isHeading }?.text,
            blocks = result.blocks,
            plainText = result.plainText,
        )
        cache.put(index, content)
        return content
    }

    /** Page image for the page-view mode, addressed as `page:<index>@<width>`. */
    override fun readResource(ref: String): Source? {
        val match = PAGE_IMAGE.matchEntire(ref) ?: return null
        val pageIndex = match.groupValues[1].toIntOrNull() ?: return null
        val width = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 1080
        val bytes = backend.renderPage(pageIndex, width) ?: return null
        return Buffer().write(bytes)
    }

    override fun coverImage(): ByteArray? = backend.renderPage(0, 600)

    /** True when there is no text layer at all, so read-aloud needs OCR first. */
    fun isScannedDocument(): Boolean = backend.isScanned()

    val pageCount: Int get() = backend.pageCount

    fun pageRangeOf(chapter: ChapterIndex): IntRange? = chapterRanges.getOrNull(chapter.value)

    override fun close() {
        cache.clear()
        runCatching { backend.close() }
    }

    private companion object {
        val PAGE_IMAGE = Regex("""page:(\d+)(?:@(\d+))?""")
    }
}
