package app.soundbound.core.book.pdf

import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.book.InlineSpan
import app.soundbound.core.book.InlineStyle
import kotlin.math.abs

/** Tunables for [PdfReflow]. The defaults suit typeset prose; the presets below adjust them. */
data class ReflowOptions(
    /** A line shorter than this fraction of the text column ends a paragraph. */
    val shortLineRatio: Float = 0.86f,
    /** A vertical gap larger than this multiple of the line height starts a new paragraph. */
    val paragraphGapRatio: Float = 1.55f,
    /** An indent larger than this many points, relative to the column edge, starts a paragraph. */
    val indentThresholdPoints: Float = 6f,
    /** A line this much larger than the body text is treated as a heading. */
    val headingSizeRatio: Float = 1.18f,
    /** Strip lines that repeat in the same place across pages (running heads and folios). */
    val removeRunningHeads: Boolean = true,
    /** Rejoin words broken across a line ending in a hyphen. */
    val dehyphenate: Boolean = true,
    /** Detect and read multi-column pages in the right order. */
    val detectColumns: Boolean = true,
    /** Insert a PAGE_BREAK block at each page boundary. */
    val markPageBreaks: Boolean = true,
) {
    companion object {
        /** Novels, non-fiction, anything justified and single-column. */
        val PROSE = ReflowOptions()

        /** Papers and reports: tighter paragraph detection, columns expected. */
        val ACADEMIC = ReflowOptions(shortLineRatio = 0.92f, paragraphGapRatio = 1.35f)

        /** Slides, forms and anything where each line stands alone. */
        val LINE_BY_LINE = ReflowOptions(
            shortLineRatio = 1.01f,
            paragraphGapRatio = 0.0f,
            detectColumns = false,
        )
    }
}

/** The reflowed result for one run of pages. */
data class ReflowResult(
    val blocks: List<ContentBlock>,
    val plainText: String,
    /** Plain-text offset at which each page starts, keyed by page number. */
    val pageOffsets: Map<Int, Int>,
)

/**
 * Turns the line soup that comes out of a PDF back into paragraphs.
 *
 * A PDF has no idea what a paragraph is: it knows only where each glyph was painted. Reading a
 * PDF aloud line by line is the single most common reason these apps sound wrong — a synthetic
 * voice pausing at the end of every typeset line is unbearable. So we reconstruct the prose
 * from the geometry: column layout, margins, line gaps, indents, hyphenation and running heads
 * are all inferred from the page itself rather than assumed.
 */
object PdfReflow {

    fun reflow(pages: List<PdfPage>, options: ReflowOptions = ReflowOptions.PROSE): ReflowResult {
        if (pages.isEmpty()) return ReflowResult(emptyList(), "", emptyMap())

        val bodyFontSize = estimateBodyFontSize(pages)
        val runningHeads =
            if (options.removeRunningHeads) detectRunningHeads(pages, bodyFontSize) else emptySet()

        val builder = BlockListBuilder()
        val pageOffsets = LinkedHashMap<Int, Int>()

        pages.forEach { page ->
            val kept = page.lines.filter { line ->
                line.text.isNotBlank() && !isRunningHead(line, page, runningHeads, bodyFontSize)
            }
            pageOffsets[page.number] = builder.currentOffset()
            if (kept.isEmpty()) {
                if (options.markPageBreaks) builder.addPageBreak(page.number)
                return@forEach
            }

            val ordered = if (options.detectColumns) orderByColumns(kept, page) else kept.sortedBy { it.top }
            emitParagraphs(ordered, bodyFontSize, options, builder)
            if (options.markPageBreaks) builder.addPageBreak(page.number)
        }

        return ReflowResult(builder.blocks(), builder.text(), pageOffsets)
    }

    // ---------------------------------------------------------------- paragraphs

    private fun emitParagraphs(
        lines: List<PdfTextLine>,
        bodyFontSize: Float,
        options: ReflowOptions,
        builder: BlockListBuilder,
    ) {
        val columnLeft = lines.minOf { it.left }
        val columnRight = lines.maxOf { it.right }
        val columnWidth = (columnRight - columnLeft).coerceAtLeast(1f)

        var current = ArrayList<PdfTextLine>()

        fun flush() {
            if (current.isEmpty()) return
            builder.addParagraph(current, bodyFontSize, options, columnLeft, columnWidth)
            current = ArrayList()
        }

        lines.forEach { line ->
            if (current.isEmpty()) {
                current.add(line)
                return@forEach
            }
            val previous = current.last()
            if (startsNewParagraph(previous, line, bodyFontSize, options, columnLeft, columnRight, columnWidth)) {
                flush()
            }
            current.add(line)
        }
        flush()
    }

    private fun startsNewParagraph(
        previous: PdfTextLine,
        line: PdfTextLine,
        bodyFontSize: Float,
        options: ReflowOptions,
        columnLeft: Float,
        columnRight: Float,
        columnWidth: Float,
    ): Boolean {
        // A change of type size or weight nearly always means a new block (a heading, a pull
        // quote, a caption), so check that before any of the geometric heuristics.
        if (abs(line.fontSize - previous.fontSize) > bodyFontSize * 0.12f) return true
        if (line.bold != previous.bold) return true
        if (isHeading(previous, bodyFontSize, options) || isHeading(line, bodyFontSize, options)) return true
        if (bulletPrefixLength(line.text) > 0) return true

        val gap = line.top - previous.bottom
        val lineHeight = previous.height.coerceAtLeast(bodyFontSize * 0.9f)
        if (options.paragraphGapRatio > 0f && gap > lineHeight * options.paragraphGapRatio) return true

        // A previous line that stopped well short of the right margin ended its paragraph,
        // unless it was the last line before a deliberate indent (handled above).
        val previousFill = (previous.right - columnLeft) / columnWidth
        if (previousFill < options.shortLineRatio) return true

        // A fresh first-line indent.
        if (line.left - columnLeft > options.indentThresholdPoints &&
            previous.left - columnLeft <= options.indentThresholdPoints
        ) {
            return true
        }

        // A centred line among flush-left ones is a heading or a stanza break. Centring is
        // judged by the margins on both sides being roughly equal *and* non-zero — comparing
        // a line's midpoint to the column's would call every last-line-of-paragraph centred,
        // since a short flush-left line's midpoint drifts exactly the same way.
        if (isCentred(line, columnLeft, columnRight, columnWidth) !=
            isCentred(previous, columnLeft, columnRight, columnWidth)
        ) {
            return true
        }

        return false
    }

    private fun isCentred(
        line: PdfTextLine,
        columnLeft: Float,
        columnRight: Float,
        columnWidth: Float,
    ): Boolean {
        val leftMargin = line.left - columnLeft
        val rightMargin = columnRight - line.right
        if (leftMargin < columnWidth * 0.08f || rightMargin < columnWidth * 0.08f) return false
        return abs(leftMargin - rightMargin) < columnWidth * 0.12f
    }

    private fun isHeading(line: PdfTextLine, bodyFontSize: Float, options: ReflowOptions): Boolean {
        if (line.fontSize >= bodyFontSize * options.headingSizeRatio) return true
        if (!line.bold) return false
        val words = line.text.trim().split(Regex("\\s+")).size
        // Bold, short and unpunctuated: a run-in heading rather than an emphasised sentence.
        return words <= 12 && !line.text.trimEnd().endsWith('.')
    }

    // ---------------------------------------------------------------- columns

    /**
     * Splits a page into columns by looking for a vertical gutter: an x-range that no line
     * crosses. Two-column academic PDFs are otherwise read straight across, interleaving two
     * unrelated sentences, which is the other classic way these apps fall over.
     */
    internal fun orderByColumns(lines: List<PdfTextLine>, page: PdfPage): List<PdfTextLine> {
        if (lines.size < 8) return lines.sortedBy { it.top }

        val gutter = findGutter(lines, page) ?: return lines.sortedBy { it.top }
        val (left, right) = lines.partition { it.right <= gutter }
        // Lines that span the gutter (full-width titles) are kept in reading order at the top.
        val spanning = lines.filter { it.left < gutter && it.right > gutter }
        if (left.isEmpty() || right.isEmpty()) return lines.sortedBy { it.top }

        val leftOnly = left.filter { it.right <= gutter }.sortedBy { it.top }
        val rightOnly = right.filter { it.left >= gutter }.sortedBy { it.top }
        return spanning.sortedBy { it.top } + leftOnly + rightOnly
    }

    private fun findGutter(lines: List<PdfTextLine>, page: PdfPage): Float? {
        val textLeft = lines.minOf { it.left }
        val textRight = lines.maxOf { it.right }
        val span = textRight - textLeft
        if (span <= 0f) return null

        // Sample the middle 60% of the text block; a real gutter sits near the centre.
        val buckets = 60
        val occupancy = IntArray(buckets)
        lines.forEach { line ->
            val from = (((line.left - textLeft) / span) * buckets).toInt().coerceIn(0, buckets - 1)
            val to = (((line.right - textLeft) / span) * buckets).toInt().coerceIn(0, buckets - 1)
            for (b in from..to) occupancy[b]++
        }

        var bestStart = -1
        var bestLength = 0
        var runStart = -1
        for (b in (buckets * 0.3f).toInt()..(buckets * 0.7f).toInt()) {
            if (occupancy[b] <= lines.size / 40) {
                if (runStart < 0) runStart = b
                val length = b - runStart + 1
                if (length > bestLength) {
                    bestLength = length
                    bestStart = runStart
                }
            } else {
                runStart = -1
            }
        }
        // Demand a gutter at least 3% of the text width, or we are just seeing ragged margins.
        if (bestLength < buckets * 0.03f) return null
        val centreBucket = bestStart + bestLength / 2f
        return textLeft + (centreBucket / buckets) * span
    }

    // ---------------------------------------------------------------- running heads

    /**
     * A running head is the same text, in the same band of the page, on many pages. We
     * normalise digits to `#` first so that "Page 41" and "Page 42" collapse to one key.
     */
    internal fun detectRunningHeads(pages: List<PdfPage>, bodyFontSize: Float): Set<String> {
        if (pages.size < 3) return emptySet()
        val counts = HashMap<String, Int>()
        pages.forEach { page ->
            page.lines.asSequence()
                .filter { couldBeRunningHead(it, page, bodyFontSize) }
                .map { runningHeadKey(it, page) }
                .distinct()
                .forEach { key -> counts[key] = (counts[key] ?: 0) + 1 }
        }
        val threshold = maxOf(3, (pages.size * 0.5f).toInt())
        return counts.filterValues { it >= threshold }.keys
    }

    /**
     * A running head sits in the outer margin *and* is set no larger than the body text.
     * Without the size test, a chapter opening that repeats near the top of each chapter's
     * first page — "Chapter 1", "Chapter 2" — gets mistaken for furniture and deleted.
     */
    private fun couldBeRunningHead(line: PdfTextLine, page: PdfPage, bodyFontSize: Float): Boolean {
        val margin = page.heightPoints * 0.10f
        val inMargin = line.top < margin || line.bottom > page.heightPoints - margin
        return inMargin && line.fontSize <= bodyFontSize * 1.1f
    }

    private fun isRunningHead(
        line: PdfTextLine,
        page: PdfPage,
        keys: Set<String>,
        bodyFontSize: Float,
    ): Boolean {
        if (keys.isEmpty()) return false
        if (!couldBeRunningHead(line, page, bodyFontSize)) return false
        return runningHeadKey(line, page) in keys
    }

    private fun runningHeadKey(line: PdfTextLine, page: PdfPage): String {
        val band = if (line.top < page.heightPoints / 2f) "top" else "bottom"
        val normalised = line.text.trim().replace(Regex("\\d+"), "#").lowercase()
        return "$band|$normalised"
    }

    // ---------------------------------------------------------------- font metrics

    /**
     * The body size is the character-weighted mode of the line font sizes, rounded to a
     * quarter-point. Using the mode rather than the mean keeps a few huge chapter numbers
     * from dragging the estimate upwards.
     */
    internal fun estimateBodyFontSize(pages: List<PdfPage>): Float {
        val weights = HashMap<Int, Int>()
        pages.forEach { page ->
            page.lines.forEach { line ->
                if (line.text.isBlank()) return@forEach
                val key = (line.fontSize * 4f).toInt()
                weights[key] = (weights[key] ?: 0) + line.text.length
            }
        }
        val best = weights.maxByOrNull { it.value }?.key ?: return 11f
        return (best / 4f).coerceIn(4f, 72f)
    }

    // ---------------------------------------------------------------- text joining

    internal fun bulletPrefixLength(text: String): Int {
        val trimmed = text.trimStart()
        val offset = text.length - trimmed.length
        BULLET_CHARS.forEach { bullet ->
            if (trimmed.startsWith(bullet)) return offset + bullet.length
        }
        NUMBERED_ITEM.matchAt(trimmed, 0)?.let { return offset + it.value.length }
        return 0
    }

    private val BULLET_CHARS = listOf("• ", "◦ ", "▪ ", "· ", "– ", "— ", "- ", "* ")
    private val NUMBERED_ITEM = Regex("""^(\d{1,3}[.)]|[ivxlcIVXLC]{1,6}[.)]|[a-zA-Z][.)])\s+""")

    /** Joins the lines of one paragraph, healing hyphenated word breaks as it goes. */
    internal fun joinLines(lines: List<PdfTextLine>, dehyphenate: Boolean): String {
        val out = StringBuilder()
        lines.forEachIndexed { index, line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@forEachIndexed
            if (out.isEmpty()) {
                out.append(text)
                return@forEachIndexed
            }
            val tail = out.substring(maxOf(0, out.length - 2))
            val endsHyphenated = dehyphenate && HYPHEN_END.containsMatchIn(tail)
            val nextStartsLower = text.firstOrNull()?.isLowerCase() == true
            if (endsHyphenated && nextStartsLower) {
                out.setLength(out.length - 1)
                out.append(text)
            } else {
                out.append(' ').append(text)
            }
        }
        return out.toString()
    }

    private val HYPHEN_END = Regex("""\p{L}[-‐­]$""")

    // ---------------------------------------------------------------- block assembly

    private class BlockListBuilder {
        private val blocks = ArrayList<ContentBlock>()
        private val plain = StringBuilder()

        fun currentOffset(): Int = plain.length

        fun addParagraph(
            lines: List<PdfTextLine>,
            bodyFontSize: Float,
            options: ReflowOptions,
            columnLeft: Float,
            columnWidth: Float,
        ) {
            val first = lines.first()
            val raw = joinLines(lines, options.dehyphenate).trim()
            if (raw.isEmpty()) return

            val bulletLength = bulletPrefixLength(raw)
            val kind = when {
                bulletLength > 0 -> BlockKind.LIST_ITEM
                isHeading(first, bodyFontSize, options) -> headingKindFor(first.fontSize, bodyFontSize)
                else -> BlockKind.PARAGRAPH
            }

            val marker = if (bulletLength > 0) raw.substring(0, bulletLength).trim() else null
            val text = if (bulletLength > 0) raw.substring(bulletLength).trim() else raw
            if (text.isEmpty()) return

            val spans = buildList {
                if (first.bold && !kind.isHeading) add(InlineSpan(0, text.length, setOf(InlineStyle.BOLD)))
                else if (first.italic) add(InlineSpan(0, text.length, setOf(InlineStyle.ITALIC)))
            }

            separate()
            val start = plain.length
            plain.append(text)
            blocks.add(
                ContentBlock(
                    kind = kind,
                    text = text,
                    textStart = start,
                    spans = spans,
                    indentLevel = if (first.left - columnLeft > columnWidth * 0.08f) 1 else 0,
                    listMarker = marker,
                ),
            )
        }

        fun addPageBreak(pageNumber: Int) {
            separate()
            blocks.add(
                ContentBlock(
                    kind = BlockKind.PAGE_BREAK,
                    text = "",
                    textStart = plain.length,
                    anchors = listOf("page-$pageNumber"),
                    isSpeechSkipped = true,
                ),
            )
        }

        private fun separate() {
            if (plain.isNotEmpty() && !plain.endsWith("\n\n")) {
                if (plain.endsWith("\n")) plain.append('\n') else plain.append("\n\n")
            }
        }

        fun blocks(): List<ContentBlock> = blocks
        fun text(): String = plain.toString()
    }

    private fun headingKindFor(size: Float, bodySize: Float): BlockKind = when {
        size >= bodySize * 1.8f -> BlockKind.HEADING_1
        size >= bodySize * 1.45f -> BlockKind.HEADING_2
        size >= bodySize * 1.25f -> BlockKind.HEADING_3
        else -> BlockKind.HEADING_4
    }
}
