package app.soundbound.core.tts.onnx

import app.soundbound.core.tts.VoiceGender
import app.soundbound.core.tts.VoiceQuality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Piper voice's companion `.onnx.json`.
 *
 * Parsed by hand rather than with generated serializers because the file has drifted across
 * Piper releases — `phoneme_id_map` values are sometimes bare integers and sometimes arrays,
 * `language` is sometimes a string and sometimes an object — and a voice that fails to load
 * is worse than one that loads with a sensible default.
 */
data class PiperModelConfig(
    val sampleRate: Int,
    val quality: VoiceQuality,
    val espeakVoice: String?,
    val languageTag: String,
    val phonemeIdMap: Map<String, IntArray>,
    val phonemeMap: Map<String, List<String>>,
    val numSpeakers: Int,
    val speakerIdMap: Map<String, Int>,
    val noiseScale: Float,
    val lengthScale: Float,
    val noiseW: Float,
    val datasetName: String?,
    val phonemeType: String,
) {
    /** The longest key in the phoneme map, so the tokeniser knows how far to look ahead. */
    val longestPhonemeKey: Int = phonemeIdMap.keys.maxOfOrNull { it.length } ?: 1

    val isMultiSpeaker: Boolean get() = numSpeakers > 1

    fun idOf(symbol: String): IntArray? = phonemeIdMap[symbol]

    companion object {
        const val BOS = "^"
        const val EOS = "$"
        const val PAD = "_"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): PiperModelConfig {
            val root = json.parseToJsonElement(text).jsonObject

            val audio = root["audio"]?.jsonObject
            val inference = root["inference"]?.jsonObject
            val espeak = root["espeak"]?.jsonObject

            val phonemeIdMap = parsePhonemeIdMap(root["phoneme_id_map"])
            require(phonemeIdMap.isNotEmpty()) {
                "This voice's configuration has no phoneme_id_map, so it cannot be used."
            }

            return PiperModelConfig(
                sampleRate = audio?.get("sample_rate")?.jsonPrimitive?.intOrNull ?: 22_050,
                quality = VoiceQuality.parse(audio?.get("quality")?.jsonPrimitive?.contentOrNull),
                espeakVoice = espeak?.get("voice")?.jsonPrimitive?.contentOrNull,
                languageTag = parseLanguage(root["language"])
                    ?: espeak?.get("voice")?.jsonPrimitive?.contentOrNull
                    ?: "en",
                phonemeIdMap = phonemeIdMap,
                phonemeMap = parsePhonemeMap(root["phoneme_map"]),
                numSpeakers = root["num_speakers"]?.jsonPrimitive?.intOrNull ?: 1,
                speakerIdMap = parseSpeakerMap(root["speaker_id_map"]),
                noiseScale = inference?.get("noise_scale")?.jsonPrimitive?.floatOrNull ?: 0.667f,
                lengthScale = inference?.get("length_scale")?.jsonPrimitive?.floatOrNull ?: 1.0f,
                noiseW = inference?.get("noise_w")?.jsonPrimitive?.floatOrNull ?: 0.8f,
                datasetName = root["dataset"]?.jsonPrimitive?.contentOrNull,
                phonemeType = root["phoneme_type"]?.jsonPrimitive?.contentOrNull ?: "espeak",
            )
        }

        private fun parsePhonemeIdMap(element: kotlinx.serialization.json.JsonElement?): Map<String, IntArray> {
            val obj = element as? JsonObject ?: return emptyMap()
            val out = HashMap<String, IntArray>(obj.size)
            obj.forEach { (symbol, value) ->
                val ids = when (value) {
                    is JsonArray -> value.mapNotNull { it.jsonPrimitive.intOrNull }.toIntArray()
                    is JsonPrimitive -> value.intOrNull?.let { intArrayOf(it) } ?: IntArray(0)
                    else -> IntArray(0)
                }
                if (ids.isNotEmpty()) out[symbol] = ids
            }
            return out
        }

        private fun parsePhonemeMap(element: kotlinx.serialization.json.JsonElement?): Map<String, List<String>> {
            val obj = element as? JsonObject ?: return emptyMap()
            return obj.mapNotNull { (from, value) ->
                val to = when (value) {
                    is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
                    is JsonPrimitive -> value.contentOrNull?.let(::listOf)
                    else -> null
                }
                if (to.isNullOrEmpty()) null else from to to
            }.toMap()
        }

        private fun parseSpeakerMap(element: kotlinx.serialization.json.JsonElement?): Map<String, Int> {
            val obj = element as? JsonObject ?: return emptyMap()
            return obj.mapNotNull { (name, value) ->
                value.jsonPrimitive.intOrNull?.let { name to it }
            }.toMap()
        }

        private fun parseLanguage(element: kotlinx.serialization.json.JsonElement?): String? = when (element) {
            null -> null
            is JsonPrimitive -> element.contentOrNull?.replace('_', '-')
            is JsonObject -> {
                val family = element["family"]?.jsonPrimitive?.contentOrNull
                val region = element["region"]?.jsonPrimitive?.contentOrNull
                val code = element["code"]?.jsonPrimitive?.contentOrNull?.replace('_', '-')
                code ?: listOfNotNull(family, region).takeIf { it.isNotEmpty() }?.joinToString("-")
            }

            else -> null
        }

        /** Guesses a speaker's gender from the voice folder name, for grouping in the UI. */
        fun guessGender(name: String): VoiceGender {
            val lower = name.lowercase()
            return when {
                FEMININE_HINTS.any { lower.contains(it) } -> VoiceGender.FEMININE
                MASCULINE_HINTS.any { lower.contains(it) } -> VoiceGender.MASCULINE
                else -> VoiceGender.UNSPECIFIED
            }
        }

        private val FEMININE_HINTS = listOf(
            "female", "-f-", "_f_", "woman", "alba", "jenny", "amy", "kathleen", "lessac",
            "ljspeech", "hfc_female", "libritts_r", "semaine", "southern_english_female",
        )
        private val MASCULINE_HINTS = listOf(
            "male", "-m-", "_m_", "man", "alan", "northern_english_male", "ryan", "joe",
            "danny", "john", "kusal", "arctic", "hfc_male", "bryce",
        )
    }
}

/**
 * Builds the integer sequence a Piper model expects from a string of IPA phonemes.
 *
 * Piper pads between every phoneme and wraps the whole utterance in beginning and end
 * markers. Getting the padding wrong does not fail loudly — it produces speech that is
 * subtly too fast and slurred — so the layout is spelled out here and covered by tests.
 */
object PiperTokeniser {

    fun toIds(phonemes: String, config: PiperModelConfig): IntArray {
        val ids = ArrayList<Int>(phonemes.length * 2 + 4)

        config.idOf(PiperModelConfig.BOS)?.forEach(ids::add)
        config.idOf(PiperModelConfig.PAD)?.forEach(ids::add)

        var index = 0
        while (index < phonemes.length) {
            val matched = matchLongest(phonemes, index, config)
            if (matched == null) {
                // An unmapped character is dropped rather than substituted: a wrong phoneme is
                // more jarring than a missing one, and this happens mainly for stray symbols.
                index += Character.charCount(phonemes.codePointAt(index))
                continue
            }
            val (symbol, mappedIds) = matched
            mappedIds.forEach(ids::add)
            config.idOf(PiperModelConfig.PAD)?.forEach(ids::add)
            index += symbol.length
        }

        config.idOf(PiperModelConfig.EOS)?.forEach(ids::add)
        return ids.toIntArray()
    }

    /**
     * Longest-match lookup. IPA is full of multi-character symbols — `tʃ`, `aɪ`, `ɔː` — and
     * some Piper maps key on those directly while others key on each character, so we try
     * the longest key first and fall back to single characters.
     */
    private fun matchLongest(
        phonemes: String,
        at: Int,
        config: PiperModelConfig,
    ): Pair<String, IntArray>? {
        val maxLength = minOf(config.longestPhonemeKey, phonemes.length - at)
        for (length in maxLength downTo 1) {
            val candidate = phonemes.substring(at, at + length)
            config.idOf(candidate)?.let { return candidate to it }
        }
        return null
    }

    /** True when every phoneme in [phonemes] is known to the model. Used by diagnostics. */
    fun coverage(phonemes: String, config: PiperModelConfig): Float {
        if (phonemes.isEmpty()) return 1f
        var known = 0
        var total = 0
        var index = 0
        while (index < phonemes.length) {
            val matched = matchLongest(phonemes, index, config)
            total++
            if (matched != null) {
                known++
                index += matched.first.length
            } else {
                index += Character.charCount(phonemes.codePointAt(index))
            }
        }
        return if (total == 0) 1f else known.toFloat() / total
    }
}
