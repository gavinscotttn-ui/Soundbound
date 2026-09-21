package app.soundbound.core.player

import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.book.InlineSpan
import app.soundbound.core.model.ChapterIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechPlanTest {

    private fun chapter(vararg blocks: ContentBlock): ChapterContent {
        val plain = StringBuilder()
        val placed = blocks.map { block ->
            if (plain.isNotEmpty()) plain.append("\n\n")
            val start = plain.length
            plain.append(block.text)
            block.copy(textStart = start)
        }
        return ChapterContent(ChapterIndex(0), "A Chapter", placed, plain.toString())
    }

    @Test
    fun `each sentence becomes one unit addressed to the chapter text`() {
        val content = chapter(
            ContentBlock(BlockKind.HEADING_1, "Chapter One", 0),
            ContentBlock(BlockKind.PARAGRAPH, "First sentence. Second sentence here.", 0),
        )
        val plan = SpeechPlanBuilder().build(content)

        assertEquals(3, plan.units.size)
        plan.units.forEach { unit ->
            assertEquals(
                unit.displayText,
                content.plainText.substring(unit.sourceStart, unit.sourceEnd),
            ) { "Unit ${unit.index} does not address its own text" }
        }
    }

    @Test
    fun `text is normalised for speech but the display text is untouched`() {
        val content = chapter(ContentBlock(BlockKind.PARAGRAPH, "It cost £12.50 in 1984.", 0))
        val unit = SpeechPlanBuilder().build(content).units.single()
        assertEquals("It cost £12.50 in 1984.", unit.displayText)
        assertTrue(unit.spokenText.contains("twelve pounds fifty")) { unit.spokenText }
        assertTrue(unit.spokenText.contains("nineteen eighty-four")) { unit.spokenText }
    }

    @Test
    fun `speech-skipped blocks are left out`() {
        val content = chapter(
            ContentBlock(BlockKind.PAGE_BREAK, "", 0, isSpeechSkipped = true),
            ContentBlock(BlockKind.PARAGRAPH, "Real prose.", 0),
            ContentBlock(BlockKind.SEPARATOR, "", 0, isSpeechSkipped = true),
        )
        val plan = SpeechPlanBuilder().build(content)
        assertEquals(1, plan.units.size)
        assertEquals("Real prose.", plan.units.single().displayText)
    }

    @Test
    fun `headings can be silenced`() {
        val content = chapter(
            ContentBlock(BlockKind.HEADING_1, "Chapter One", 0),
            ContentBlock(BlockKind.PARAGRAPH, "Prose.", 0),
        )
        val quiet = SpeechPlanBuilder(SpeechPlanOptions(speakHeadings = false)).build(content)
        assertEquals(1, quiet.units.size)
        assertFalse(quiet.units.any { it.blockKind.isHeading })
    }

    @Test
    fun `headings get a longer pause after them`() {
        val content = chapter(
            ContentBlock(BlockKind.HEADING_1, "Chapter One", 0),
            ContentBlock(BlockKind.PARAGRAPH, "Prose here. More prose.", 0),
        )
        val plan = SpeechPlanBuilder().build(content)
        val heading = plan.units.first { it.blockKind.isHeading }
        val prose = plan.units.first { it.blockKind == BlockKind.PARAGRAPH }
        assertTrue(heading.trailingPauseMillis > prose.trailingPauseMillis)
    }

    @Test
    fun `image alt text is read when asked for`() {
        val content = chapter(
            ContentBlock(
                BlockKind.IMAGE, "", 0,
                imageRef = "pic.png", imageAlt = "A map of Norfolk", isSpeechSkipped = false,
            ),
        )
        val plan = SpeechPlanBuilder().build(content)
        assertEquals("A map of Norfolk", plan.units.single().displayText)

        val silent = SpeechPlanBuilder(SpeechPlanOptions(speakImageDescriptions = false)).build(content)
        assertTrue(silent.isEmpty)
    }

    @Test
    fun `footnotes can be announced after the paragraph that cites them`() {
        val content = ChapterContent(
            chapterIndex = ChapterIndex(0),
            title = null,
            blocks = listOf(
                ContentBlock(
                    BlockKind.PARAGRAPH, "The battle was lost that morning.", 0,
                    spans = listOf(InlineSpan(3, 9, noteRef = "fn1")),
                ),
            ),
            plainText = "The battle was lost that morning.",
            notes = mapOf("fn1" to "Some say it was won."),
        )

        val quiet = SpeechPlanBuilder().build(content)
        assertEquals(1, quiet.units.size)

        val verbose = SpeechPlanBuilder(SpeechPlanOptions(announceFootnotes = true)).build(content)
        assertEquals(2, verbose.units.size)
        assertEquals(BlockKind.FOOTNOTE, verbose.units[1].blockKind)
        assertTrue(verbose.units[1].spokenText.startsWith("Note:"))
    }

    @Test
    fun `a position can be looked up by character offset`() {
        val content = chapter(
            ContentBlock(BlockKind.PARAGRAPH, "One sentence here. Two sentences here.", 0),
            ContentBlock(BlockKind.PARAGRAPH, "A second paragraph.", 0),
        )
        val plan = SpeechPlanBuilder().build(content)
        val second = plan.units[1]
        val found = plan.unitAtOffset(second.sourceStart + 3)
        assertEquals(second.index, found?.index)

        // An offset before everything lands on the first unit; beyond, on the last.
        assertEquals(0, plan.unitAtOffset(0)?.index)
        assertEquals(plan.units.lastIndex, plan.unitAtOffset(10_000)?.index)
    }

    @Test
    fun `word ranges map back into the original text`() {
        val content = chapter(ContentBlock(BlockKind.PARAGRAPH, "He paid twelve pounds.", 0))
        val unit = SpeechPlanBuilder().build(content).units.single()
        val words = unit.spokenWordRanges()
        assertEquals(4, words.size)

        val range = unit.sourceRangeOf(words[1].first, words[1].last - words[1].first + 1)
        assertEquals("paid", content.plainText.substring(range.first, range.last + 1))
    }
}
