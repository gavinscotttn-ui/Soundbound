package app.soundbound.pdfjvm

import app.soundbound.core.book.pdf.PdfBackend
import app.soundbound.core.book.pdf.PdfBackendFactory
import app.soundbound.core.book.pdf.PdfOutlineEntry
import app.soundbound.core.book.pdf.PdfPage
import app.soundbound.core.book.pdf.PdfTextLine
import app.soundbound.core.model.BookMetadata
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Apache PDFBox implementation of [PdfBackend], for the macOS and Windows builds. */
class PdfBoxBackend(private val document: PDDocument) : PdfBackend {

    override val pageCount: Int get() = document.numberOfPages

    override val metadata: BookMetadata by lazy {
        val info = document.documentInformation
        BookMetadata(
            title = info?.title?.trim().orEmpty(),
            authors = info?.author?.let(::splitAuthors).orEmpty(),
            publisher = info?.producer?.trim()?.takeIf { it.isNotEmpty() },
            subjects = info?.keywords?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            description = info?.subject?.trim()?.takeIf { it.isNotEmpty() },
            publishedDate = info?.creationDate?.let { calendar ->
                "%04d-%02d-%02d".format(
                    calendar.get(java.util.Calendar.YEAR),
                    calendar.get(java.util.Calendar.MONTH) + 1,
                    calendar.get(java.util.Calendar.DAY_OF_MONTH),
                )
            },
        )
    }

    private val pageLabels: Map<Int, String> by lazy {
        runCatching {
            document.documentCatalog.pageLabels?.labelsByPageIndices
                ?.withIndex()
                ?.associate { (index, label) -> index to label }
                .orEmpty()
        }.getOrDefault(emptyMap())
    }

    override fun page(index: Int): PdfPage {
        require(index in 0 until pageCount) { "Page $index is outside this document ($pageCount pages)." }
        val pdPage = document.getPage(index)
        val box = pdPage.mediaBox
        val stripper = LineCapturingStripper().apply {
            sortByPosition = true
            startPage = index + 1
            endPage = index + 1
            // Paragraph detection happens in PdfReflow; we only need PDFBox to give us
            // faithful lines, so its own heuristics are dialled right down.
            setSuppressDuplicateOverlappingText(true)
        }
        stripper.getText(document)
        return PdfPage(
            number = index,
            widthPoints = box.width,
            heightPoints = box.height,
            lines = stripper.lines,
            label = pageLabels[index],
        )
    }

    override fun outline(): List<PdfOutlineEntry> {
        val root = runCatching { document.documentCatalog.documentOutline }.getOrNull() ?: return emptyList()
        return collectOutline(root.children().toList(), depth = 0)
    }

    private fun collectOutline(items: List<PDOutlineItem>, depth: Int): List<PdfOutlineEntry> =
        items.mapNotNull { item ->
            val pageIndex = runCatching { item.findDestinationPage(document) }
                .getOrNull()
                ?.let { page -> document.pages.indexOf(page) }
                ?.takeIf { it >= 0 }
                ?: return@mapNotNull null
            val title = runCatching { item.title }.getOrNull()?.trim().orEmpty()
            PdfOutlineEntry(
                title = title,
                pageIndex = pageIndex,
                depth = depth,
                // 8 levels is already more than any sane document; the guard stops a
                // maliciously cyclic outline from spinning forever.
                children = if (depth < 8) collectOutline(item.children().toList(), depth + 1) else emptyList(),
            )
        }

    override fun renderPage(index: Int, targetWidthPixels: Int): ByteArray? = runCatching {
        val page = document.getPage(index)
        val widthPoints = page.mediaBox.width.takeIf { it > 0 } ?: return null
        val dpi = (targetWidthPixels / widthPoints) * 72f
        val image: BufferedImage = PDFRenderer(document)
            .renderImageWithDPI(index, dpi.coerceIn(36f, 400f), ImageType.RGB)
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }.getOrNull()

    override fun isScanned(): Boolean {
        // Sample the first few pages: a scan yields essentially no extractable characters.
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

    private fun splitAuthors(raw: String): List<String> =
        raw.split(';', '&', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.size <= 6 }
            ?: listOf(raw.trim())

    companion object : PdfBackendFactory {
        override fun open(localPath: String): PdfBackend =
            PdfBoxBackend(Loader.loadPDF(File(localPath)))

        /** Opens a password-protected document. */
        fun open(localPath: String, password: String): PdfBackend =
            PdfBoxBackend(Loader.loadPDF(File(localPath), password))
    }
}

/**
 * Captures one [PdfTextLine] per visual line, with the geometry and font metrics that
 * `PdfReflow` needs. PDFBox's own `getText` throws all of that away.
 */
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
        var boldCount = 0
        var italicCount = 0

        textPositions.forEach { position ->
            val x = position.xDirAdj
            val y = position.yDirAdj
            val w = position.widthDirAdj
            val h = position.heightDir
            if (x < left) left = x
            if (x + w > right) right = x + w
            // yDirAdj is the baseline measured from the top of the page, so the glyph box
            // runs from (y - height) to y.
            if (y - h < top) top = y - h
            if (y > bottom) bottom = y

            val weight = position.unicode?.length?.toFloat() ?: 1f
            sizeSum += position.fontSizeInPt * weight
            sizeWeight += weight

            val fontName = runCatching { position.font?.name.orEmpty() }.getOrDefault("")
            if (fontName.containsIgnoreCase("bold") || fontName.containsIgnoreCase("black") ||
                fontName.containsIgnoreCase("heavy") || fontName.containsIgnoreCase("semibold")
            ) {
                boldCount++
            }
            if (fontName.containsIgnoreCase("italic") || fontName.containsIgnoreCase("oblique")) {
                italicCount++
            }
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
                bold = boldCount > half,
                italic = italicCount > half,
            ),
        )
    }
}

private fun String.containsIgnoreCase(needle: String): Boolean = contains(needle, ignoreCase = true)
