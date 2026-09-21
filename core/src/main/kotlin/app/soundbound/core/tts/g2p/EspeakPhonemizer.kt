package app.soundbound.core.tts.g2p

/**
 * The narrow slice of espeak-ng that Soundbound needs, kept as an interface so that :core
 * stays free of native code. The desktop app binds it with JNA and the Android app with a
 * small JNI shim; both talk to the same libespeak-ng.
 */
interface NativeEspeakBridge {
    /**
     * @param dataPath directory holding `espeak-ng-data`.
     * @return true if the library initialised.
     */
    fun initialise(dataPath: String): Boolean

    /** @param voiceName an espeak-ng voice name such as `en-gb` or `de`. */
    fun setVoice(voiceName: String): Boolean

    /** Phonemises [text] as IPA, including stress marks. */
    fun textToPhonemes(text: String): String

    fun isInitialised(): Boolean

    fun release()
}

/**
 * Phonemizer backed by espeak-ng.
 *
 * This is the preferred path for Piper voices, because Piper's training data was phonemised
 * with exactly this library: matching it means matching the model's expectations phoneme for
 * phoneme, which is audibly better than any approximation. It also brings the other forty-odd
 * languages along for free.
 */
class EspeakPhonemizer(
    private val bridge: NativeEspeakBridge,
    private val dataPath: String,
) : Phonemizer {

    override val id: String = "espeak-ng"

    private var initialised = false
    private var currentVoice: String? = null

    @Synchronized
    private fun ensureReady(language: String): Boolean {
        if (!initialised) {
            initialised = bridge.isInitialised() || bridge.initialise(dataPath)
            if (!initialised) return false
        }
        val voice = espeakVoiceFor(language)
        if (voice != currentVoice) {
            if (!bridge.setVoice(voice)) return false
            currentVoice = voice
        }
        return true
    }

    override fun supports(language: String): Boolean = language.isNotBlank()

    @Synchronized
    override fun phonemise(text: String, language: String): List<PhonemeToken> {
        if (!ensureReady(language)) return emptyList()

        // espeak-ng phonemises a whole utterance at a time, which is what gives it sensible
        // cross-word prosody. We therefore ask it per word only to keep the offset mapping,
        // and per clause where offsets are not needed.
        return TextTokeniser.tokenise(text).map { token ->
            when (token.kind) {
                PhonemeToken.Kind.WORD -> PhonemeToken(
                    source = token.text,
                    phonemes = runCatching { bridge.textToPhonemes(token.text) }
                        .getOrDefault("")
                        .trim(),
                    kind = PhonemeToken.Kind.WORD,
                    sourceStart = token.start,
                )

                else -> PhonemeToken(token.text, "", token.kind, token.start)
            }
        }
    }

    /** Phonemises a whole line in one call, which keeps espeak's own phrase prosody intact. */
    @Synchronized
    fun phonemiseLine(text: String, language: String): String? {
        if (!ensureReady(language)) return null
        return runCatching { bridge.textToPhonemes(text).trim() }.getOrNull()
    }

    fun release() {
        initialised = false
        currentVoice = null
        bridge.release()
    }

    private companion object {
        /** Maps a BCP-47 tag onto an espeak-ng voice name. */
        fun espeakVoiceFor(language: String): String {
            val normalised = language.replace('_', '-').lowercase()
            SPECIAL_CASES[normalised]?.let { return it }
            val parts = normalised.split('-')
            val base = parts.firstOrNull().orEmpty().ifEmpty { "en" }
            val region = parts.getOrNull(1)
            return if (region != null && "$base-$region" in KNOWN_REGIONAL) "$base-$region" else base
        }

        val SPECIAL_CASES = mapOf(
            "en-gb" to "en-gb",
            "en-us" to "en-us",
            "en-029" to "en-029",
            "pt-br" to "pt-br",
            "zh-cn" to "cmn",
            "zh-tw" to "cmn-latn-pinyin",
            "nb-no" to "nb",
            "no" to "nb",
        )

        val KNOWN_REGIONAL = setOf(
            "en-gb", "en-us", "en-029", "pt-br", "fr-be", "fr-ch", "es-419",
            "de-at", "de-ch", "nl-be", "en-gb-scotland", "en-gb-x-gbclan",
        )
    }
}
