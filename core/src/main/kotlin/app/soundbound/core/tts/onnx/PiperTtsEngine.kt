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
import app.soundbound.core.tts.g2p.EspeakPhonemizer
import app.soundbound.core.tts.g2p.LexiconPhonemizer
import app.soundbound.core.tts.g2p.Phonemizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Where installed voices live on disk, and how they are named.
 *
 * One directory per voice, holding `model.onnx` and `model.onnx.json`. Piper's own release
 * layout (`en_GB-alba-medium.onnx` beside `en_GB-alba-medium.onnx.json`) is also accepted, so
 * a user can drop files straight from a download into the voices folder.
 */
class VoiceStore(val root: File) {

    fun ensureRoot(): File = root.apply { if (!exists()) mkdirs() }

    /** Every voice directory or loose model pair under [root]. */
    fun discover(): List<InstalledModel> {
        if (!root.isDirectory) return emptyList()
        val found = LinkedHashMap<String, InstalledModel>()

        fun consider(model: File) {
            val config = configFor(model) ?: return
            val id = model.name.removeSuffix(".onnx")
            found.putIfAbsent(id, InstalledModel(id, model, config))
        }

        root.listFiles().orEmpty().sortedBy { it.name }.forEach { entry ->
            when {
                entry.isDirectory -> entry.listFiles().orEmpty()
                    .filter { it.name.endsWith(".onnx") }
                    .sortedBy { it.name }
                    .forEach(::consider)

                entry.name.endsWith(".onnx") -> consider(entry)
            }
        }
        return found.values.toList()
    }

    private fun configFor(model: File): File? {
        val candidates = listOf(
            File(model.parentFile, model.name + ".json"),
            File(model.parentFile, model.name.removeSuffix(".onnx") + ".json"),
            File(model.parentFile, "config.json"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    fun directoryFor(voiceKey: String): File = File(ensureRoot(), voiceKey)

    data class InstalledModel(val key: String, val modelFile: File, val configFile: File)
}

/**
 * Piper voices, run locally through ONNX Runtime.
 *
 * Piper is the reason Soundbound can offer hundreds of voices across forty-odd languages
 * without a network connection: the models are small (20–110 MB), the licence is permissive,
 * and the "high" quality voices hold up against anything that needs a server.
 */
class PiperTtsEngine(
    private val store: VoiceStore,
    /** Preferred phonemizer. espeak-ng where available, the built-in lexicon otherwise. */
    private val phonemizerProvider: () -> Phonemizer = { LexiconPhonemizer() },
    /** Threads for the ONNX session. One less than the core count keeps the UI smooth. */
    private val threadCount: Int = defaultThreadCount(),
) : TtsEngine {

    override val kind: EngineKind = EngineKind.PIPER

    private val environment: OrtEnvironment? by lazy {
        runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    }

    override val isAvailable: Boolean
        get() = environment != null

    override suspend fun installedVoices(): List<TtsVoice> = withContext(Dispatchers.IO) {
        store.discover().mapNotNull { model ->
            val config = runCatching { PiperModelConfig.parse(model.configFile.readText()) }.getOrNull()
                ?: return@mapNotNull null
            describe(model, config)
        }.flatten()
    }

    /** One catalogue entry per speaker, so a multi-speaker model appears as several voices. */
    private fun describe(model: VoiceStore.InstalledModel, config: PiperModelConfig): List<TtsVoice> {
        val baseName = prettyName(model.key, config)
        if (!config.isMultiSpeaker) {
            return listOf(
                TtsVoice(
                    id = VoiceId("piper/${model.key}"),
                    displayName = baseName,
                    engine = EngineKind.PIPER,
                    language = config.languageTag,
                    gender = PiperModelConfig.guessGender(model.key),
                    quality = config.quality,
                    sampleRate = config.sampleRate,
                    speakerId = 0,
                    description = config.datasetName,
                    downloadBytes = model.modelFile.length(),
                    isInstalled = true,
                    installPath = model.modelFile.absolutePath,
                ),
            )
        }

        val names = config.speakerIdMap.entries.sortedBy { it.value }
        return (0 until config.numSpeakers).map { speaker ->
            val speakerName = names.firstOrNull { it.value == speaker }?.key ?: "Speaker ${speaker + 1}"
            TtsVoice(
                id = VoiceId("piper/${model.key}#$speaker"),
                displayName = "$baseName · $speakerName",
                engine = EngineKind.PIPER,
                language = config.languageTag,
                gender = PiperModelConfig.guessGender(speakerName),
                quality = config.quality,
                sampleRate = config.sampleRate,
                speakerId = speaker,
                description = config.datasetName,
                downloadBytes = model.modelFile.length(),
                isInstalled = true,
                installPath = model.modelFile.absolutePath,
            )
        }
    }

    /** `en_GB-alba-medium` reads better in a list as "Alba — English (United Kingdom), Medium". */
    private fun prettyName(key: String, config: PiperModelConfig): String {
        val parts = key.split('-')
        val speaker = parts.getOrNull(1)?.replace('_', ' ')
            ?.split(' ')
            ?.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            ?: key
        return speaker.ifBlank { key }
    }

    override suspend fun load(voice: TtsVoice): LoadedVoice = withContext(Dispatchers.IO) {
        val env = environment
            ?: throw TtsException("ONNX Runtime is not available on this device, so Piper voices cannot be used.")
        val key = voice.id.value.removePrefix("piper/").substringBefore('#')
        val model = store.discover().firstOrNull { it.key == key }
            ?: throw TtsException("The voice \"${voice.displayName}\" is no longer installed.")
        val config = runCatching { PiperModelConfig.parse(model.configFile.readText()) }
            .getOrElse { throw TtsException("The configuration for \"${voice.displayName}\" could not be read.", it) }

        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setInterOpNumThreads(1)
            runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        }
        val session = runCatching { env.createSession(model.modelFile.absolutePath, options) }
            .getOrElse { throw TtsException("The model for \"${voice.displayName}\" could not be loaded.", it) }

        PiperVoice(
            voice = voice.copy(sampleRate = config.sampleRate),
            config = config,
            session = session,
            environment = env,
            phonemizer = phonemizerProvider(),
        )
    }

    override fun close() = Unit

    companion object {
        fun defaultThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}

/** A loaded Piper model, ready to speak. */
class PiperVoice internal constructor(
    override val voice: TtsVoice,
    private val config: PiperModelConfig,
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    private val phonemizer: Phonemizer,
) : LoadedVoice {

    override val sampleRate: Int get() = config.sampleRate

    private val inputNames: Set<String> = runCatching { session.inputNames.toSet() }.getOrDefault(emptySet())

    override suspend fun synthesise(text: String, params: SpeechParams): AudioClip =
        withContext(Dispatchers.Default) {
            val safe = params.coerced()
            val phonemes = phonemise(text)
            if (phonemes.isBlank()) return@withContext AudioClip.empty(sampleRate)

            val ids = PiperTokeniser.toIds(phonemes, config)
            // Two markers and a pad is an empty utterance; anything shorter than that means
            // nothing survived phonemisation.
            if (ids.size <= 4) return@withContext AudioClip.empty(sampleRate)

            val raw = runInference(ids, safe)
            finish(raw, safe)
        }

    private fun phonemise(text: String): String {
        // espeak-ng gives better phrase-level prosody when handed the whole line at once.
        if (phonemizer is EspeakPhonemizer) {
            phonemizer.phonemiseLine(text, config.espeakVoice ?: config.languageTag)
                ?.takeIf { it.isNotBlank() }
                ?.let { return applyPhonemeMap(it) }
        }
        val tokens = phonemizer.phonemise(text, config.languageTag)
        val out = StringBuilder()
        tokens.forEach { token ->
            when (token.kind) {
                app.soundbound.core.tts.g2p.PhonemeToken.Kind.WORD -> out.append(token.phonemes)
                // Punctuation is passed through: Piper's phoneme map contains commas and full
                // stops, and they are what produce the pauses and the falling intonation.
                app.soundbound.core.tts.g2p.PhonemeToken.Kind.PUNCTUATION -> out.append(token.source)
                app.soundbound.core.tts.g2p.PhonemeToken.Kind.WHITESPACE -> out.append(' ')
            }
        }
        return applyPhonemeMap(out.toString())
    }

    /** Some voices ship a `phoneme_map` that rewrites symbols the model was not trained on. */
    private fun applyPhonemeMap(phonemes: String): String {
        if (config.phonemeMap.isEmpty()) return phonemes
        val out = StringBuilder(phonemes.length)
        phonemes.forEach { ch ->
            val mapped = config.phonemeMap[ch.toString()]
            if (mapped != null) mapped.forEach(out::append) else out.append(ch)
        }
        return out.toString()
    }

    private fun runInference(ids: IntArray, params: SpeechParams): AudioClip {
        val inputs = HashMap<String, OnnxTensor>()
        try {
            val idBuffer = LongBuffer.allocate(ids.size)
            ids.forEach { idBuffer.put(it.toLong()) }
            idBuffer.rewind()
            inputs["input"] = OnnxTensor.createTensor(environment, idBuffer, longArrayOf(1, ids.size.toLong()))

            val lengths = LongBuffer.allocate(1).put(ids.size.toLong()).also { it.rewind() }
            inputs["input_lengths"] = OnnxTensor.createTensor(environment, lengths, longArrayOf(1))

            // Piper takes speed as `length_scale`, where larger is slower. Letting the model
            // do the work sounds markedly better than time-stretching the output afterwards,
            // because the phoneme durations are re-predicted rather than smeared.
            val lengthScale = config.lengthScale / params.rate
            val scales = FloatBuffer.allocate(3).apply {
                put(params.expressiveness)
                put(lengthScale)
                put(params.cadenceVariation)
                rewind()
            }
            inputs["scales"] = OnnxTensor.createTensor(environment, scales, longArrayOf(3))

            if (config.isMultiSpeaker && "sid" in inputNames) {
                val sid = LongBuffer.allocate(1).put(voice.speakerId.toLong()).also { it.rewind() }
                inputs["sid"] = OnnxTensor.createTensor(environment, sid, longArrayOf(1))
            }

            val requested = inputs.filterKeys { it in inputNames || inputNames.isEmpty() }
            session.run(requested).use { results ->
                val output = results[0].value
                val samples = flatten(output)
                    ?: throw TtsException("This Piper model returned audio in a shape Soundbound does not recognise.")
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

    /** Piper emits `[1, 1, samples]`, but exported variants differ, so unwrap defensively. */
    private fun flatten(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value
        is Array<*> -> value.firstOrNull()?.let(::flatten)
        else -> null
    }

    private fun finish(clip: AudioClip, params: SpeechParams): AudioClip {
        var result = clip
        // The model has already applied the rate via length_scale; anything left over (an
        // extreme setting the model handles badly) is made up with time-stretching.
        result = Dsp.trimSilence(result)
        if (params.pitchSemitones != 0f) result = Dsp.changePitch(result, params.pitchSemitones)
        result = Dsp.applyEdgeFades(result)
        if (params.volume != 1f) result = Dsp.applyGain(result, params.volume)
        return result
    }

    override fun close() {
        runCatching { session.close() }
    }
}
