package app.soundbound.core.tts.g2p

/** One unit of phonemised text. */
data class PhonemeToken(
    /** The original word or punctuation mark. */
    val source: String,
    /** IPA phonemes for [source]. Empty for punctuation, which contributes only prosody. */
    val phonemes: String,
    val kind: Kind,
    /** Offset of [source] within the text that was phonemised. */
    val sourceStart: Int,
) {
    enum class Kind { WORD, PUNCTUATION, WHITESPACE }
}

/**
 * Turns written text into the phonemes a neural voice expects.
 *
 * This is the step that decides whether a synthesiser says "lead" as the metal or the verb,
 * and whether it can say an unfamiliar name at all. Piper models are trained on espeak-ng's
 * IPA output, so that is the target alphabet.
 */
interface Phonemizer {
    val id: String

    /** True when this phonemizer can handle the given BCP-47 language tag. */
    fun supports(language: String): Boolean

    fun phonemise(text: String, language: String): List<PhonemeToken>

    /** Convenience: the phoneme string alone, with word boundaries preserved as spaces. */
    fun phonemeString(text: String, language: String): String =
        phonemise(text, language).joinToString("") { token ->
            when (token.kind) {
                PhonemeToken.Kind.WORD -> token.phonemes
                PhonemeToken.Kind.PUNCTUATION -> token.source
                PhonemeToken.Kind.WHITESPACE -> " "
            }
        }
}

/**
 * Splits text into words, punctuation and whitespace.
 *
 * Punctuation is kept because Piper models are trained with it in the phoneme stream: the
 * comma is what produces the pause, and stripping it flattens the delivery.
 */
object TextTokeniser {

    private val WORD_CHARACTERS = Regex("""[\p{L}\p{M}\p{Nd}'’‑-]""")

    fun tokenise(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch.isWhitespace() -> {
                    val start = index
                    while (index < text.length && text[index].isWhitespace()) index++
                    tokens.add(Token(" ", start, PhonemeToken.Kind.WHITESPACE))
                }

                WORD_CHARACTERS.matches(ch.toString()) -> {
                    val start = index
                    while (index < text.length && WORD_CHARACTERS.matches(text[index].toString())) index++
                    var word = text.substring(start, index)
                    // A trailing apostrophe or hyphen belongs to the punctuation, not the word.
                    while (word.isNotEmpty() && (word.last() == '\'' || word.last() == '’' || word.last() == '-')) {
                        word = word.dropLast(1)
                        index--
                    }
                    if (word.isEmpty()) {
                        index = start + 1
                        tokens.add(Token(text.substring(start, index), start, PhonemeToken.Kind.PUNCTUATION))
                    } else {
                        tokens.add(Token(word, start, PhonemeToken.Kind.WORD))
                    }
                }

                else -> {
                    tokens.add(Token(ch.toString(), index, PhonemeToken.Kind.PUNCTUATION))
                    index++
                }
            }
        }
        return tokens
    }

    data class Token(val text: String, val start: Int, val kind: PhonemeToken.Kind)
}
