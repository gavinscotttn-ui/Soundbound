package app.soundbound.core.book.pdf

import app.soundbound.core.book.BlockKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfReflowTest {

    /** Lays out lines down a page at a fixed leading, like a typeset column. */
    private fun page(
        number: Int = 0,
        lines: List<Triple<String, Float, Float>>,
        width: Float = 500f,
        height: Float = 700f,
        leading: Float = 14f,
        top: Float = 60f,
        left: Float = 50f,
    ): PdfPage {
        val textLines = lines.mapIndexed { index, (text, fontSize, right) ->
            val y = top + index * leading
            PdfTextLine(
                text = text,
                left = left,
                top = y,
                right = right,
                bottom = y + fontSize,
                fontSize = fontSize,
            )
        }
        return PdfPage(number, width, height, textLines)
    }

    @Test
    fun `justified lines are rejoined into one paragraph`() {
        // Four full-measure lines then one short one: a single paragraph.
        val result = PdfReflow.reflow(
            listOf(
                page(
                    lines = listOf(
                        Triple("It is a truth universally acknowledged, that a", 11f, 450f),
                        Triple("single man in possession of a good fortune,", 11f, 450f),
                        Triple("must be in want of a wife.", 11f, 240f),
                    ),
                ),
            ),
        )
        val paragraphs = result.blocks.filter { it.kind == BlockKind.PARAGRAPH }
        assertEquals(1, paragraphs.size) { "Expected one paragraph, got ${paragraphs.map { it.text }}" }
        assertEquals(
            "It is a truth universally acknowledged, that a single man in possession " +
                "of a good fortune, must be in want of a wife.",
            paragraphs.single().text,
        )
    }

    @Test
    fun `a short line ends the paragraph`() {
        val result = PdfReflow.reflow(
            listOf(
                page(
                    lines = listOf(
                        Triple("The first paragraph runs the full measure of the", 11f, 450f),
                        Triple("column and then stops here.", 11f, 250f),
                        Triple("The second paragraph begins on a new line and", 11f, 450f),
                        Triple("also stops short.", 11f, 180f),
                    ),
                ),
            ),
        )
        val paragraphs = result.blocks.filter { it.kind == BlockKind.PARAGRAPH }
        assertEquals(2, paragraphs.size) { paragraphs.map { it.text }.toString() }
        assertTrue(paragraphs[1].text.startsWith("The second paragraph"))
    }

    @Test
    fun `hyphenated word breaks are healed`() {
        val result = PdfReflow.reflow(
            listOf(
                page(
                    lines = listOf(
                        Triple("The extraordinary and quite unrepeat-", 11f, 450f),
                        Triple("able events of that afternoon in Norwich.", 11f, 300f),
                    ),
                ),
            ),
        )
        val text = result.blocks.first { it.kind == BlockKind.PARAGRAPH }.text
        assertTrue(text.contains("unrepeatable")) { "Got: $text" }
        assertFalse(text.contains("unrepeat-"))
    }

    @Test
    fun `larger type becomes a heading`() {
        val result = PdfReflow.reflow(
            listOf(
                page(
                    lines = listOf(
                        Triple("Chapter Four", 20f, 200f),
                        Triple("The body text begins here and runs to the", 11f, 450f),
                        Triple("end of the measure before stopping.", 11f, 260f),
                    ),
                ),
            ),
        )
        assertEquals(BlockKind.HEADING_1, result.blocks.first().kind)
        assertEquals("Chapter Four", result.blocks.first().text)
    }

    @Test
    fun `running heads and folios are removed`() {
        val pages = (0 until 6).map { index ->
            PdfPage(
                number = index,
                widthPoints = 500f,
                heightPoints = 700f,
                lines = listOf(
                    PdfTextLine("A HISTORY OF NORFOLK", 50f, 20f, 300f, 30f, 9f),
                    PdfTextLine("Body text on page ${index + 1} of the book here.", 50f, 300f, 450f, 311f, 11f),
                    PdfTextLine("${index + 12}", 260f, 670f, 275f, 680f, 9f),
                ),
            )
        }
        val result = PdfReflow.reflow(pages)
        assertFalse(result.plainText.contains("A HISTORY OF NORFOLK")) {
            "The running head should have been stripped:\n${result.plainText}"
        }
        assertTrue(result.plainText.contains("Body text on page 1"))
        assertTrue(result.plainText.contains("Body text on page 6"))
    }

    @Test
    fun `two column pages are read one column at a time`() {
        // Left column at x 50..240, right column at x 280..470, with an empty gutter between.
        val lines = ArrayList<PdfTextLine>()
        repeat(10) { row ->
            val y = 60f + row * 14f
            lines += PdfTextLine("left line $row of the first column", 50f, y, 240f, y + 11f, 11f)
            lines += PdfTextLine("right line $row of the second column", 280f, y, 470f, y + 11f, 11f)
        }
        val page = PdfPage(0, 520f, 700f, lines)
        val ordered = PdfReflow.orderByColumns(lines, page)

        val leftIndices = ordered.withIndex().filter { it.value.text.startsWith("left") }.map { it.index }
        val rightIndices = ordered.withIndex().filter { it.value.text.startsWith("right") }.map { it.index }
        assertTrue(leftIndices.max() < rightIndices.min()) {
            "Columns were interleaved: " + ordered.map { it.text.take(5) }
        }
    }

    @Test
    fun `bullet lists are recognised`() {
        val result = PdfReflow.reflow(
            listOf(
                page(
                    lines = listOf(
                        Triple("• The first item in a list", 11f, 260f),
                        Triple("• The second item in a list", 11f, 265f),
                    ),
                ),
            ),
        )
        val items = result.blocks.filter { it.kind == BlockKind.LIST_ITEM }
        assertEquals(2, items.size)
        assertEquals("The first item in a list", items[0].text)
        assertEquals("•", items[0].listMarker)
    }

    @Test
    fun `page breaks are recorded with offsets`() {
        val result = PdfReflow.reflow(
            listOf(
                page(number = 0, lines = listOf(Triple("Page one text here.", 11f, 250f))),
                page(number = 1, lines = listOf(Triple("Page two text here.", 11f, 250f))),
            ),
        )
        assertEquals(setOf(0, 1), result.pageOffsets.keys)
        assertTrue(result.pageOffsets[1]!! > result.pageOffsets[0]!!)
        val breaks = result.blocks.filter { it.kind == BlockKind.PAGE_BREAK }
        assertEquals(2, breaks.size)
        assertTrue(breaks.all { it.isSpeechSkipped })
    }

    @Test
    fun `the body font size is the weighted mode not the mean`() {
        val pages = listOf(
            page(
                lines = listOf(
                    Triple("An Enormous Title", 36f, 400f),
                    Triple("a long line of ordinary body text here", 11f, 450f),
                    Triple("another long line of ordinary body text", 11f, 450f),
                    Triple("and a third long line of ordinary text", 11f, 450f),
                ),
            ),
        )
        assertEquals(11f, PdfReflow.estimateBodyFontSize(pages))
    }

    @Test
    fun `block offsets always match the plain text`() {
        val result = PdfReflow.reflow(
            listOf(
                page(
                    lines = listOf(
                        Triple("A Heading Of Some Size", 18f, 300f),
                        Triple("Body text that runs the full width of the", 11f, 450f),
                        Triple("column before ending here.", 11f, 240f),
                        Triple("• A bullet item", 11f, 200f),
                    ),
                ),
            ),
        )
        result.blocks.forEach { block ->
            assertEquals(block.text, result.plainText.substring(block.textStart, block.textEnd)) {
                "Block ${block.kind} is misaligned"
            }
        }
    }

    @Test
    fun `an empty document reflows to nothing`() {
        val result = PdfReflow.reflow(emptyList())
        assertTrue(result.blocks.isEmpty())
        assertEquals("", result.plainText)
    }
}
