package app.soundbound.core.prefs

import app.soundbound.core.library.LibrarySort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private fun repository(name: String = "settings.json") = SettingsRepository(File(tempDir, name))

    @Test
    fun `defaults are sensible and survive a reload`() {
        val file = File(tempDir, "settings.json")
        val first = SettingsRepository(file)
        assertEquals(AppTheme.FOLLOW_SYSTEM, first.current.theme)
        assertEquals(1.0f, first.current.speech.rate)

        first.updateSpeech { it.copy(rate = 1.4f, speakHeadings = false) }
        first.updateTypography { it.copy(fontSizeSp = 24f) }
        first.updateReader { it.copy(theme = ReaderTheme.SEPIA.name) }
        first.updateLibrary { it.copy(sort = LibrarySort.TITLE.name) }

        val reloaded = SettingsRepository(file)
        assertEquals(1.4f, reloaded.current.speech.rate)
        assertFalse(reloaded.current.speech.speakHeadings)
        assertEquals(24f, reloaded.current.typography.fontSizeSp)
        assertEquals(ReaderTheme.SEPIA, reloaded.current.reader.readerTheme)
        assertEquals(LibrarySort.TITLE, reloaded.current.library.librarySort)
    }

    @Test
    fun `typography values are clamped to something readable`() {
        val settings = repository()
        settings.updateTypography { it.copy(fontSizeSp = 900f, lineHeightMultiplier = 0.1f) }
        assertEquals(48f, settings.current.typography.fontSizeSp)
        assertEquals(1.0f, settings.current.typography.lineHeightMultiplier)

        settings.updateTypography { it.copy(maxLineLengthCharacters = 5) }
        assertEquals(30, settings.current.typography.maxLineLengthCharacters)
    }

    @Test
    fun `speech settings convert into the engine's own option objects`() {
        val settings = repository()
        settings.updateSpeech {
            it.copy(
                rate = 1.75f,
                pitchSemitones = -2f,
                expressiveness = 0.9f,
                expandNumbers = false,
                speakHeadings = false,
                sentencePauseMillis = 5_000,
            )
        }
        val speech = settings.current.speech

        val params = speech.toSpeechParams()
        assertEquals(1.75f, params.rate)
        assertEquals(-2f, params.pitchSemitones)
        assertEquals(0.9f, params.expressiveness)

        assertFalse(speech.toNormalisationOptions().expandNumbers)
        assertFalse(speech.toPlanOptions().speakHeadings)
        // Out-of-range pauses are clamped rather than producing a four-second gap mid-paragraph.
        assertEquals(2_000, speech.toSegmentationOptions().pauseAfterSentenceMillis)
    }

    @Test
    fun `an unreadable settings file falls back to the defaults`() {
        val file = File(tempDir, "broken.json")
        file.writeText("{ this is not JSON")
        val settings = SettingsRepository(file)
        assertEquals(AppTheme.FOLLOW_SYSTEM, settings.current.theme)
        assertEquals(1.0f, settings.current.speech.rate)
    }

    @Test
    fun `an unknown field in the file is ignored rather than rejected`() {
        // A newer version of the app wrote this; an older one must still be able to read it.
        val file = File(tempDir, "future.json")
        file.writeText("""{"version":99,"appTheme":"DARK","somethingNew":{"a":1}}""")
        assertEquals(AppTheme.DARK, SettingsRepository(file).current.theme)
    }

    @Test
    fun `an unrecognised enum value falls back rather than throwing`() {
        val file = File(tempDir, "odd.json")
        file.writeText("""{"appTheme":"NEON","reader":{"theme":"HOLOGRAM"}}""")
        val settings = SettingsRepository(file)
        assertEquals(AppTheme.FOLLOW_SYSTEM, settings.current.theme)
        assertEquals(ReaderTheme.FOLLOW_APP, settings.current.reader.readerTheme)
    }

    @Test
    fun `settings export and import round trip`() {
        val source = repository("a.json")
        source.updateSpeech { it.copy(rate = 1.6f) }
        source.updateTypography { it.copy(fontSizeSp = 26f) }
        source.addPronunciation("Gloucester", "ˈɡlɒstə")

        val json = source.exportJson()
        val target = repository("b.json")
        assertTrue(target.importJson(json))

        assertEquals(1.6f, target.current.speech.rate)
        assertEquals(26f, target.current.typography.fontSizeSp)
        assertEquals("ˈɡlɒstə", target.current.speech.pronunciationOverrides["gloucester"])
    }

    @Test
    fun `importing something that is not settings is refused`() {
        val settings = repository()
        settings.updateSpeech { it.copy(rate = 1.3f) }
        assertFalse(settings.importJson("not json at all"))
        assertEquals(1.3f, settings.current.speech.rate) { "The existing settings must be untouched" }
    }

    @Test
    fun `pronunciation corrections are keyed case-insensitively and survive a reset`() {
        val settings = repository()
        settings.addPronunciation("  Leicester ", "ˈlɛstə")
        assertEquals("ˈlɛstə", settings.current.speech.pronunciationOverrides["leicester"])

        settings.updateSpeech { it.copy(rate = 2f) }
        settings.resetSpeech()

        assertEquals(1f, settings.current.speech.rate) { "Speech settings should be reset" }
        assertEquals("ˈlɛstə", settings.current.speech.pronunciationOverrides["leicester"]) {
            "A user's own corrections are their work, not a setting"
        }

        settings.removePronunciation("LEICESTER")
        assertTrue(settings.current.speech.pronunciationOverrides.isEmpty())
    }

    @Test
    fun `a write that changes nothing does not touch the file`() {
        val file = File(tempDir, "settings.json")
        val settings = SettingsRepository(file)
        settings.updateSpeech { it.copy(rate = 1.2f) }
        val modified = file.lastModified()

        settings.updateSpeech { it.copy(rate = 1.2f) }
        assertEquals(modified, file.lastModified())
    }
}
