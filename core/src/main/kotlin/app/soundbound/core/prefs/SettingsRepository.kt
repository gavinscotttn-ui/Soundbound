package app.soundbound.core.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Holds [Settings] and writes them out when they change.
 *
 * Reads are synchronous against an in-memory value because the reader consults the typography
 * settings on every frame. Writes are atomic, and a settings file that cannot be parsed falls
 * back to the defaults rather than preventing the app from starting.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class SettingsRepository(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        write(updated)
    }

    fun updateTypography(transform: (TypographySettings) -> TypographySettings) =
        update { it.copy(typography = transform(it.typography).coerced()) }

    fun updateSpeech(transform: (SpeechSettings) -> SpeechSettings) =
        update { it.copy(speech = transform(it.speech)) }

    fun updateReader(transform: (ReaderSettings) -> ReaderSettings) =
        update { it.copy(reader = transform(it.reader)) }

    fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings) =
        update { it.copy(library = transform(it.library)) }

    fun resetTypography() = update { it.copy(typography = TypographySettings()) }

    fun resetSpeech() = update {
        // A user's own pronunciation corrections are their work, not a setting, so a reset
        // leaves them alone.
        it.copy(speech = SpeechSettings(pronunciationOverrides = it.speech.pronunciationOverrides))
    }

    fun addPronunciation(word: String, phonemes: String) = updateSpeech { speech ->
        speech.copy(
            pronunciationOverrides = speech.pronunciationOverrides +
                (word.trim().lowercase() to phonemes.trim()),
        )
    }

    fun removePronunciation(word: String) = updateSpeech { speech ->
        speech.copy(pronunciationOverrides = speech.pronunciationOverrides - word.trim().lowercase())
    }

    /** Exports the settings as JSON, for backup or for moving to another device. */
    fun exportJson(): String = json.encodeToString(Settings.serializer(), _settings.value)

    /** Imports settings from JSON. Returns false if the text was not valid settings. */
    fun importJson(text: String): Boolean {
        val parsed = runCatching { json.decodeFromString(Settings.serializer(), text) }.getOrNull()
            ?: return false
        _settings.value = parsed
        write(parsed)
        return true
    }

    private fun read(): Settings {
        if (!file.isFile) return Settings()
        return runCatching { json.decodeFromString(Settings.serializer(), file.readText()) }
            .getOrDefault(Settings())
    }

    private fun write(value: Settings) {
        runCatching {
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.encodeToString(Settings.serializer(), value))
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }
    }
}
