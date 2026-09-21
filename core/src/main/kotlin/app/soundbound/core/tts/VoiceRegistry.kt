package app.soundbound.core.tts

import app.soundbound.core.model.VoiceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place the rest of the app asks for a voice.
 *
 * Holds every available engine, merges their voices into one list, and keeps exactly one
 * model loaded at a time. That last part matters: a Piper session is tens of megabytes of
 * resident memory, and keeping several around is the quickest way to be killed by Android's
 * low-memory reaper halfway through a chapter.
 */
class VoiceRegistry(private val engines: List<TtsEngine>) : AutoCloseable {

    private val mutex = Mutex()
    private var loaded: LoadedVoice? = null

    val availableEngines: List<TtsEngine> get() = engines.filter { it.isAvailable }

    /** Every voice that can speak right now, best quality first within each language. */
    suspend fun voices(): List<TtsVoice> = availableEngines
        .flatMap { engine -> runCatching { engine.installedVoices() }.getOrDefault(emptyList()) }
        .sortedWith(
            compareBy(
                { it.language },
                { -it.quality.order },
                { it.engine.ordinal },
                { it.displayName },
            ),
        )

    suspend fun voice(id: VoiceId): TtsVoice? = voices().firstOrNull { it.id == id }

    /**
     * Picks a sensible voice with no help from the user: the best-quality installed voice in
     * the book's language, else in the device's language, else anything at all.
     */
    suspend fun bestVoiceFor(languageTag: String?, deviceLanguageTag: String?): TtsVoice? {
        val all = voices()
        if (all.isEmpty()) return null

        fun bestIn(tag: String?): TtsVoice? {
            if (tag.isNullOrBlank()) return null
            val normalised = tag.replace('_', '-').lowercase()
            val language = normalised.substringBefore('-')
            // An exact regional match is worth a lot: en-GB rather than en-US for a British book.
            return all.firstOrNull { it.language.replace('_', '-').lowercase() == normalised }
                ?: all.firstOrNull { it.languageCode == language }
        }

        return bestIn(languageTag) ?: bestIn(deviceLanguageTag) ?: all.first()
    }

    /**
     * Loads [voice], closing whatever was loaded before. Returns the previously loaded voice
     * unchanged when it is already the one asked for, so switching sentences costs nothing.
     */
    suspend fun load(voice: TtsVoice): LoadedVoice = mutex.withLock {
        loaded?.let { current ->
            if (current.voice.id == voice.id) return@withLock current
            runCatching { current.close() }
            loaded = null
        }
        val engine = engines.firstOrNull { it.kind == voice.engine && it.isAvailable }
            ?: throw TtsException("The engine for \"${voice.displayName}\" is not available on this device.")
        val result = engine.load(voice)
        loaded = result
        result
    }

    suspend fun unload() = mutex.withLock {
        loaded?.let { runCatching { it.close() } }
        loaded = null
    }

    override fun close() {
        loaded?.let { runCatching { it.close() } }
        loaded = null
        engines.forEach { runCatching { it.close() } }
    }
}
