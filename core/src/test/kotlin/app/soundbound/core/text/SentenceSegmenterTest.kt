package app.soundbound.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceSegmenterTest {

    private val segmenter = SentenceSegmenter()

    private fun texts(input: String) = segmenter.segment(input).map { it.text }

    @Test
    fun `plain sentences split on terminators`() {
        assertEquals(
            listOf("The cat sat on the mat.", "The dog did not.", "Everyone was content."),
            texts("The cat sat on the mat. The dog did not. Everyone was content."),
        )
    }

    @Test
    fun `titles do not end a sentence`() {
        assertEquals(
            listOf("Mr. Darcy called on Dr. Watson at St. Pancras.", "They spoke briefly."),
            texts("Mr. Darcy called on Dr. Watson at St. Pancras. They spoke briefly."),
        )
    }

    @Test
    fun `initials do not end a sentence`() {
        assertEquals(
            listOf("J. R. R. Tolkien wrote it.", "He was thorough."),
            texts("J. R. R. Tolkien wrote it. He was thorough."),
        )
    }

    @Test
    fun `decimals do not end a sentence`() {
        assertEquals(
            listOf("Pi is roughly 3.14 by most accounts.", "Nobody disputes it."),
            texts("Pi is roughly 3.14 by most accounts. Nobody disputes it."),
        )
    }

    @Test
    fun `dialogue keeps its attribution attached`() {
        assertEquals(
            listOf("\"Stop!\" he said.", "The room fell silent."),
            texts("\"Stop!\" he said. The room fell silent."),
        )
    }

    @Test
    fun `closing quotes belong to the sentence they end`() {
        assertEquals(
            listOf("\"We shall see,\" said Holmes.", "\"Indeed we shall.\""),
            texts("\"We shall see,\" said Holmes. \"Indeed we shall.\""),
        )
    }

    @Test
    fun `a blank line always ends a unit`() {
        val units = texts("A heading\n\nAnd the paragraph beneath it, which runs on")
        assertEquals(listOf("A heading", "And the paragraph beneath it, which runs on"), units)
    }

    @Test
    fun `ellipses are handled without exploding`() {
        assertEquals(
            listOf("He paused... then went on.", "Nothing more was said."),
            texts("He paused... then went on. Nothing more was said."),
        )
    }

    @Test
    fun `offsets address the original text exactly`() {
        val source = "First sentence here. Second one follows. And a third."
        segmenter.segment(source).forEach { sentence ->
            assertEquals(sentence.text, source.substring(sentence.start, sentence.end)) {
                "Offsets ${sentence.start}..${sentence.end} do not match the sentence text"
            }
        }
    }

    @Test
    fun `long sentences are split at clause boundaries`() {
        val clause = "the quick brown fox jumped over the lazy dog and kept on running"
        val long = (1..12).joinToString("; ") { clause } + "."
        val units = segmenter.segment(long)
        assertTrue(units.size > 1) { "Expected the over-long sentence to be split" }
        assertTrue(units.all { it.text.length <= 420 }) {
            "Longest unit was ${units.maxOf { it.text.length }} characters"
        }
        // Reassembling the pieces must give back the original words in order.
        assertEquals(
            long.replace(Regex("\\s+"), " ").trim(),
            units.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun `tiny fragments are merged into their neighbour`() {
        val units = texts("Yes. The whole matter was settled that afternoon.")
        assertEquals(1, units.size)
        assertEquals("Yes. The whole matter was settled that afternoon.", units.first())
    }

    @Test
    fun `paragraph breaks get a longer pause than sentence breaks`() {
        val units = segmenter.segment("One sentence. Another one.\n\nA new paragraph entirely.")
        assertEquals(3, units.size)
        assertTrue(units[1].trailingPauseMillis > units[0].trailingPauseMillis) {
            "Expected a longer pause at the paragraph break"
        }
    }

    @Test
    fun `empty and whitespace input yields nothing`() {
        assertTrue(segmenter.segment("").isEmpty())
        assertTrue(segmenter.segment("   \n\n  ").isEmpty())
    }
}
