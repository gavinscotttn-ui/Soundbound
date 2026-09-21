package app.soundbound.core.tts.system

import app.soundbound.core.audio.AudioClip
import app.soundbound.core.audio.Dsp
import app.soundbound.core.audio.WavCodec
import app.soundbound.core.tts.EngineKind
import app.soundbound.core.tts.LoadedVoice
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.TtsEngine
import app.soundbound.core.tts.TtsException
import app.soundbound.core.tts.TtsVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The platform's own speech service, wrapped so it looks like any other engine.
 *
 * Every platform can already speak: Android has `TextToSpeech` (and on a recent Samsung
 * handset that means Google's or Samsung's neural voices, which are excellent), macOS has
 * `AVSpeechSynthesizer`, Windows has SAPI. None of them needs a download, so this is what a
 * brand-new install speaks with while the user is still deciding which Piper voices to fetch.
 *
 * All three can render to a WAV file, so the bridge returns bytes rather than playing
 * directly. That keeps one audio path through the app: the same mixing, the same speed
 * control, the same sentence highlighting.
 */
interface SystemTtsBridge {
    /** False when the platform service failed to initialise. */
    val isAvailable: Boolean

    suspend fun voices(): List<TtsVoice>

    /**
     * Renders [text] to a WAV file's bytes.
     *
     * @return null if the platform declined, which callers treat as "skip this line" rather
     *   than as a fatal error — a single awkward line should never stop an audiobook.
     */
    suspend fun synthesiseToWav(
        text: String,
        voice: TtsVoice,
        params: SpeechParams,
    ): ByteArray?

    /** True when the platform applies [SpeechParams.rate] itself, so we must not do it twice. */
    val appliesRateNatively: Boolean get() = true

    fun release() = Unit
}

class SystemTtsEngine(private val bridge: SystemTtsBridge) : TtsEngine {

    override val kind: EngineKind = EngineKind.SYSTEM

    override val isAvailable: Boolean get() = bridge.isAvailable

    override suspend fun installedVoices(): List<TtsVoice> =
        if (!bridge.isAvailable) emptyList() else runCatching { bridge.voices() }.getOrDefault(emptyList())

    override suspend fun load(voice: TtsVoice): LoadedVoice {
        if (!bridge.isAvailable) {
            throw TtsException("This device's speech service is unavailable.")
        }
        return SystemVoice(voice, bridge)
    }

    override fun close() = bridge.release()
}

private class SystemVoice(
    override val voice: TtsVoice,
    private val bridge: SystemTtsBridge,
) : LoadedVoice {

    override val sampleRate: Int get() = voice.sampleRate

    override suspend fun synthesise(text: String, params: SpeechParams): AudioClip =
        withContext(Dispatchers.IO) {
            val safe = params.coerced()
            val wav = bridge.synthesiseToWav(text, voice, safe) ?: return@withContext AudioClip.empty(sampleRate)
            var clip = runCatching { WavCodec.decode(wav) }
                .getOrElse { throw TtsException("This device's speech service returned audio Soundbound could not read.", it) }

            if (!bridge.appliesRateNatively && safe.rate != 1f) {
                clip = Dsp.changeSpeed(clip, safe.rate)
            }
            if (safe.pitchSemitones != 0f) clip = Dsp.changePitch(clip, safe.pitchSemitones)
            clip = Dsp.applyEdgeFades(Dsp.trimSilence(clip))
            if (safe.volume != 1f) clip = Dsp.applyGain(clip, safe.volume)
            clip
        }

    override fun close() = Unit
}
