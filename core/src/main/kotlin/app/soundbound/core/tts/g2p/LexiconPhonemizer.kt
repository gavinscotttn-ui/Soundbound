package app.soundbound.core.tts.g2p

/**
 * The always-available English phonemizer: dictionary first, spelling rules second.
 *
 * Soundbound prefers [app.soundbound.core.tts.g2p.EspeakPhonemizer] when the espeak-ng
 * library is present, because that is what Piper's models were trained against. This one
 * needs no native code at all, which means a fresh install can speak immediately.
 */
class LexiconPhonemizer(
    private val lexicon: Lexicon = Lexicon.builtIn(),
    /** Words the user has corrected by hand, which always win. */
    private val overrides: Map<String, String> = emptyMap(),
) : Phonemizer {

    override val id: String = "lexicon"

    override fun supports(language: String): Boolean =
        language.substringBefore('-').equals("en", ignoreCase = true)

    override fun phonemise(text: String, language: String): List<PhonemeToken> =
        TextTokeniser.tokenise(text).map { token ->
            when (token.kind) {
                PhonemeToken.Kind.WORD -> PhonemeToken(
                    source = token.text,
                    phonemes = pronounce(token.text),
                    kind = PhonemeToken.Kind.WORD,
                    sourceStart = token.start,
                )

                else -> PhonemeToken(token.text, "", token.kind, token.start)
            }
        }

    private fun pronounce(word: String): String {
        overrides[word.lowercase()]?.let { return it }
        lexicon.lookup(word)?.let { return it }

        // A word written in capitals with no vowel is an initialism; say the letters.
        if (word.length in 2..6 && word.all { it.isUpperCase() } &&
            word.none { it in "AEIOU" }
        ) {
            return word.map { letterName(it) }.joinToString(" ")
        }
        return LetterToSound.phonemise(word)
    }

    private fun letterName(letter: Char): String = LETTER_NAMES[letter.uppercaseChar()] ?: ""

    private companion object {
        val LETTER_NAMES = mapOf(
            'A' to "ˈeɪ", 'B' to "ˈbiː", 'C' to "ˈsiː", 'D' to "ˈdiː", 'E' to "ˈiː",
            'F' to "ˈɛf", 'G' to "ˈdʒiː", 'H' to "ˈeɪtʃ", 'I' to "ˈaɪ", 'J' to "ˈdʒeɪ",
            'K' to "ˈkeɪ", 'L' to "ˈɛl", 'M' to "ˈɛm", 'N' to "ˈɛn", 'O' to "ˈəʊ",
            'P' to "ˈpiː", 'Q' to "ˈkjuː", 'R' to "ˈɑː", 'S' to "ˈɛs", 'T' to "ˈtiː",
            'U' to "ˈjuː", 'V' to "ˈviː", 'W' to "ˈdʌbəljuː", 'X' to "ˈɛks",
            'Y' to "ˈwaɪ", 'Z' to "ˈzɛd",
        )
    }
}
