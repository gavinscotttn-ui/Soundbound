package app.soundbound.core.tts.g2p

import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.source
import java.io.InputStream

/**
 * A pronunciation dictionary: word → IPA.
 *
 * Soundbound ships a small built-in table of the English function words and common
 * irregulars — the words that make up most of any page and that letter-to-sound rules get
 * wrong most embarrassingly ("said", "once", "colonel"). A full dictionary (CMUdict is the
 * usual source, about 135,000 entries) can be installed alongside it; see
 * `tools/build-lexicon.py` in the repository.
 */
class Lexicon private constructor(
    private val entries: Map<String, String>,
    val name: String,
) {
    val size: Int get() = entries.size

    fun lookup(word: String): String? {
        val key = word.lowercase()
        entries[key]?.let { return it }

        // Try the possessive and plural forms of a known stem before giving up.
        if (key.endsWith("'s") || key.endsWith("’s")) {
            entries[key.dropLast(2)]?.let { return it + possessiveSuffix(it) }
        }
        if (key.endsWith("s") && key.length > 3) {
            entries[key.dropLast(1)]?.let { return it + pluralSuffix(it) }
        }
        if (key.endsWith("ed") && key.length > 4) {
            entries[key.dropLast(2)]?.let { return it + pastSuffix(it) }
        }
        if (key.endsWith("ing") && key.length > 5) {
            entries[key.dropLast(3)]?.let { return it + "ɪŋ" }
            entries[key.dropLast(3) + "e"]?.let { return it + "ɪŋ" }
        }
        // Hyphenated compounds: look the halves up separately.
        if ('-' in key) {
            val parts = key.split('-').map { entries[it] ?: return null }
            return parts.joinToString(" ")
        }
        return null
    }

    operator fun contains(word: String): Boolean = lookup(word) != null

    fun withOverrides(overrides: Map<String, String>): Lexicon =
        Lexicon(entries + overrides.mapKeys { it.key.lowercase() }, "$name + ${overrides.size} overrides")

    private fun voicelessEnding(ipa: String): Boolean =
        ipa.isNotEmpty() && ipa.last() in "ptkfθ"

    private fun sibilantEnding(ipa: String): Boolean =
        ipa.endsWith("s") || ipa.endsWith("z") || ipa.endsWith("ʃ") ||
            ipa.endsWith("ʒ") || ipa.endsWith("tʃ") || ipa.endsWith("dʒ")

    private fun pluralSuffix(ipa: String): String = when {
        sibilantEnding(ipa) -> "ɪz"
        voicelessEnding(ipa) -> "s"
        else -> "z"
    }

    private fun possessiveSuffix(ipa: String): String = pluralSuffix(ipa)

    private fun pastSuffix(ipa: String): String = when {
        ipa.endsWith("t") || ipa.endsWith("d") -> "ɪd"
        voicelessEnding(ipa) -> "t"
        else -> "d"
    }

    companion object {
        /**
         * Parses a lexicon file. The first line may be a header:
         * `# soundbound-lexicon 1 ipa` or `# soundbound-lexicon 1 arpabet`.
         * Everything after it is `word<TAB>pronunciation`. Gzip is detected automatically.
         */
        fun load(input: InputStream, name: String = "lexicon"): Lexicon {
            val bytes = input.buffered()
            bytes.mark(2)
            val first = bytes.read()
            val second = bytes.read()
            bytes.reset()
            val gzipped = first == 0x1F && second == 0x8B
            val source: BufferedSource =
                if (gzipped) bytes.source().gzip().buffer() else bytes.source().buffer()

            var arpabet = false
            var british = true
            val entries = HashMap<String, String>(4096)

            source.use { reader ->
                while (true) {
                    val line = reader.readUtf8Line() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith("#")) {
                        if (line.contains("arpabet", ignoreCase = true)) arpabet = true
                        if (line.contains("american", ignoreCase = true)) british = false
                        continue
                    }
                    val tab = line.indexOf('\t').takeIf { it > 0 } ?: line.indexOf(' ').takeIf { it > 0 }
                    ?: continue
                    val word = line.substring(0, tab).lowercase()
                    // CMUdict marks alternative pronunciations as WORD(2); keep only the first.
                    if (word.endsWith(")")) continue
                    val pronunciation = line.substring(tab + 1).trim()
                    if (word.isEmpty() || pronunciation.isEmpty()) continue
                    val ipa = if (arpabet) ArpabetToIpa.convert(pronunciation, british) else pronunciation
                    if (ipa.isNotEmpty()) entries.putIfAbsent(word, ipa)
                }
            }
            return Lexicon(BUILT_IN + entries, name)
        }

        /** The built-in table alone. Always available, no files needed. */
        fun builtIn(): Lexicon = Lexicon(BUILT_IN, "built-in")

        fun of(entries: Map<String, String>, name: String = "custom"): Lexicon =
            Lexicon(BUILT_IN + entries.mapKeys { it.key.lowercase() }, name)

        /**
         * Received Pronunciation for the English words that appear most often and that
         * spelling rules handle worst. Roughly the top few hundred by frequency, plus the
         * notorious irregulars.
         */
        private val BUILT_IN: Map<String, String> = mapOf(
            // Function words, unstressed as they are in connected speech.
            "the" to "ðə", "a" to "ə", "an" to "ən", "and" to "ənd", "of" to "əv",
            "to" to "tə", "in" to "ɪn", "is" to "ɪz", "it" to "ɪt", "that" to "ðæt",
            "was" to "wɒz", "he" to "hiː", "for" to "fɔː", "on" to "ɒn", "are" to "ɑː",
            "as" to "əz", "with" to "wɪð", "his" to "hɪz", "they" to "ðeɪ", "at" to "æt",
            "be" to "biː", "this" to "ðɪs", "have" to "hæv", "from" to "fɹɒm", "or" to "ɔː",
            "one" to "wʌn", "had" to "hæd", "by" to "baɪ", "word" to "wɜːd", "but" to "bʌt",
            "not" to "nɒt", "what" to "wɒt", "all" to "ɔːl", "were" to "wɜː", "we" to "wiː",
            "when" to "wɛn", "your" to "jɔː", "can" to "kæn", "said" to "sɛd", "there" to "ðɛə",
            "use" to "juːz", "each" to "iːtʃ", "which" to "wɪtʃ", "she" to "ʃiː", "do" to "duː",
            "how" to "haʊ", "their" to "ðɛə", "if" to "ɪf", "will" to "wɪl", "up" to "ʌp",
            "other" to "ˈʌðə", "about" to "əˈbaʊt", "out" to "aʊt", "many" to "ˈmɛni",
            "then" to "ðɛn", "them" to "ðɛm", "these" to "ðiːz", "so" to "səʊ",
            "some" to "sʌm", "her" to "hɜː", "would" to "wʊd", "make" to "meɪk",
            "like" to "laɪk", "him" to "hɪm", "into" to "ˈɪntuː", "time" to "taɪm",
            "has" to "hæz", "look" to "lʊk", "two" to "tuː", "more" to "mɔː",
            "write" to "ɹaɪt", "go" to "ɡəʊ", "see" to "siː", "number" to "ˈnʌmbə",
            "no" to "nəʊ", "way" to "weɪ", "could" to "kʊd", "people" to "ˈpiːpəl",
            "my" to "maɪ", "than" to "ðæn", "first" to "fɜːst", "water" to "ˈwɔːtə",
            "been" to "biːn", "call" to "kɔːl", "who" to "huː", "its" to "ɪts",
            "now" to "naʊ", "find" to "faɪnd", "long" to "lɒŋ", "down" to "daʊn",
            "day" to "deɪ", "did" to "dɪd", "get" to "ɡɛt", "come" to "kʌm",
            "made" to "meɪd", "may" to "meɪ", "part" to "pɑːt", "over" to "ˈəʊvə",
            "new" to "njuː", "sound" to "saʊnd", "take" to "teɪk", "only" to "ˈəʊnli",
            "little" to "ˈlɪtəl", "work" to "wɜːk", "know" to "nəʊ", "place" to "pleɪs",
            "year" to "jɪə", "live" to "lɪv", "me" to "miː", "back" to "bæk",
            "give" to "ɡɪv", "most" to "məʊst", "very" to "ˈvɛɹi", "after" to "ˈɑːftə",
            "thing" to "θɪŋ", "our" to "aʊə", "just" to "dʒʌst", "name" to "neɪm",
            "good" to "ɡʊd", "sentence" to "ˈsɛntəns", "man" to "mæn", "think" to "θɪŋk",
            "say" to "seɪ", "great" to "ɡɹeɪt", "where" to "wɛə", "help" to "hɛlp",
            "through" to "θɹuː", "much" to "mʌtʃ", "before" to "bɪˈfɔː", "line" to "laɪn",
            "right" to "ɹaɪt", "too" to "tuː", "mean" to "miːn", "old" to "əʊld",
            "any" to "ˈɛni", "same" to "seɪm", "tell" to "tɛl", "boy" to "bɔɪ",
            "follow" to "ˈfɒləʊ", "came" to "keɪm", "want" to "wɒnt", "show" to "ʃəʊ",
            "also" to "ˈɔːlsəʊ", "around" to "əˈɹaʊnd", "form" to "fɔːm", "three" to "θɹiː",
            "small" to "smɔːl", "set" to "sɛt", "put" to "pʊt", "end" to "ɛnd",
            "does" to "dʌz", "another" to "əˈnʌðə", "well" to "wɛl", "large" to "lɑːdʒ",
            "must" to "mʌst", "big" to "bɪɡ", "even" to "ˈiːvən", "such" to "sʌtʃ",
            "because" to "bɪˈkɒz", "turn" to "tɜːn", "here" to "hɪə", "why" to "waɪ",
            "ask" to "ɑːsk", "went" to "wɛnt", "men" to "mɛn", "read" to "ɹiːd",
            "need" to "niːd", "land" to "lænd", "different" to "ˈdɪfɹənt", "home" to "həʊm",
            "move" to "muːv", "try" to "tɹaɪ", "kind" to "kaɪnd", "hand" to "hænd",
            "picture" to "ˈpɪktʃə", "again" to "əˈɡɛn", "change" to "tʃeɪndʒ",
            "off" to "ɒf", "play" to "pleɪ", "spell" to "spɛl", "air" to "ɛə",
            "away" to "əˈweɪ", "animal" to "ˈænɪməl", "house" to "haʊs", "point" to "pɔɪnt",
            "page" to "peɪdʒ", "letter" to "ˈlɛtə", "mother" to "ˈmʌðə", "answer" to "ˈɑːnsə",
            "found" to "faʊnd", "study" to "ˈstʌdi", "still" to "stɪl", "learn" to "lɜːn",
            "should" to "ʃʊd", "america" to "əˈmɛɹɪkə", "world" to "wɜːld",

            // Irregulars that rules reliably mangle.
            "once" to "wʌns", "colonel" to "ˈkɜːnəl", "choir" to "ˈkwaɪə",
            "yacht" to "jɒt", "queue" to "kjuː", "island" to "ˈaɪlənd",
            "business" to "ˈbɪznɪs", "women" to "ˈwɪmɪn", "woman" to "ˈwʊmən",
            "friend" to "fɹɛnd", "enough" to "ɪˈnʌf", "though" to "ðəʊ",
            "thought" to "θɔːt", "thorough" to "ˈθʌɹə", "trough" to "tɹɒf",
            "cough" to "kɒf", "rough" to "ɹʌf", "tough" to "tʌf", "bough" to "baʊ",
            "dough" to "dəʊ", "plough" to "plaʊ", "borough" to "ˈbʌɹə",
            "laugh" to "lɑːf", "laughter" to "ˈlɑːftə", "daughter" to "ˈdɔːtə",
            "eight" to "eɪt", "weight" to "weɪt", "height" to "haɪt", "heir" to "ɛə",
            "honest" to "ˈɒnɪst", "honour" to "ˈɒnə", "hour" to "aʊə", "herb" to "hɜːb",
            "knight" to "naɪt", "knowledge" to "ˈnɒlɪdʒ", "muscle" to "ˈmʌsəl",
            "receipt" to "ɹɪˈsiːt", "subtle" to "ˈsʌtəl", "debt" to "dɛt",
            "doubt" to "daʊt", "castle" to "ˈkɑːsəl", "listen" to "ˈlɪsən",
            "often" to "ˈɒfən", "sword" to "sɔːd", "answer" to "ˈɑːnsə",
            "wednesday" to "ˈwɛnzdeɪ", "february" to "ˈfɛbɹuəɹi", "library" to "ˈlaɪbɹəɹi",
            "comfortable" to "ˈkʌmftəbəl", "vegetable" to "ˈvɛdʒtəbəl",
            "chocolate" to "ˈtʃɒklət", "restaurant" to "ˈɹɛstɹɒnt",
            "aisle" to "aɪl", "bury" to "ˈbɛɹi", "busy" to "ˈbɪzi",
            "ocean" to "ˈəʊʃən", "sugar" to "ˈʃʊɡə", "sure" to "ʃɔː",
            "one's" to "wʌnz", "says" to "sɛz", "been" to "biːn",
            "iron" to "ˈaɪən", "lieutenant" to "lɛfˈtɛnənt", "leicester" to "ˈlɛstə",
            "worcester" to "ˈwʊstə", "gloucester" to "ˈɡlɒstə", "norwich" to "ˈnɒɹɪdʒ",
            "edinburgh" to "ˈɛdɪnbəɹə", "greenwich" to "ˈɡɹɛnɪtʃ", "thames" to "tɛmz",
        )
    }
}
