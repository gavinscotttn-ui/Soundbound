package app.soundbound.android.tts

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.soundbound.core.model.VoiceId
import app.soundbound.core.tts.EngineKind
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.tts.VoiceGender
import app.soundbound.core.tts.VoiceQuality
import app.soundbound.core.tts.system.SystemTtsBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * The device's own speech engine.
 *
 * On a recent Samsung handset this is Samsung's or Google's neural voice — genuinely good, already
 * installed, and available the moment the app is opened. That makes it the right thing to speak
 * with while the user is still deciding which Piper voices to fetch, and the right fallback for a
 * language no installed Piper model covers.
 *
 * Audio is rendered to a WAV file with `synthesizeToFile` rather than spoken directly, so it goes
 * through the same pipeline as every other voice: the same speed control, the same sentence
 * highlighting, the same export to MP3.
 */
class AndroidSystemTts(private val context: Context) : SystemTtsBridge {

    private var engine: TextToSpeech? = null
    private var ready = false
    private val counter = AtomicLong(0)
    private val cacheDirectory: File by lazy {
        File(context.cacheDir, "tts").apply { mkdirs() }
    }

    override val isAvailable: Boolean get() = ready

    /**
     * The platform engine applies its own rate, but only up to about 4x and with its own curve.
     * Soundbound asks for 1.0 and applies the rate itself, so that the speed control behaves
     * identically whichever voice is selected.
     */
    override val appliesRateNatively: Boolean get() = false

    suspend fun initialise(): Boolean = suspendCancellableCoroutine { continuation ->
        var settled = false
        val created = TextToSpeech(context) { status ->
            if (settled) return@TextToSpeech
            settled = true
            ready = status == TextToSpeech.SUCCESS
            continuation.resume(ready)
        }
        engine = created
        continuation.invokeOnCancellation {
            runCatching { created.shutdown() }
            engine = null
            ready = false
        }
    }

    override suspend fun voices(): List<TtsVoice> {
        val current = engine ?: return emptyList()
        if (!ready) return emptyList()

        val voices = runCatching { current.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) {
            // Some engines report no voices but do support locales; offer those instead of nothing.
            return runCatching { current.availableLanguages }.getOrNull().orEmpty().map { locale ->
                TtsVoice(
                    id = VoiceId("system/locale:${locale.toLanguageTag()}"),
                    displayName = locale.displayName,
                    engine = EngineKind.SYSTEM,
                    language = locale.toLanguageTag(),
                    quality = VoiceQuality.MEDIUM,
                    sampleRate = DEFAULT_SAMPLE_RATE,
                    isInstalled = true,
                )
            }
        }

        return voices
            // A network voice is not offline, and offering one would break the app's whole promise.
            .filterNot { it.isNetworkConnectionRequired }
            .map(::describe)
            .sortedWith(compareBy({ it.language }, { -it.quality.order }, { it.displayName }))
    }

    private fun describe(voice: Voice): TtsVoice {
        val locale = voice.locale ?: Locale.UK
        return TtsVoice(
            id = VoiceId("system/${voice.name}"),
            displayName = prettyName(voice, locale),
            engine = EngineKind.SYSTEM,
            language = locale.toLanguageTag(),
            gender = guessGender(voice.name),
            quality = when {
                voice.quality >= Voice.QUALITY_VERY_HIGH -> VoiceQuality.HIGH
                voice.quality >= Voice.QUALITY_HIGH -> VoiceQuality.HIGH
                voice.quality >= Voice.QUALITY_NORMAL -> VoiceQuality.MEDIUM
                else -> VoiceQuality.LOW
            },
            sampleRate = DEFAULT_SAMPLE_RATE,
            description = voice.name,
            isInstalled = true,
        )
    }

    /**
     * Engine voice names are machine-readable and unpleasant — "en-gb-x-gba-local". This turns them
     * into something a person can choose between.
     */
    private fun prettyName(voice: Voice, locale: Locale): String {
        val suffix = voice.name
            .removePrefix(locale.toLanguageTag().lowercase())
            .trim('-', '_', '#')
            .replace("-local", "")
            .replace("-network", "")
            .replace('-', ' ')
            .trim()
        val base = locale.displayName
        return if (suffix.isEmpty()) "$base voice" else "$base · $suffix"
    }

    private fun guessGender(name: String): VoiceGender {
        val lower = name.lowercase()
        return when {
            lower.contains("female") || lower.endsWith("-f") -> VoiceGender.FEMININE
            lower.contains("male") || lower.endsWith("-m") -> VoiceGender.MASCULINE
            else -> VoiceGender.UNSPECIFIED
        }
    }

    override suspend fun synthesiseToWav(
        text: String,
        voice: TtsVoice,
        params: SpeechParams,
    ): ByteArray? {
        val current = engine ?: return null
        if (!ready || text.isBlank()) return null

        applyVoice(current, voice)
        current.setSpeechRate(1.0f)
        current.setPitch(1.0f)

        val file = File(cacheDirectory, "utterance-${counter.incrementAndGet()}.wav")
        val utteranceId = file.name

        val completed = suspendCancellableCoroutine { continuation ->
            current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(true)
                }

                @Deprecated("Superseded by onError(String, int)", ReplaceWith("onError(id, errorCode)"))
                override fun onError(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(false)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(false)
                }
            })

            val result = current.synthesizeToFile(text, Bundle(), file, utteranceId)
            if (result != TextToSpeech.SUCCESS && continuation.isActive) {
                continuation.resume(false)
            }

            continuation.invokeOnCancellation {
                runCatching { current.stop() }
                file.delete()
            }
        }

        if (!completed || !file.isFile || file.length() < 64) {
            file.delete()
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        return bytes
    }

    private fun applyVoice(engine: TextToSpeech, voice: TtsVoice) {
        val raw = voice.id.value.removePrefix("system/")
        if (raw.startsWith("locale:")) {
            val tag = raw.removePrefix("locale:")
            runCatching { engine.language = Locale.forLanguageTag(tag) }
            return
        }
        val match = runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == raw }
        if (match != null) {
            runCatching { engine.voice = match }
        } else {
            runCatching { engine.language = Locale.forLanguageTag(voice.language) }
        }
    }

    override fun release() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        runCatching { cacheDirectory.listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        /**
         * Android's engines do not report their output rate before synthesising. 22.05 kHz is what
         * the Google and Samsung engines actually produce, and in any case the real rate is read
         * from the WAV header that comes back, so this is only the figure shown in the voice list.
         */
        const val DEFAULT_SAMPLE_RATE = 22_050
    }
}
