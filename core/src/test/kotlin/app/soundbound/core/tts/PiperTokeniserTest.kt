package app.soundbound.core.tts

import app.soundbound.core.tts.onnx.PiperModelConfig
import app.soundbound.core.tts.onnx.PiperTokeniser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PiperTokeniserTest {

    /** A cut-down version of a real Piper `.onnx.json`, including its quirks. */
    private val configJson = """
        {
          "audio": { "sample_rate": 22050, "quality": "high" },
          "espeak": { "voice": "en-gb" },
          "inference": { "noise_scale": 0.667, "length_scale": 1.0, "noise_w": 0.8 },
          "phoneme_type": "espeak",
          "phoneme_id_map": {
            "_": [0], "^": [1], "$": [2], " ": [3], ",": [4], ".": [5],
            "h": [10], "ə": [11], "l": [12], "o": [13], "ʊ": [14], "ˈ": [15],
            "tʃ": [20]
          },
          "num_speakers": 1,
          "speaker_id_map": {},
          "language": { "code": "en_GB", "family": "en", "region": "GB",
                        "name_english": "English", "country_english": "United Kingdom" },
          "dataset": "alba"
        }
    """.trimIndent()

    private val config = PiperModelConfig.parse(configJson)

    @Test
    fun `configuration is parsed including the nested language object`() {
        assertEquals(22_050, config.sampleRate)
        assertEquals(VoiceQuality.HIGH, config.quality)
        assertEquals("en-gb", config.espeakVoice)
        assertEquals("en-GB", config.languageTag)
        assertEquals(0.667f, config.noiseScale)
        assertEquals(1, config.numSpeakers)
        assertEquals("alba", config.datasetName)
        assertEquals(2, config.longestPhonemeKey)
    }

    @Test
    fun `a language given as a bare string is also accepted`() {
        val parsed = PiperModelConfig.parse(
            """{"language":"de_DE","phoneme_id_map":{"_":[0],"a":[1]}}""",
        )
        assertEquals("de-DE", parsed.languageTag)
    }

    @Test
    fun `phoneme id values given as bare integers are accepted`() {
        val parsed = PiperModelConfig.parse("""{"phoneme_id_map":{"_":0,"^":1,"a":7}}""")
        assertEquals(intArrayOf(7).toList(), parsed.idOf("a")!!.toList())
    }

    @Test
    fun `the id sequence is wrapped and interleaved with padding`() {
        // "hələʊ" -> BOS, pad, then each phoneme followed by pad, then EOS.
        val ids = PiperTokeniser.toIds("hələʊ", config).toList()
        assertEquals(
            listOf(1, 0, 10, 0, 11, 0, 12, 0, 11, 0, 14, 0, 2),
            ids,
        )
    }

    @Test
    fun `multi-character IPA symbols win over single characters`() {
        // tʃ is mapped as one symbol (20); it must not be read as t + ʃ.
        val ids = PiperTokeniser.toIds("tʃ", config).toList()
        assertEquals(listOf(1, 0, 20, 0, 2), ids)
    }

    @Test
    fun `punctuation is carried through because it is what makes the pauses`() {
        val ids = PiperTokeniser.toIds("hə, lo.", config).toList()
        assertTrue(ids.contains(4)) { "The comma should reach the model" }
        assertTrue(ids.contains(5)) { "The full stop should reach the model" }
        assertTrue(ids.contains(3)) { "The word space should reach the model" }
    }

    @Test
    fun `unmapped characters are dropped rather than substituted`() {
        val ids = PiperTokeniser.toIds("h§§o", config).toList()
        assertEquals(listOf(1, 0, 10, 0, 13, 0, 2), ids)
    }

    @Test
    fun `coverage reports how much of the input the model understands`() {
        assertEquals(1f, PiperTokeniser.coverage("hələʊ", config))
        assertTrue(PiperTokeniser.coverage("hə§§o", config) < 1f)
        assertEquals(1f, PiperTokeniser.coverage("", config))
    }

    @Test
    fun `an empty phoneme map is rejected with a readable message`() {
        val error = runCatching { PiperModelConfig.parse("""{"audio":{"sample_rate":16000}}""") }
            .exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("phoneme_id_map"))
    }

    @Test
    fun `speaker maps are read for multi-speaker models`() {
        val parsed = PiperModelConfig.parse(
            """
            {
              "num_speakers": 3,
              "speaker_id_map": { "p225": 0, "p226": 1, "p227": 2 },
              "phoneme_id_map": { "_": [0], "a": [1] }
            }
            """.trimIndent(),
        )
        assertTrue(parsed.isMultiSpeaker)
        assertEquals(3, parsed.numSpeakers)
        assertEquals(1, parsed.speakerIdMap["p226"])
    }
}
