package app.soundbound.core.tts

import app.soundbound.core.tts.onnx.KokoroModelConfig
import app.soundbound.core.tts.onnx.KokoroStyleTable
import app.soundbound.core.tts.onnx.KokoroTokeniser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KokoroTest {

    private val configJson = """
        {
          "sample_rate": 24000,
          "n_token": 510,
          "style_dim": 256,
          "language": "en-us",
          "vocab": {
            "$": 0, ";": 1, ":": 2, ",": 3, ".": 4, "!": 5, "?": 6,
            " ": 16,
            "h": 50, "ə": 51, "l": 52, "o": 53, "ʊ": 54, "ˈ": 55,
            "tʃ": 60
          }
        }
    """.trimIndent()

    private val config = KokoroModelConfig.parse(configJson)

    @Test
    fun `the vocabulary and shape come from the pack's own configuration`() {
        assertEquals(24_000, config.sampleRate)
        assertEquals(510, config.maxTokens)
        assertEquals(256, config.styleDimensions)
        assertEquals("en-us", config.languageTag)
        assertEquals(50, config.idOf("h"))
        assertEquals(2, config.longestSymbol) { "The two-character symbol must be noticed" }
    }

    @Test
    fun `the vocabulary is found wherever the release put it`() {
        val nested = KokoroModelConfig.parse("""{"model":{"vocab":{"a":7}}}""")
        assertEquals(7, nested.idOf("a"))

        val alternative = KokoroModelConfig.parse("""{"token_to_id":{"b":9}}""")
        assertEquals(9, alternative.idOf("b"))
    }

    @Test
    fun `a configuration with no vocabulary is refused with a readable message`() {
        val error = runCatching { KokoroModelConfig.parse("""{"sample_rate":24000}""") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("vocabulary"))
    }

    @Test
    fun `a Piper configuration is not mistaken for a Kokoro one`() {
        assertTrue(KokoroModelConfig.looksLikeKokoro(configJson))
        assertFalse(
            KokoroModelConfig.looksLikeKokoro("""{"phoneme_id_map":{"_":[0],"a":[1]}}"""),
        ) { "A Piper config has a phoneme_id_map and must be left to the Piper engine" }
        assertFalse(KokoroModelConfig.looksLikeKokoro("not json"))
    }

    @Test
    fun `tokens are padded at both ends and not interleaved`() {
        // Unlike Piper, Kokoro takes a flat sequence with a single pad either side.
        assertEquals(
            listOf(0, 50, 51, 52, 51, 54, 0),
            KokoroTokeniser.toIds("hələʊ", config).toList(),
        )
    }

    @Test
    fun `multi-character symbols win over single characters`() {
        assertEquals(listOf(0, 60, 0), KokoroTokeniser.toIds("tʃ", config).toList())
    }

    @Test
    fun `punctuation reaches the model, because it is what makes the pauses`() {
        val ids = KokoroTokeniser.toIds("hə, lo.", config).toList()
        assertTrue(3 in ids) { "The comma should be tokenised" }
        assertTrue(4 in ids) { "The full stop should be tokenised" }
        assertTrue(16 in ids) { "The space should be tokenised" }
    }

    @Test
    fun `unknown symbols are dropped rather than substituted`() {
        assertEquals(listOf(0, 50, 53, 0), KokoroTokeniser.toIds("h§§o", config).toList())
    }

    @Test
    fun `the sequence is capped so the style table always has a row for it`() {
        val long = "h".repeat(2_000)
        val ids = KokoroTokeniser.toIds(long, config)
        assertTrue(ids.size <= config.maxTokens) { "Produced ${ids.size} tokens" }
        assertEquals(0, ids.first())
        assertEquals(0, ids.last())
    }

    @Test
    fun `coverage reports how much of the input the vocabulary understands`() {
        assertEquals(1f, KokoroTokeniser.coverage("hələʊ", config))
        assertTrue(KokoroTokeniser.coverage("hə§§o", config) < 1f)
        assertEquals(1f, KokoroTokeniser.coverage("", config))
    }

    // ---------------------------------------------------------------- style table

    /** Builds a style table whose first value in each row is the row's own index. */
    private fun styleBytes(rows: Int, dimensions: Int): ByteArray {
        val buffer = ByteBuffer.allocate(rows * dimensions * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (row in 0 until rows) {
            buffer.putFloat(row.toFloat())
            repeat(dimensions - 1) { buffer.putFloat(0.5f) }
        }
        return buffer.array()
    }

    @Test
    fun `the style vector is chosen by the length of the utterance`() {
        val table = KokoroStyleTable.load(styleBytes(rows = 32, dimensions = 8), dimensions = 8)
        assertEquals(32, table.rows)
        assertEquals(8, table.dimensions)

        // Using one vector for every length, or the wrong row, gives speech that is intelligible
        // but flat — a fault that does not look like a bug, so it is pinned down here.
        assertEquals(0f, table.styleFor(0)[0])
        assertEquals(7f, table.styleFor(7)[0])
        assertEquals(31f, table.styleFor(31)[0])
        assertEquals(0.5f, table.styleFor(7)[1])
    }

    @Test
    fun `an utterance longer than the table clamps to its last row`() {
        val table = KokoroStyleTable.load(styleBytes(rows = 16, dimensions = 4), dimensions = 4)
        assertEquals(15f, table.styleFor(9_999)[0])
        assertEquals(0f, table.styleFor(-5)[0]) { "A negative length must not read out of bounds" }
    }

    @Test
    fun `the row count is derived from the file rather than assumed`() {
        val table = KokoroStyleTable.load(styleBytes(rows = 510, dimensions = 256), dimensions = 256)
        assertEquals(510, table.rows)
        assertEquals(256, table.styleFor(1).size)
    }

    @Test
    fun `a truncated or mis-shaped voice file is refused`() {
        val tooShort = runCatching { KokoroStyleTable.load(ByteArray(16), dimensions = 256) }
        assertTrue(tooShort.isFailure)
        assertTrue(tooShort.exceptionOrNull()!!.message!!.contains("fewer"))

        val ragged = runCatching {
            KokoroStyleTable.load(styleBytes(rows = 4, dimensions = 8) + ByteArray(4), dimensions = 8)
        }
        assertTrue(ragged.isFailure)
        assertTrue(ragged.exceptionOrNull()!!.message!!.contains("whole number"))
    }

    @Test
    fun `floats are read little-endian, as the packs are written`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(1.25f)
        buffer.putFloat(-2.5f)
        val table = KokoroStyleTable.load(buffer.array(), dimensions = 2)
        assertEquals(1.25f, table.styleFor(0)[0])
        assertEquals(-2.5f, table.styleFor(0)[1])
    }
}
