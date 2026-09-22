package app.soundbound.core.tts.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.soundbound.core.audio.AudioClip
import app.soundbound.core.audio.Dsp
import app.soundbound.core.model.VoiceId
import app.soundbound.core.tts.EngineKind
import app.soundbound.core.tts.LoadedVoice
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.TtsEngine
import app.soundbound.core.tts.TtsException
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.tts.VoiceGender
import app.soundbound.core.tts.VoiceQuality
import app.soundbound.core.tts.g2p.EspeakPhonemizer
import app.soundbound.core.tts.g2p.LexiconPhonemizer
import app.soundbound.core.tts.g2p.Phonemizer
import app.soundbound.core.tts.g2p.PhonemeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** One Kokoro pack on disk: a model, its configuration, and a folder of speaker style tables. */
data class KokoroPack(
    val directory: File,
    val modelFile: File,
    val configFile: File,
    val voiceFiles: List<File>,
) {
    val key: String get() = directory.name
}

/**
 * Kokoro voices, run locally through ONNX Runtime.
 *
 * Worth having alongside Piper for one reason: it sounds more like a person. Piper remains the
 * breadth — hundreds of voices across forty-odd languages — and Kokoro is the one to reach for
 * when a book is going to be listened to for six hours.
 *
 * A pack is recognised by its shape rather than its name: a configuration carrying a vocabulary
 * but no `phoneme_id_map`, beside a folder of speaker files. That keeps Piper and Kokoro packs
 * from being confused for one another in the same voices folder.
 */
class KokoroTtsEngine(
    private val store: VoiceStore,
    private val phonemizerProvider: () -> Phonemizer = { LexiconPhonemizer() },
    private val threadCount: Int = PiperTtsEngine.defaultThreadCount(),
) : TtsEngine {

    override val kind: EngineKind = EngineKind.KOKORO

    private val environment: OrtEnvironment? by lazy {
        runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    }

    override val isAvailable: Boolean get() = environment != null

    /** Finds Kokoro packs under the voices folder. */
    fun discoverPacks(): List<KokoroPack> {
        val root = store.root
        if (!root.isDirectory) return emptyList()

        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { directory ->
                val model = directory.listFiles().orEmpty()
                    .filter { it.name.endsWith(".onnx") }
                    // Prefer the full-precision model when a pack ships several quantisations.
                    .minByOrNull { it.name.length }
                    ?: return@mapNotNull null
                val config = listOf("config.json", model.name + ".json", "tokenizer.json")
                    .map { File(directory, it) }
                    .firstOrNull { it.isFile }
                    ?: return@mapNotNull null
                if (!KokoroModelConfig.looksLikeKokoro(runCatching { config.readText() }.getOrDefault(""))) {
                    return@mapNotNull null
                }
                val voicesDirectory = listOf("voices", "speakers")
                    .map { File(directory, it) }
                    .firstOrNull { it.isDirectory }
                    ?: return@mapNotNull null
                val voiceFiles = voicesDirectory.listFiles().orEmpty()
                    .filter { it.isFile && (it.name.endsWith(".bin") || it.name.endsWith(".npy")) }
                    .sortedBy { it.name }
                if (voiceFiles.isEmpty()) return@mapNotNull null

                KokoroPack(directory, model, config, voiceFiles)
            }
    }

    override suspend fun installedVoices(): List<TtsVoice> = withContext(Dispatchers.IO) {
        discoverPacks().flatMap { pack ->
            val config = runCatching { KokoroModelConfig.parse(pack.configFile.readText()) }.getOrNull()
                ?: return@flatMap emptyList()
            pack.voiceFiles.map { file -> describe(pack, config, file) }
        }
    }

    private fun describe(pack: KokoroPack, config: KokoroModelConfig, voiceFile: File): TtsVoice {
        val speaker = voiceFile.name.substringBeforeLast('.')
        return TtsVoice(
            id = VoiceId("kokoro/${pack.key}#$speaker"),
            displayName = prettyName(speaker),
            engine = EngineKind.KOKORO,
            language = languageOf(speaker, config),
            gender = genderOf(speaker),
            // Kokoro packs ship one quality; calling it high is accurate rather than flattering.
            quality = VoiceQuality.HIGH,
            sampleRate = config.sampleRate,
            description = pack.key,
            downloadBytes = pack.modelFile.length() + voiceFile.length(),
            isInstalled = true,
            installPath = voiceFile.absolutePath,
        )
    }

    /**
     * Speaker files follow a convention: a two-letter language, a gender letter, then a name —
     * `af_heart` is American English, female, "Heart". Decoding it gives a list a person can
     * actually choose from rather than forty filenames.
     */
    private fun prettyName(speaker: String): String {
        val name = speaker.substringAfter('_', speaker)
            .split('_', '-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        return name.ifBlank { speaker }
    }

    private fun languageOf(speaker: String, config: KokoroModelConfig): String {
        val prefix = speaker.substringBefore('_', "").lowercase()
        return LANGUAGE_PREFIXES[prefix.take(1)]?.let { language ->
            REGION_PREFIXES[prefix.take(1)]?.let { "$language-$it" } ?: language
        } ?: config.languageTag
    }

    private fun genderOf(speaker: String): VoiceGender =
        when (speaker.substringBefore('_', "").lowercase().getOrNull(1)) {
            'f' -> VoiceGender.FEMININE
            'm' -> VoiceGender.MASCULINE
            else -> VoiceGender.UNSPECIFIED
        }

    override suspend fun load(voice: TtsVoice): LoadedVoice = withContext(Dispatchers.IO) {
        val env = environment
            ?: throw TtsException("ONNX Runtime is not available on this device, so Kokoro voices cannot be used.")

        val raw = voice.id.value.removePrefix("kokoro/")
        val packKey = raw.substringBefore('#')
        val speaker = raw.substringAfter('#', "")

        val pack = discoverPacks().firstOrNull { it.key == packKey }
            ?: throw TtsException("The voice pack for \"${voice.displayName}\" is no longer installed.")
        val voiceFile = pack.voiceFiles.firstOrNull { it.name.substringBeforeLast('.') == speaker }
            ?: throw TtsException("The speaker \"$speaker\" is missing from this voice pack.")

        val config = runCatching { KokoroModelConfig.parse(pack.configFile.readText()) }
            .getOrElse { throw TtsException("This Kokoro pack's configuration could not be read.", it) }
        val style = runCatching { KokoroStyleTable.load(voiceFile, config.styleDimensions) }
            .getOrElse { throw TtsException("The voice file for \"${voice.displayName}\" could not be read.", it) }

        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setInterOpNumThreads(1)
            runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        }
        val session = runCatching { env.createSession(pack.modelFile.absolutePath, options) }
            .getOrElse { throw TtsException("The Kokoro model could not be loaded.", it) }

        KokoroVoice(
            voice = voice.copy(sampleRate = config.sampleRate),
            config = config,
            style = style,
            session = session,
            environment = env,
            phonemizer = phonemizerProvider(),
        )
    }

    private companion object {
        /** Kokoro's speaker-name prefixes: the first letter is the language. */
        val LANGUAGE_PREFIXES = mapOf(
            "a" to "en", "b" to "en", "e" to "es", "f" to "fr",
            "h" to "hi", "i" to "it", "j" to "ja", "p" to "pt", "z" to "zh",
        )

        /** The same letter also distinguishes American from British English. */
        val REGION_PREFIXES = mapOf("a" to "US", "b" to "GB")
    }
}

/** A loaded Kokoro speaker. */
class KokoroVoice internal constructor(
    override val voice: TtsVoice,
    private val config: KokoroModelConfig,
    private val style: KokoroStyleTable,
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    private val phonemizer: Phonemizer,
) : LoadedVoice {

    override val sampleRate: Int get() = config.sampleRate

    /**
     * Input names differ between published exports, so they are matched by what they contain
     * rather than assumed. Hard-coding them is how a pack that works in one tool produces an
     * unhelpful "invalid input name" in another.
     */
    private val inputNames: List<String> = runCatching { session.inputNames.toList() }.getOrDefault(emptyList())
    private val tokenInput = inputNames.firstOrNull { it.contains("input", true) || it.contains("token", true) }
        ?: "input_ids"
    private val styleInput = inputNames.firstOrNull { it.contains("style", true) || it.contains("ref", true) }
        ?: "style"
    private val speedInput = inputNames.firstOrNull { it.contains("speed", true) || it.contains("rate", true) }

    override suspend fun synthesise(text: String, params: SpeechParams): AudioClip =
        withContext(Dispatchers.Default) {
            val safe = params.coerced()
            val phonemes = phonemise(text)
            if (phonemes.isBlank()) return@withContext AudioClip.empty(sampleRate)

            val ids = KokoroTokeniser.toIds(phonemes, config)
            // Two pad tokens and nothing between them means nothing survived phonemisation.
            if (ids.size <= 2) return@withContext AudioClip.empty(sampleRate)

            val raw = runInference(ids, safe)
            finish(raw, safe)
        }

    private fun phonemise(text: String): String {
        if (phonemizer is EspeakPhonemizer) {
            phonemizer.phonemiseLine(text, config.languageTag)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        val out = StringBuilder()
        phonemizer.phonemise(text, config.languageTag).forEach { token ->
            when (token.kind) {
                PhonemeToken.Kind.WORD -> out.append(token.phonemes)
                PhonemeToken.Kind.PUNCTUATION -> out.append(token.source)
                PhonemeToken.Kind.WHITESPACE -> out.append(' ')
            }
        }
        return out.toString()
    }

    private fun runInference(ids: IntArray, params: SpeechParams): AudioClip {
        val inputs = HashMap<String, OnnxTensor>()
        try {
            val idBuffer = LongBuffer.allocate(ids.size)
            ids.forEach { idBuffer.put(it.toLong()) }
            idBuffer.rewind()
            inputs[tokenInput] = OnnxTensor.createTensor(
                environment,
                idBuffer,
                longArrayOf(1, ids.size.toLong()),
            )

            // The style vector is chosen by the length of this utterance, which is what gives
            // Kokoro its pacing.
            val styleVector = style.styleFor(ids.size)
            val styleBuffer = FloatBuffer.wrap(styleVector)
            inputs[styleInput] = OnnxTensor.createTensor(
                environment,
                styleBuffer,
                longArrayOf(1, styleVector.size.toLong()),
            )

            if (speedInput != null) {
                val speed = FloatBuffer.allocate(1).put(params.rate).also { it.rewind() }
                inputs[speedInput] = OnnxTensor.createTensor(environment, speed, longArrayOf(1))
            }

            session.run(inputs).use { results ->
                val samples = flatten(results[0].value)
                    ?: throw TtsException("This Kokoro model returned audio in a shape Soundbound does not recognise.")
                return AudioClip(samples, config.sampleRate)
            }
        } catch (e: TtsException) {
            throw e
        } catch (e: Exception) {
            throw TtsException("Speech synthesis failed for \"${voice.displayName}\".", e)
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun flatten(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value
        is Array<*> -> value.firstOrNull()?.let(::flatten)
        else -> null
    }

    private fun finish(clip: AudioClip, params: SpeechParams): AudioClip {
        var result = Dsp.trimSilence(clip)
        // When the model has no speed input, the rate is applied by time-stretching instead.
        if (speedInput == null && params.rate != 1f) result = Dsp.changeSpeed(result, params.rate)
        if (params.pitchSemitones != 0f) result = Dsp.changePitch(result, params.pitchSemitones)
        result = Dsp.applyEdgeFades(result)
        if (params.volume != 1f) result = Dsp.applyGain(result, params.volume)
        return result
    }

    override fun close() {
        runCatching { session.close() }
    }
}
