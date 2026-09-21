package app.soundbound.core.tts.g2p

/**
 * Rule-based English letter-to-sound conversion, in the style of the NRL rules.
 *
 * This is the last line of defence: it runs only for words the lexicon has never heard of —
 * invented names, places, neologisms. It will not match a trained phonemizer, but it is far
 * better than the alternative, which is a voice that falls silent or spells the word out.
 *
 * A rule is `left context` + `focus` + `right context` → phonemes. Rules are tried longest
 * focus first at each position, so `ough` beats `ou` beats `o`.
 */
object LetterToSound {

    private const val VOWELS = "aeiouy"
    private const val CONSONANTS = "bcdfghjklmnpqrstvwxz"

    /**
     * Context patterns:
     *  `#` one or more vowels, `:` zero or more consonants, `^` exactly one consonant,
     *  `.` a voiced consonant, `+` a front vowel (e, i, y), `$` a word boundary,
     *  `%` a suffix (e, es, ed, er, ing, ely).
     */
    private data class Rule(val left: String, val focus: String, val right: String, val output: String)

    private fun rule(spec: String, output: String): Rule {
        val left = spec.substringBefore('[')
        val focus = spec.substringAfter('[').substringBefore(']')
        val right = spec.substringAfter(']')
        return Rule(left, focus, right, output)
    }

    private val RULES: List<Rule> = listOf(
        // ---------------------------------------------------------------- multigraphs
        rule("[ough]t", "ɔː"), rule("[ough]$", "ʌf"), rule("[ough]", "ʌf"),
        rule("[augh]t", "ɔː"), rule("[augh]", "ɑːf"),
        rule("[eigh]", "eɪ"), rule("[igh]", "aɪ"),
        rule("[tion]", "ʃən"), rule("[sion]", "ʒən"), rule("[cian]", "ʃən"),
        rule("[ture]$", "tʃə"), rule("[sure]$", "ʒə"),
        rule("[ought]", "ɔːt"),
        rule("[tch]", "tʃ"), rule("[dge]", "dʒ"),
        rule("[sch]", "sk"), rule("[chr]", "kɹ"),
        rule("[psy]", "saɪ"), rule("[pha]", "fæ"), rule("[phe]", "fɛ"),
        rule("[ph]", "f"), rule("[gh]$", ""), rule("[gh]", "ɡ"),
        rule("[ch]", "tʃ"), rule("[sh]", "ʃ"),
        rule("[th]e$", "ð"), rule("$[th]", "θ"), rule("[th]", "θ"),
        rule("[wh]", "w"), rule("[wr]", "ɹ"), rule("[kn]", "n"), rule("[gn]$", "n"),
        rule("$[gn]", "n"), rule("$[pn]", "n"), rule("$[ps]", "s"), rule("$[pt]", "t"),
        rule("[mb]$", "m"), rule("[mn]$", "m"),
        rule("[qu]", "kw"), rule("[ck]", "k"), rule("[ng]$", "ŋ"), rule("[nk]", "ŋk"),

        // ---------------------------------------------------------------- vowel digraphs
        rule("[eau]", "oʊ"), rule("[eye]", "aɪ"),
        rule("[ai]", "eɪ"), rule("[ay]", "eɪ"), rule("[ei]", "eɪ"), rule("[ey]", "eɪ"),
        rule("[ea]r$", "ɪə"), rule("[ea]d$", "ɛd"), rule("[ea]", "iː"),
        rule("[ee]", "iː"), rule("[ie]$", "iː"), rule("[ie]", "iː"),
        rule("[oa]", "oʊ"), rule("[oe]$", "oʊ"), rule("[oo]k", "ʊ"), rule("[oo]", "uː"),
        rule("[ou]s$", "ə"), rule("[ou]", "aʊ"), rule("[ow]$", "oʊ"), rule("[ow]", "aʊ"),
        rule("[oi]", "ɔɪ"), rule("[oy]", "ɔɪ"),
        rule("[ue]$", "uː"), rule("[ui]", "uː"), rule("[ew]", "juː"),
        rule("[au]", "ɔː"), rule("[aw]", "ɔː"),

        // ---------------------------------------------------------------- r-coloured vowels
        rule("[ar]$", "ɑː"), rule("[ar]^", "ɑː"),
        rule("[er]$", "ə"), rule("[or]$", "ɔː"), rule("[or]^", "ɔː"),
        rule("[ir]", "ɜː"), rule("[ur]", "ɜː"), rule("[er]^", "ɜː"),
        rule("[air]", "ɛə"), rule("[are]$", "ɛə"), rule("[ere]$", "ɪə"),
        rule("[ore]$", "ɔː"), rule("[ire]$", "aɪə"), rule("[ure]$", "ʊə"),

        // ---------------------------------------------------------------- magic e
        rule("[a]^e$", "eɪ"), rule("[e]^e$", "iː"), rule("[i]^e$", "aɪ"),
        rule("[o]^e$", "oʊ"), rule("[u]^e$", "juː"),

        // ---------------------------------------------------------------- soft c and g
        rule("[c]+", "s"), rule("[c]", "k"),
        rule("[g]+", "dʒ"), rule("[g]", "ɡ"),

        // ---------------------------------------------------------------- endings
        rule("[e]$", ""), rule("[es]$", "z"), rule("[ed]$", "d"),
        rule("[ing]$", "ɪŋ"), rule("[ly]$", "li"), rule("[y]$", "i"),
        rule("[le]$", "əl"), rule("[re]$", "ə"),

        // ---------------------------------------------------------------- single vowels
        rule("[a]", "æ"), rule("[e]", "ɛ"), rule("[i]", "ɪ"),
        rule("[o]", "ɒ"), rule("[u]", "ʌ"), rule("[y]", "j"),

        // ---------------------------------------------------------------- single consonants
        rule("[b]", "b"), rule("[d]", "d"), rule("[f]", "f"), rule("[h]", "h"),
        rule("[j]", "dʒ"), rule("[k]", "k"), rule("[l]", "l"), rule("[m]", "m"),
        rule("[n]", "n"), rule("[p]", "p"), rule("[q]", "k"), rule("[r]", "ɹ"),
        rule("[s]", "s"), rule("[t]", "t"), rule("[v]", "v"), rule("[w]", "w"),
        rule("[x]", "ks"), rule("[z]", "z"),
    ).sortedByDescending { it.focus.length }

    /** Rules grouped by their first letter, so lookup does not scan the whole table. */
    private val BY_FIRST_LETTER: Map<Char, List<Rule>> =
        RULES.groupBy { it.focus.first() }

    fun phonemise(word: String): String {
        val normalised = word.lowercase().filter { it.isLetter() || it == '\'' }
        if (normalised.isEmpty()) return ""

        val out = StringBuilder()
        var index = 0
        while (index < normalised.length) {
            val candidates = BY_FIRST_LETTER[normalised[index]].orEmpty()
            val applied = candidates.firstOrNull { rule ->
                normalised.startsWith(rule.focus, index) &&
                    matchesLeft(normalised, index, rule.left) &&
                    matchesRight(normalised, index + rule.focus.length, rule.right)
            }
            if (applied == null) {
                index++
                continue
            }
            out.append(applied.output)
            index += applied.focus.length
        }

        val phonemes = out.toString()
        if (phonemes.isEmpty()) return ""
        // Stress the first syllable, which is right for the great majority of English words
        // and much better than leaving a word entirely unstressed.
        return "ˈ$phonemes"
    }

    private fun matchesLeft(word: String, focusStart: Int, pattern: String): Boolean {
        if (pattern.isEmpty()) return true
        var patternIndex = pattern.length - 1
        var wordIndex = focusStart - 1
        while (patternIndex >= 0) {
            when (val symbol = pattern[patternIndex]) {
                '$' -> return wordIndex < 0
                '#' -> {
                    if (wordIndex < 0 || word[wordIndex] !in VOWELS) return false
                    while (wordIndex >= 0 && word[wordIndex] in VOWELS) wordIndex--
                }

                '^' -> {
                    if (wordIndex < 0 || word[wordIndex] !in CONSONANTS) return false
                    wordIndex--
                }

                ':' -> while (wordIndex >= 0 && word[wordIndex] in CONSONANTS) wordIndex--
                '+' -> {
                    if (wordIndex < 0 || word[wordIndex] !in "eiy") return false
                    wordIndex--
                }

                else -> {
                    if (wordIndex < 0 || word[wordIndex] != symbol) return false
                    wordIndex--
                }
            }
            patternIndex--
        }
        return true
    }

    private fun matchesRight(word: String, focusEnd: Int, pattern: String): Boolean {
        if (pattern.isEmpty()) return true
        var patternIndex = 0
        var wordIndex = focusEnd
        while (patternIndex < pattern.length) {
            when (val symbol = pattern[patternIndex]) {
                '$' -> return wordIndex >= word.length
                '#' -> {
                    if (wordIndex >= word.length || word[wordIndex] !in VOWELS) return false
                    while (wordIndex < word.length && word[wordIndex] in VOWELS) wordIndex++
                }

                '^' -> {
                    if (wordIndex >= word.length || word[wordIndex] !in CONSONANTS) return false
                    wordIndex++
                }

                ':' -> while (wordIndex < word.length && word[wordIndex] in CONSONANTS) wordIndex++
                '+' -> {
                    if (wordIndex >= word.length || word[wordIndex] !in "eiy") return false
                    wordIndex++
                }

                else -> {
                    if (wordIndex >= word.length || word[wordIndex] != symbol) return false
                    wordIndex++
                }
            }
            patternIndex++
        }
        return true
    }
}
