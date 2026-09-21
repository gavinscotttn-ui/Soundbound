package app.soundbound.core.voices

import app.soundbound.core.tts.EngineKind
import app.soundbound.core.tts.VoiceGender
import app.soundbound.core.tts.VoiceQuality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One downloadable file belonging to a voice. */
data class CatalogFile(
    /** Path relative to the catalogue's base URL. */
    val path: String,
    val sizeBytes: Long,
    val md5: String?,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val isModel: Boolean get() = fileName.endsWith(".onnx")
    val isConfig: Boolean get() = fileName.endsWith(".onnx.json") || fileName == "config.json"
}

/** A voice offered in the voice store, installed or not. */
data class CatalogVoice(
    val key: String,
    val speakerName: String,
    val languageTag: String,
    val languageNameEnglish: String,
    val countryEnglish: String?,
    val quality: VoiceQuality,
    val numSpeakers: Int,
    val speakerNames: List<String>,
    val files: List<CatalogFile>,
    val engine: EngineKind = EngineKind.PIPER,
    val gender: VoiceGender = VoiceGender.UNSPECIFIED,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    val displayName: String
        get() = speakerName.split('_', '-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    val localeDescription: String
        get() = listOfNotNull(languageNameEnglish, countryEnglish).distinct().joinToString(", ")

    val modelFile: CatalogFile? get() = files.firstOrNull { it.isModel }
    val configFile: CatalogFile? get() = files.firstOrNull { it.isConfig }

    /** True when both a model and a configuration are listed, i.e. the entry is installable. */
    val isComplete: Boolean get() = modelFile != null && configFile != null
}

/**
 * The voice store's index.
 *
 * Parses the `voices.json` published alongside the Piper voice collection. The index is the
 * single source of truth for what exists and where it lives — Soundbound deliberately does
 * not hard-code download URLs, because a typo in one would show the user a voice that cannot
 * be installed.
 */
object VoiceCatalog {

    /** Where the Piper voice collection is published. */
    const val PIPER_BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"
    const val PIPER_INDEX_URL = PIPER_BASE_URL + "voices.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(indexJson: String): List<CatalogVoice> {
        val root = json.parseToJsonElement(indexJson).jsonObject
        return root.mapNotNull { (key, element) ->
            runCatching { parseVoice(key, element.jsonObject) }.getOrNull()
        }.filter { it.isComplete }
            .sortedWith(compareBy({ it.languageTag }, { -it.quality.order }, { it.displayName }))
    }

    private fun parseVoice(key: String, obj: JsonObject): CatalogVoice {
        val language = obj["language"]?.jsonObject
        val speakerMap = obj["speaker_id_map"]?.jsonObject
        val files = obj["files"]?.jsonObject?.map { (path, meta) ->
            val metaObj = meta.jsonObject
            CatalogFile(
                path = path,
                sizeBytes = metaObj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                md5 = metaObj["md5_digest"]?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()

        val speakerName = obj["name"]?.jsonPrimitive?.contentOrNull ?: key.split('-').getOrNull(1) ?: key
        return CatalogVoice(
            key = obj["key"]?.jsonPrimitive?.contentOrNull ?: key,
            speakerName = speakerName,
            languageTag = language?.get("code")?.jsonPrimitive?.contentOrNull?.replace('_', '-')
                ?: key.substringBefore('-').replace('_', '-'),
            languageNameEnglish = language?.get("name_english")?.jsonPrimitive?.contentOrNull
                ?: language?.get("family")?.jsonPrimitive?.contentOrNull
                ?: "Unknown",
            countryEnglish = language?.get("country_english")?.jsonPrimitive?.contentOrNull,
            quality = VoiceQuality.parse(obj["quality"]?.jsonPrimitive?.contentOrNull),
            numSpeakers = obj["num_speakers"]?.jsonPrimitive?.intOrNull ?: 1,
            speakerNames = speakerMap?.keys?.toList().orEmpty(),
            files = files,
            gender = guessGender(speakerName),
        )
    }

    /**
     * The collection does not record a speaker's gender, so it is inferred from the dataset
     * name where that is unambiguous. Voices it cannot place are simply left unlabelled
     * rather than guessed at.
     */
    private fun guessGender(name: String): VoiceGender {
        val lower = name.lowercase()
        return when {
            FEMININE.any { lower.contains(it) } -> VoiceGender.FEMININE
            MASCULINE.any { lower.contains(it) } -> VoiceGender.MASCULINE
            else -> VoiceGender.UNSPECIFIED
        }
    }

    private val FEMININE = listOf(
        "female", "alba", "jenny", "amy", "kathleen", "kristin", "ljspeech", "semaine",
        "southern_english_female", "hfc_female", "cori", "aru",
    )
    private val MASCULINE = listOf(
        "male", "alan", "northern_english_male", "ryan", "joe", "john", "danny", "kusal",
        "hfc_male", "bryce", "norman", "arctic",
    )
}

/** Groups the catalogue for display: by language, then by quality. */
fun List<CatalogVoice>.groupedByLanguage(): Map<String, List<CatalogVoice>> =
    groupBy { it.localeDescription.ifBlank { it.languageTag } }
        .toSortedMap()
        .mapValues { (_, voices) -> voices.sortedWith(compareBy({ -it.quality.order }, { it.displayName })) }

/** The languages present in the catalogue, as display strings with their voice counts. */
fun List<CatalogVoice>.languageSummary(): List<Pair<String, Int>> =
    groupedByLanguage().map { (language, voices) -> language to voices.size }
