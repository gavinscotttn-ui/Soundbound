package app.soundbound.android.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.soundbound.core.book.pdf.PdfBackend
import app.soundbound.core.book.pdf.PdfOutlineEntry
import app.soundbound.core.book.pdf.PdfPage
import app.soundbound.core.book.pdf.PdfTextLine
import app.soundbound.core.model.BookMetadata
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF support on Android, using the PDFBox-Android port for text and the platform's own
 * [PdfRenderer] for page images.
 *
 * The split is deliberate. PDFBox-Android extracts text and geometry, which the platform cannot do
 * at all. `PdfRenderer` rasterises pages, which it does with hardware acceleration and without
 * pulling in PDFBox's rendering stack — a large amount of code that leans on java.awt.
 */
class AndroidPdfBackend private constructor(
    private val document: PDDocument,
    private val file: File,
) : PdfBackend {

    override val pageCount: Int get() = document.numberOfPages

    override val metadata: BookMetadata by lazy {
        val info = document.documentInformation
        BookMetadata(
            title = info?.title?.trim().orEmpty(),
            authors = info?.author?.split(';', '&', ',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.size <= 6 }
                .orEmpty(),
            publisher = info?.producer?.trim()?.takeIf { it.isNotEmpty() },
            description = info?.subject?.trim()?.takeIf { it.isNotEmpty() },
            subjects = info?.keywords?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                .orEmpty(),
        )
    }

    override fun page(index: Int): PdfPage {
        require(index in 0 until pageCount) { "Page $index is outside this document ($pageCount pages)." }
        val pdPage = document.getPage(index)
        val box = pdPage.mediaBox
        val stripper = LineCapturingStripper().apply {
            sortByPosition = true
            startPage = index + 1
            endPage = index + 1
            setSuppressDuplicateOverlappingText(true)
        }
        stripper.getText(document)
        return PdfPage(
            number = index,
            widthPoints = box.width,
            heightPoints = box.height,
            lines = stripper.lines,
        )
    }

    override fun outline(): List<PdfOutlineEntry> {
        val root = runCatching { document.documentCatalog.documentOutline }.getOrNull() ?: return emptyList()
        return collect(root.children().toList(), 0)
    }

    private fun collect(items: List<PDOutlineItem>, depth: Int): List<PdfOutlineEntry> =
        items.mapNotNull { item ->
            val pageIndex = runCatching { item.findDestinationPage(document) }
                .getOrNull()
                ?.let { document.pages.indexOf(it) }
                ?.takeIf { it >= 0 }
                ?: return@mapNotNull null
            PdfOutlineEntry(
                title = runCatching { item.title }.getOrNull()?.trim().orEmpty(),
                pageIndex = pageIndex,
                depth = depth,
                children = if (depth < 8) collect(item.children().toList(), depth + 1) else emptyList(),
            )
        }

    override fun renderPage(index: Int, targetWidthPixels: Int): ByteArray? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (index !in 0 until renderer.pageCount) return null
                renderer.openPage(index).use { page ->
                    val width = targetWidthPixels.coerceIn(120, 2_400)
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer draws only what is on the page, leaving the rest transparent,
                    // so the bitmap is filled white first or the cover comes out black.
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                        bitmap.recycle()
                        out.toByteArray()
                    }
                }
            }
        }
    }.getOrNull()

    override fun isScanned(): Boolean {
        val sample = minOf(pageCount, 5)
        if (sample == 0) return false
        var characters = 0
        for (i in 0 until sample) {
            characters += runCatching { page(i).lines.sumOf { it.text.length } }.getOrDefault(0)
            if (characters > 200) return false
        }
        return true
    }

    override fun close() {
        runCatching { document.close() }
    }

    companion object {
        private var initialised = false

        /**
         * PDFBox-Android loads its font metrics from app assets, and must be pointed at the
         * context before the first document is opened.
         */
        fun install(context: Context) {
            if (initialised) return
            PDFBoxResourceLoader.init(context.applicationContext)
            initialised = true
        }

        fun factory(context: Context) = app.soundbound.core.book.pdf.PdfBackendFactory { path ->
            install(context)
            val file = File(path)
            AndroidPdfBackend(PDDocument.load(file), file)
        }
    }
}

/** Captures one line at a time with its geometry, which is what `PdfReflow` needs. */
private class LineCapturingStripper : PDFTextStripper() {

    val lines = ArrayList<PdfTextLine>()

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        if (text.isBlank() || textPositions.isEmpty()) return

        var left = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var top = Float.MAX_VALUE
        var bottom = Float.MIN_VALUE
        var sizeSum = 0f
        var sizeWeight = 0f
        var bold = 0
        var italic = 0

        textPositions.forEach { position ->
            val x = position.xDirAdj
            val y = position.yDirAdj
            val w = position.widthDirAdj
            val h = position.heightDir
            if (x < left) left = x
            if (x + w > right) right = x + w
            if (y - h < top) top = y - h
            if (y > bottom) bottom = y

            val weight = position.unicode?.length?.toFloat() ?: 1f
            sizeSum += position.fontSizeInPt * weight
            sizeWeight += weight

            val fontName = runCatching { position.font?.name.orEmpty() }.getOrDefault("")
            if (
                fontName.contains("bold", true) || fontName.contains("black", true) ||
                fontName.contains("heavy", true) || fontName.contains("semibold", true)
            ) {
                bold++
            }
            if (fontName.contains("italic", true) || fontName.contains("oblique", true)) italic++
        }

        if (left > right || top > bottom) return
        val half = textPositions.size / 2

        lines.add(
            PdfTextLine(
                text = text.trimEnd(),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                fontSize = if (sizeWeight > 0f) sizeSum / sizeWeight else 11f,
                bold = bold > half,
                italic = italic > half,
            ),
        )
    }
}
