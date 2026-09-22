package app.soundbound.core.voices

import app.soundbound.core.tts.VoiceGender
import app.soundbound.core.tts.VoiceQuality
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceCatalogTest {

    /** A faithful excerpt of the published Piper voices index, including its awkward parts. */
    private val index = """
        {
          "en_GB-alba-medium": {
            "key": "en_GB-alba-medium",
            "name": "alba",
            "language": {
              "code": "en_GB", "family": "en", "region": "GB",
              "name_native": "English", "name_english": "English",
              "country_english": "United Kingdom"
            },
            "quality": "medium",
            "num_speakers": 1,
            "speaker_id_map": {},
            "files": {
              "en/en_GB/alba/medium/en_GB-alba-medium.onnx": {
                "size_bytes": 63201294, "md5_digest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              },
              "en/en_GB/alba/medium/en_GB-alba-medium.onnx.json": {
                "size_bytes": 4959, "md5_digest": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              }
            },
            "aliases": []
          },
          "en_GB-northern_english_male-medium": {
            "key": "en_GB-northern_english_male-medium",
            "name": "northern_english_male",
            "language": {
              "code": "en_GB", "family": "en", "region": "GB",
              "name_english": "English", "country_english": "United Kingdom"
            },
            "quality": "medium",
            "num_speakers": 1,
            "files": {
              "en/en_GB/northern_english_male/medium/en_GB-northern_english_male-medium.onnx": {
                "size_bytes": 63201294
              },
              "en/en_GB/northern_english_male/medium/en_GB-northern_english_male-medium.onnx.json": {
                "size_bytes": 4959
              }
            }
          },
          "de_DE-thorsten-high": {
            "key": "de_DE-thorsten-high",
            "name": "thorsten",
            "language": {
              "code": "de_DE", "family": "de", "region": "DE",
              "name_english": "German", "country_english": "Germany"
            },
            "quality": "high",
            "num_speakers": 1,
            "files": {
              "de/de_DE/thorsten/high/de_DE-thorsten-high.onnx": { "size_bytes": 113000000 },
              "de/de_DE/thorsten/high/de_DE-thorsten-high.onnx.json": { "size_bytes": 5000 }
            }
          },
          "en_US-libritts-high": {
            "key": "en_US-libritts-high",
            "name": "libritts",
            "language": { "code": "en_US", "family": "en", "region": "US",
                          "name_english": "English", "country_english": "United States" },
            "quality": "high",
            "num_speakers": 904,
            "speaker_id_map": { "p001": 0, "p002": 1 },
            "files": {
              "en/en_US/libritts/high/en_US-libritts-high.onnx": { "size_bytes": 120000000 },
              "en/en_US/libritts/high/en_US-libritts-high.onnx.json": { "size_bytes": 20000 }
            }
          },
          "broken-entry": {
            "key": "broken-entry",
            "name": "broken",
            "quality": "medium",
            "files": {
              "somewhere/broken.onnx": { "size_bytes": 1 }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `the published index is parsed`() {
        val voices = VoiceCatalog.parse(index)
        // The entry with no configuration file is dropped: it could not be installed.
        assertEquals(4, voices.size) { voices.map { it.key }.toString() }
        assertFalse(voices.any { it.key == "broken-entry" })
    }

    @Test
    fun `a voice carries everything the store needs to show`() {
        val alba = VoiceCatalog.parse(index).first { it.key == "en_GB-alba-medium" }
        assertEquals("Alba", alba.displayName)
        assertEquals("en-GB", alba.languageTag)
        assertEquals("English, United Kingdom", alba.localeDescription)
        assertEquals(VoiceQuality.MEDIUM, alba.quality)
        assertEquals(63_201_294L + 4_959L, alba.totalBytes)
        assertTrue(alba.isComplete)
        assertNotNull(alba.modelFile)
        assertNotNull(alba.configFile)
        assertEquals("en_GB-alba-medium.onnx", alba.modelFile!!.fileName)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", alba.modelFile!!.md5)
    }

    @Test
    fun `underscored dataset names become readable`() {
        val northern = VoiceCatalog.parse(index)
            .first { it.key == "en_GB-northern_english_male-medium" }
        assertEquals("Northern English Male", northern.displayName)
        assertEquals(VoiceGender.MASCULINE, northern.gender)
    }

    @Test
    fun `multi-speaker models report their speaker count`() {
        val libritts = VoiceCatalog.parse(index).first { it.key == "en_US-libritts-high" }
        assertEquals(904, libritts.numSpeakers)
        assertEquals(listOf("p001", "p002"), libritts.speakerNames)
    }

    @Test
    fun `voices are grouped by language for the store`() {
        val grouped = VoiceCatalog.parse(index).groupedByLanguage()
        assertEquals(
            listOf("English, United Kingdom", "English, United States", "German, Germany"),
            grouped.keys.toList(),
        )
        assertEquals(2, grouped["English, United Kingdom"]!!.size)

        val summary = VoiceCatalog.parse(index).languageSummary()
        assertTrue(summary.contains("German, Germany" to 1))
    }

    @Test
    fun `higher quality sorts first within a language`() {
        val extra = index.replace(
            "\"de_DE-thorsten-high\": {",
            """
            "de_DE-thorsten-low": {
              "key": "de_DE-thorsten-low", "name": "thorsten", "quality": "low",
              "language": { "code": "de_DE", "name_english": "German", "country_english": "Germany" },
              "files": {
                "de/x.onnx": { "size_bytes": 1 }, "de/x.onnx.json": { "size_bytes": 1 }
              }
            },
            "de_DE-thorsten-high": {
            """.trimIndent(),
        )
        val german = VoiceCatalog.parse(extra).groupedByLanguage()["German, Germany"]!!
        assertEquals(VoiceQuality.HIGH, german.first().quality)
    }

    @Test
    fun `a file's role is worked out from its name`() {
        val model = CatalogFile("a/b/voice.onnx", 1, null)
        val config = CatalogFile("a/b/voice.onnx.json", 1, null)
        assertTrue(model.isModel)
        assertFalse(model.isConfig)
        assertTrue(config.isConfig)
        // ".onnx.json" ends with ".json", not ".onnx" — a naive check gets this backwards.
        assertFalse(config.isModel)
    }

    @Test
    fun `nonsense input yields no voices rather than throwing`() {
        assertTrue(VoiceCatalog.parse("{}").isEmpty())
        assertTrue(runCatching { VoiceCatalog.parse("""{"x": 5}""") }.getOrDefault(emptyList()).isEmpty())
    }

    @Test
    fun `download URLs are built from the index rather than guessed`() {
        val alba = VoiceCatalog.parse(index).first { it.key == "en_GB-alba-medium" }
        val url = VoiceCatalog.PIPER_BASE_URL.trimEnd('/') + "/" + alba.modelFile!!.path
        assertEquals(
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/" +
                "en/en_GB/alba/medium/en_GB-alba-medium.onnx",
            url,
        )
    }
}
