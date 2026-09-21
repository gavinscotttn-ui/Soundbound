package app.soundbound.core.book.pdf

import app.soundbound.core.model.BookMetadata

/**
 * One laid-out line of text lifted off a PDF page, with just enough geometry to reconstruct
 * paragraphs. Coordinates are in PDF points with the origin at the top-left of the page, which
 * is the convention PDFBox's text stripper already reports in.
 */
data class PdfTextLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val fontSize: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2f
}

/** A page's worth of extracted lines plus the page box. */
data class PdfPage(
    val number: Int,
    val widthPoints: Float,
    val heightPoints: Float,
    val lines: List<PdfTextLine>,
    /** The publisher's own page label ("xii", "42"), when the document declares one. */
    val label: String? = null,
)

/** An entry from the PDF's outline (what Acrobat calls bookmarks). */
data class PdfOutlineEntry(
    val title: String,
    val pageIndex: Int,
    val depth: Int,
    val children: List<PdfOutlineEntry> = emptyList(),
)

/**
 * The platform-specific half of PDF support.
 *
 * The desktop app backs this with Apache PDFBox and Android backs it with the PDFBox-Android
 * port; the two expose different package names, so the split lives here rather than leaking
 * a platform dependency into the reader. Everything that decides how a PDF *reads* —
 * paragraph reflow, heading detection, running-head removal — sits in [PdfReflow] on this side
 * of the boundary, where it is testable without either library.
 */
interface PdfBackend : AutoCloseable {
    val pageCount: Int
    val metadata: BookMetadata
    fun page(index: Int): PdfPage
    fun outline(): List<PdfOutlineEntry>

    /** Renders a page to PNG bytes at the given width, for the cover and the page-image view. */
    fun renderPage(index: Int, targetWidthPixels: Int): ByteArray? = null

    /** True when the document has no extractable text layer and would need OCR. */
    fun isScanned(): Boolean = false
}

/** Opens a PDF file. Supplied by each platform and handed to [PdfParser]. */
fun interface PdfBackendFactory {
    fun open(localPath: String): PdfBackend
}
