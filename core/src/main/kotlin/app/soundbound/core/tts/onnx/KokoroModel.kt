package app.soundbound.core.tts.onnx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Kokoro voice pack's configuration.
 *
 * Kokoro is a small, strikingly natural model, and the main reason to want it over Piper is
 * simply that it sounds more like a person. Like the Piper support, everything that could be
 * guessed is instead read from the files the pack ships: the vocabulary comes from the model's
 * own `config.json`, and the style vectors from its voice files. A voice that will not load is a
 * better outcome than one that loads and produces confident nonsense.
 */
data class KokoroModelConfig(
    /** Symbol to token id, exactly as the model's configuration declares it. */
    val vocab: Map<String, Int>,
    val sampleRate: Int,
    /** Longest token sequence the style table covers. */
    val maxTokens: Int,
    val styleDimensions: Int,
    val languageTag: String,
) {
    fun idOf(symbol: String): Int? = vocab[symbol]

    val longestSymbol: Int = vocab.keys.maxOfOrNull { it.length } ?: 1

    companion object {
        /**
         * The token used to pad both ends of the sequence. Kokoro's vocabulary maps the symbol
         * `$` to it, but every published pack uses 0 and the padding is positional rather than
         * looked up, so it is fixed here.
         */
        const val PAD_TOKEN = 0

        /** Kokoro produces 24 kHz audio. Read from the config where present. */
        const val DEFAULT_SAMPLE_RATE = 24_000

        /** Style tables in the published packs cover sequences up to 510 tokens. */
        const val DEFAULT_MAX_TOKENS = 510

        const val DEFAULT_STYLE_DIMENSIONS = 256

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parses a pack's `config.json`.
         *
         * The vocabulary has lived under two different keys across releases, and once inside a
         * `model` object, so all three are tried before giving up.
         */
        fun parse(text: String): KokoroModelConfig {
            val root = json.parseToJsonElement(text).jsonObject
            val vocab = findVocab(root)
                ?: throw IllegalArgumentException(
                    "This Kokoro configuration has no vocabulary, so its text cannot be tokenised.",
                )

            return KokoroModelConfig(
                vocab = vocab,
                sampleRate = root["sample_rate"]?.jsonPrimitive?.intOrNull
                    ?: root["sampling_rate"]?.jsonPrimitive?.intOrNull
                    ?: DEFAULT_SAMPLE_RATE,
                maxTokens = root["n_token"]?.jsonPrimitive?.intOrNull
                    ?: root["max_tokens"]?.jsonPrimitive?.intOrNull
                    ?: DEFAULT_MAX_TOKENS,
                styleDimensions = root["style_dim"]?.jsonPrimitive?.intOrNull
                    ?: DEFAULT_STYLE_DIMENSIONS,
                languageTag = root["language"]?.jsonPrimitive?.contentOrNull?.replace('_', '-')
                    ?: "en-US",
            )
        }

        private fun findVocab(root: JsonObject): Map<String, Int>? {
            val candidates = listOfNotNull(
                root["vocab"] as? JsonObject,
                root["token_to_id"] as? JsonObject,
                (root["model"] as? JsonObject)?.get("vocab") as? JsonObject,
            )
            val obj = candidates.firstOrNull() ?: return null
            val map = obj.mapNotNull { (symbol, value) ->
                value.jsonPrimitive.intOrNull?.let { symbol to it }
            }.toMap()
            return map.takeIf { it.isNotEmpty() }
        }

        /** True when a configuration file looks like Kokoro's rather than Piper's. */
        fun looksLikeKokoro(text: String): Boolean = runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            root["phoneme_id_map"] == null && findVocab(root) != null
        }.getOrDefault(false)
    }
}

/**
 * A Kokoro speaker's style table.
 *
 * The table holds one style vector per possible token count, and the right one is chosen by the
 * length of the utterance. That is unusual enough to be worth stating plainly: using a single
 * vector, or the wrong row, produces speech that is intelligible but flat and oddly paced, which
 * is exactly the sort of fault that gets shipped because it does not look like a bug.
 */
class KokoroStyleTable private constructor(
    private val data: FloatArray,
    val rows: Int,
    val dimensions: Int,
) {
    /** The style vector for an utterance of [tokenCount] tokens. */
    fun styleFor(tokenCount: Int): FloatArray {
        val row = tokenCount.coerceIn(0, rows - 1)
        return data.copyOfRange(row * dimensions, (row + 1) * dimensions)
    }

    companion object {
        /**
         * Loads a `.bin` of little-endian 32-bit floats laid out as [rows][1][dimensions].
         *
         * The row count is derived from the file's length rather than assumed, so a pack with a
         * different table size still loads.
         */
        fun load(bytes: ByteArray, dimensions: Int = KokoroModelConfig.DEFAULT_STYLE_DIMENSIONS): KokoroStyleTable {
            require(dimensions > 0) { "dimensions must be positive" }
            val floats = bytes.size / 4
            require(floats >= dimensions) {
                "This voice file holds $floats values, fewer than the $dimensions a single style needs."
            }
            require(floats % dimensions == 0) {
                "This voice file holds $floats values, which is not a whole number of " +
                    "$dimensions-value styles."
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val data = FloatArray(floats)
            buffer.get(data)
            return KokoroStyleTable(data, floats / dimensions, dimensions)
        }

        fun load(file: File, dimensions: Int = KokoroModelConfig.DEFAULT_STYLE_DIMENSIONS): KokoroStyleTable =
            load(file.readBytes(), dimensions)
    }
}

/**
 * Turns IPA phonemes into Kokoro's token ids.
 *
 * Simpler than Piper's: a flat lookup with a single pad at each end, and no interleaving. The
 * sequence is capped because the style table only covers so many tokens, and the caller is
 * expected to have split long text into sentences long before reaching here.
 */
object KokoroTokeniser {

    fun toIds(phonemes: String, config: KokoroModelConfig): IntArray {
        val ids = ArrayList<Int>(phonemes.length + 2)
        ids.add(KokoroModelConfig.PAD_TOKEN)

        var index = 0
        val limit = config.maxTokens - 2
        while (index < phonemes.length && ids.size - 1 < limit) {
            val matched = matchLongest(phonemes, index, config)
            if (matched == null) {
                // Dropped rather than substituted: a wrong phoneme is more jarring than a
                // missing one, and this happens mainly for stray symbols.
                index += Character.charCount(phonemes.codePointAt(index))
                continue
            }
            ids.add(matched.second)
            index += matched.first.length
        }

        ids.add(KokoroModelConfig.PAD_TOKEN)
        return ids.toIntArray()
    }

    private fun matchLongest(phonemes: String, at: Int, config: KokoroModelConfig): Pair<String, Int>? {
        val maxLength = minOf(config.longestSymbol, phonemes.length - at)
        for (length in maxLength downTo 1) {
            val candidate = phonemes.substring(at, at + length)
            config.idOf(candidate)?.let { return candidate to it }
        }
        return null
    }

    /** How much of the input the vocabulary covers. Used by the voice diagnostics. */
    fun coverage(phonemes: String, config: KokoroModelConfig): Float {
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
