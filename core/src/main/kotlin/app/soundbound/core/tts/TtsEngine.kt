package app.soundbound.core.tts

import app.soundbound.core.audio.AudioClip
import app.soundbound.core.model.VoiceId
import kotlinx.coroutines.flow.Flow

/** Which synthesis technology a voice is served by. */
enum class EngineKind(val displayName: String) {
    /** Piper (VITS) models run locally through ONNX Runtime. */
    PIPER("Piper"),

    /** Kokoro models run locally through ONNX Runtime. */
    KOKORO("Kokoro"),

    /** The operating system's own speech service. Always present, no download needed. */
    SYSTEM("Device voice"),
    ;
}

enum class VoiceQuality(val displayName: String, val order: Int) {
    X_LOW("Very low", 0),
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
    ;

    companion object {
        fun parse(value: String?): VoiceQuality = when (value?.lowercase()) {
            "x_low", "x-low", "xlow" -> X_LOW
            "low" -> LOW
            "high" -> HIGH
            else -> MEDIUM
        }
    }
}

enum class VoiceGender { FEMININE, MASCULINE, NEUTRAL, UNSPECIFIED }

/** A voice the user can choose. Installed or not; the catalogue lists both. */
data class TtsVoice(
    val id: VoiceId,
    val displayName: String,
    val engine: EngineKind,
    /** BCP-47 tag, e.g. `en-GB`. */
    val language: String,
    val gender: VoiceGender = VoiceGender.UNSPECIFIED,
    val quality: VoiceQuality = VoiceQuality.MEDIUM,
    val sampleRate: Int = 22_050,
    /** Speaker index for multi-speaker models. */
    val speakerId: Int = 0,
    val description: String? = null,
    val downloadBytes: Long = 0,
    val isInstalled: Boolean = false,
    /** Where the model lives once installed. Null for system voices. */
    val installPath: String? = null,
) {
    val languageTag: String get() = language
    val languageCode: String get() = language.substringBefore('-').lowercase()
    val regionCode: String? get() = language.substringAfter('-', "").takeIf { it.isNotEmpty() }
}

/** How a line should be spoken. All values are multipliers around a neutral 1.0. */
data class SpeechParams(
    /** Playback speed. Applied by the model where it can, otherwise by time-stretching. */
    val rate: Float = 1.0f,
    /** Pitch shift in semitones. */
    val pitchSemitones: Float = 0.0f,
    val volume: Float = 1.0f,
    /**
     * Expressiveness. Piper calls this `noise_scale`; higher values give a more varied,
     * less machine-like delivery at the cost of occasional wobble.
     */
    val expressiveness: Float = 0.667f,
    /** Piper's `noise_w`: variation in phoneme duration, i.e. rhythmic naturalness. */
    val cadenceVariation: Float = 0.8f,
) {
    fun coerced() = copy(
        rate = rate.coerceIn(0.25f, 4.0f),
        pitchSemitones = pitchSemitones.coerceIn(-12f, 12f),
        volume = volume.coerceIn(0f, 2f),
        expressiveness = expressiveness.coerceIn(0f, 1.5f),
        cadenceVariation = cadenceVariation.coerceIn(0f, 2f),
    )
}

/** Thrown when a voice cannot be loaded or a line cannot be synthesised. */
class TtsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A loaded, ready-to-speak voice.
 *
 * Instances hold native resources (an ONNX session, a platform engine handle) and must be
 * closed. They are safe to call from one coroutine at a time; the player serialises access.
 */
interface LoadedVoice : AutoCloseable {
    val voice: TtsVoice
    val sampleRate: Int

    /** Synthesises one utterance. Returns silence for input with nothing speakable in it. */
    suspend fun synthesise(text: String, params: SpeechParams = SpeechParams()): AudioClip

    /**
     * Streams an utterance in pieces where the engine supports it, so playback can begin
     * before the whole line is rendered. The default simply emits the finished clip.
     */
    fun synthesiseStreaming(text: String, params: SpeechParams = SpeechParams()): Flow<AudioClip> =
        kotlinx.coroutines.flow.flow { emit(synthesise(text, params)) }

    /** Warms the model up so the first real sentence is not slower than the rest. */
    suspend fun prime() {
        runCatching { synthesise("Ready.", SpeechParams()) }
    }
}

/** A source of voices. Engines are discovered at start-up and queried lazily. */
interface TtsEngine : AutoCloseable {
    val kind: EngineKind

    /** False when the engine's prerequisites are missing, e.g. no ONNX Runtime on the device. */
    val isAvailable: Boolean

    /** Voices this engine can speak with right now, without any further download. */
    suspend fun installedVoices(): List<TtsVoice>

    suspend fun load(voice: TtsVoice): LoadedVoice

    override fun close() = Unit
}
