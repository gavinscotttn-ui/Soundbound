package app.soundbound.core.text

/** Knobs for [TextNormaliser]. Exposed in the app's speech settings. */
data class NormalisationOptions(
    val expandNumbers: Boolean = true,
    val expandAbbreviations: Boolean = true,
    val expandSymbols: Boolean = true,
    val spellOutAcronyms: Boolean = true,
    /** Read a bare four-digit number in 1100–2099 as a year ("nineteen eighty-four"). */
    val readYearsAsYears: Boolean = true,
    /** Say URLs and email addresses in full rather than skipping them. */
    val readWebAddresses: Boolean = false,
    /** Strip emoji and decorative symbols that have no spoken form. */
    val stripDecorativeSymbols: Boolean = true,
    /** Currency symbol to assume for a bare figure. Only used for ambiguous cases. */
    val currencyHint: String = "GBP",
)

/**
 * Rewrites text into something a synthesiser can say properly.
 *
 * A neural TTS model will happily read "£12.50" as "pound twelve point five zero" or, worse,
 * silently drop the symbol. Every rule here exists because some real book broke without it.
 * Rules are tried in priority order at each position; anything unmatched passes through
 * untouched, and the source mapping is preserved throughout so the reader can still highlight
 * the exact words being spoken.
 */
class TextNormaliser(private val options: NormalisationOptions = NormalisationOptions()) {

    fun normalise(text: String): NormalisedText {
        if (text.isEmpty()) return NormalisedText.passthrough(text)

        val builder = NormalisationBuilder(text)
        var index = 0
        var verbatimStart = 0

        while (index < text.length) {
            val replacement = matchRule(text, index)
            if (replacement == null) {
                index++
                continue
            }
            builder.appendVerbatim(verbatimStart, index)
            builder.appendReplacement(index, replacement.end, replacement.spoken)
            index = replacement.end
            verbatimStart = index
        }
        builder.appendVerbatim(verbatimStart, text.length)
        return builder.build()
    }

    /** Convenience for callers that do not need the offset mapping. */
    fun normaliseToString(text: String): String = normalise(text).spoken

    private data class Applied(val end: Int, val spoken: String)

    private fun matchRule(text: String, at: Int): Applied? {
        // Cheap gate: the overwhelming majority of characters in a book start no rule at all.
        val ch = text[at]
        if (!couldStartRule(ch) && !(ch.isLetter() && at.isWordStart(text))) return null

        RULES.forEach { rule ->
            if (!rule.enabled(options)) return@forEach
            val match = rule.pattern.matchAt(text, at) ?: return@forEach
            // A rule must not fire in the middle of a longer word.
            if (rule.wordBoundary && !at.isWordStart(text)) return@forEach
            val spoken = rule.speak(match, options) ?: return@forEach
            return Applied(at + match.value.length, spoken)
        }
        return null
    }

    private fun couldStartRule(ch: Char): Boolean = when {
        ch.isDigit() || ch.isUpperCase() -> true
        ch in SYMBOL_STARTERS || ch in FRACTION_CHARACTERS -> true
        // Typographic punctuation, arrows, box drawing and dingbats.
        ch.code in 0x2010..0x2E7F -> true
        // The high half of a surrogate pair, i.e. an emoji.
        ch.code in 0xD800..0xDBFF -> true
        else -> false
    }

    private fun Int.isWordStart(text: String): Boolean =
        this == 0 || !text[this - 1].isLetterOrDigit()

    private companion object {
        val SYMBOL_STARTERS = "£$€¥₹&@%©®™°×÷§¶№…—–+=<>#~*_/\\|".toSet()

        /**
         * One normalisation rule. `speak` returns null to decline the match, which lets a rule
         * pattern-match broadly and then bail out on a case it cannot handle sensibly.
         */
        class Rule(
            val pattern: Regex,
            val wordBoundary: Boolean = true,
            val enabled: (NormalisationOptions) -> Boolean = { true },
            val speak: (MatchResult, NormalisationOptions) -> String?,
        )

        val CURRENCY_NAMES = mapOf(
            "£" to ("pound" to "pounds"),
            "$" to ("dollar" to "dollars"),
            "€" to ("euro" to "euros"),
            "¥" to ("yen" to "yen"),
            "₹" to ("rupee" to "rupees"),
        )

        val CURRENCY_SUBUNITS = mapOf(
            "£" to ("penny" to "pence"),
            "$" to ("cent" to "cents"),
            "€" to ("cent" to "cents"),
        )

        val MAGNITUDE_WORDS = setOf("million", "billion", "trillion", "thousand", "hundred")

        val UNITS = mapOf(
            "km" to ("kilometre" to "kilometres"),
            "cm" to ("centimetre" to "centimetres"),
            "mm" to ("millimetre" to "millimetres"),
            "m" to ("metre" to "metres"),
            "kg" to ("kilogram" to "kilograms"),
            "g" to ("gram" to "grams"),
            "mg" to ("milligram" to "milligrams"),
            "lb" to ("pound" to "pounds"),
            "lbs" to ("pound" to "pounds"),
            "oz" to ("ounce" to "ounces"),
            "ft" to ("foot" to "feet"),
            "in" to ("inch" to "inches"),
            "mi" to ("mile" to "miles"),
            "yd" to ("yard" to "yards"),
            "ml" to ("millilitre" to "millilitres"),
            "l" to ("litre" to "litres"),
            "hr" to ("hour" to "hours"),
            "hrs" to ("hour" to "hours"),
            "min" to ("minute" to "minutes"),
            "mins" to ("minute" to "minutes"),
            "sec" to ("second" to "seconds"),
            "secs" to ("second" to "seconds"),
            "mph" to ("mile per hour" to "miles per hour"),
            "kph" to ("kilometre per hour" to "kilometres per hour"),
            "kb" to ("kilobyte" to "kilobytes"),
            "mb" to ("megabyte" to "megabytes"),
            "gb" to ("gigabyte" to "gigabytes"),
            "tb" to ("terabyte" to "terabytes"),
        )

        val ABBREVIATIONS = mapOf(
            "Mr" to "Mister", "Mr." to "Mister",
            "Mrs" to "Missus", "Mrs." to "Missus",
            "Ms" to "Miz", "Ms." to "Miz",
            "Dr" to "Doctor", "Dr." to "Doctor",
            "Prof" to "Professor", "Prof." to "Professor",
            "Rev" to "Reverend", "Rev." to "Reverend",
            "Hon" to "Honourable", "Hon." to "Honourable",
            "Sgt" to "Sergeant", "Sgt." to "Sergeant",
            "Capt" to "Captain", "Capt." to "Captain",
            "Lt" to "Lieutenant", "Lt." to "Lieutenant",
            "Col" to "Colonel", "Col." to "Colonel",
            "Gen" to "General", "Gen." to "General",
            "Jr" to "Junior", "Jr." to "Junior",
            "Sr" to "Senior", "Sr." to "Senior",
            "St" to "Saint", "St." to "Saint",
            "Ave" to "Avenue", "Ave." to "Avenue",
            "Rd" to "Road", "Rd." to "Road",
            "Blvd" to "Boulevard", "Blvd." to "Boulevard",
            "Ltd" to "Limited", "Ltd." to "Limited",
            "Inc" to "Incorporated", "Inc." to "Incorporated",
            "Co" to "Company", "Co." to "Company",
            "vs" to "versus", "vs." to "versus",
            "etc" to "et cetera", "etc." to "et cetera",
            "approx" to "approximately", "approx." to "approximately",
            "Est" to "Established", "est." to "estimated",
            "No" to "Number", "No." to "Number",
            "pp" to "pages", "pp." to "pages",
            "ed" to "edition", "ed." to "edition",
            "vol" to "volume", "Vol." to "volume", "vol." to "volume",
            "Fig" to "Figure", "Fig." to "Figure", "fig." to "figure",
            "Sec" to "Section", "Sec." to "Section",
            "Ch" to "Chapter", "Ch." to "Chapter",
            "Jan." to "January", "Feb." to "February", "Mar." to "March",
            "Apr." to "April", "Jun." to "June", "Jul." to "July",
            "Aug." to "August", "Sept." to "September", "Sep." to "September",
            "Oct." to "October", "Nov." to "November", "Dec." to "December",
            "Mon." to "Monday", "Tue." to "Tuesday", "Wed." to "Wednesday",
            "Thu." to "Thursday", "Fri." to "Friday", "Sat." to "Saturday", "Sun." to "Sunday",
        )

        /** Latin abbreviations, which read far better expanded than spelled out. */
        val LATIN = mapOf(
            "e.g." to "for example,",
            "i.e." to "that is,",
            "cf." to "compare",
            "viz." to "namely,",
            "et al." to "and others",
            "ibid." to "in the same place",
            "N.B." to "note well,",
            "a.m." to "a.m.",
            "p.m." to "p.m.",
        )

        val SYMBOLS = mapOf(
            '&' to "and",
            '@' to "at",
            '%' to "per cent",
            '©' to "copyright",
            '®' to "registered",
            '™' to "trade mark",
            '°' to "degrees",
            '×' to "times",
            '÷' to "divided by",
            '§' to "section",
            '¶' to "paragraph",
            '№' to "number",
            '±' to "plus or minus",
            '≈' to "approximately",
            '≠' to "not equal to",
            '≤' to "less than or equal to",
            '≥' to "greater than or equal to",
            '√' to "the square root of",
            '∞' to "infinity",
            '†' to "",
            '‡' to "",
            '•' to "",
            '*' to "",
            '_' to "",
            '~' to "",
            '|' to "",
            '\\' to "",
        )

        /** Acronyms that are read as words rather than spelled out. */
        val PRONOUNCED_ACRONYMS = setOf(
            "NASA", "NATO", "UNICEF", "UNESCO", "OPEC", "AIDS", "SCUBA", "LASER", "RADAR",
            "SONAR", "ASAP", "FAQ", "GIF", "JPEG", "PNG", "RAM", "ROM", "SIM", "WIFI", "WI-FI",
            "COVID", "SARS", "MERS", "PIN", "ATM", "ISO", "IKEA", "OPEC", "SWAT", "POTUS",
        )

        val FRACTION_CHARACTERS = mapOf(
            '½' to "a half", '⅓' to "a third", '⅔' to "two thirds",
            '¼' to "a quarter", '¾' to "three quarters",
            '⅕' to "a fifth", '⅖' to "two fifths", '⅗' to "three fifths", '⅘' to "four fifths",
            '⅙' to "a sixth", '⅚' to "five sixths",
            '⅛' to "an eighth", '⅜' to "three eighths", '⅝' to "five eighths", '⅞' to "seven eighths",
        )

        val MONTHS = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        /** The digits at the head of a match, with any trailing magnitude word removed. */
        private fun numberPartOf(matched: String, magnitude: String?): String =
            if (magnitude.isNullOrEmpty()) matched.trim() else matched.substringBefore(magnitude).trim()

        private fun cardinalWithMagnitude(number: String, magnitude: String?): String {
            val plain = number.replace(",", "")
            val words = if (plain.contains('.')) NumberSpeech.decimal(plain)
            else plain.toLongOrNull()?.let(NumberSpeech::cardinal) ?: NumberSpeech.digits(plain)
            return if (magnitude.isNullOrEmpty()) words else "$words $magnitude"
        }

        val RULES: List<Rule> = listOf(
            // ---------------------------------------------------------------- web
            Rule(
                // The trailing character class excludes punctuation on purpose: swallowing the
                // full stop at the end of "…see https://example.com/thing." would destroy the
                // sentence boundary the segmenter depends on.
                pattern = Regex("""(?:https?://|www\.)[^\s<>()"']*[^\s<>()"'.,;:!?]"""),
                enabled = { true },
            ) { match, opts ->
                if (!opts.readWebAddresses) "" else match.value
                    .replace("https://", "")
                    .replace("http://", "")
                    .replace(".", " dot ")
                    .replace("/", " slash ")
                    .replace("-", " dash ")
                    .replace("_", " underscore ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            },
            Rule(
                pattern = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+"""),
            ) { match, opts ->
                if (!opts.readWebAddresses) "" else match.value
                    .replace("@", " at ")
                    .replace(".", " dot ")
            },

            // ---------------------------------------------------------------- currency
            Rule(
                pattern = Regex("""([£$€¥₹])\s?(\d{1,3}(?:,\d{3})*|\d+)(?:\.(\d{1,2}))?\s*(million|billion|trillion|thousand)?\b"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val symbol = match.groupValues[1]
                val whole = match.groupValues[2].replace(",", "")
                val fraction = match.groupValues[3]
                val magnitude = match.groupValues[4]
                val (singular, plural) = CURRENCY_NAMES[symbol] ?: return@Rule null
                val wholeValue = whole.toLongOrNull() ?: return@Rule null

                if (magnitude.isNotEmpty()) {
                    val amount = if (fraction.isEmpty()) NumberSpeech.cardinal(wholeValue)
                    else NumberSpeech.decimal("$whole.$fraction")
                    return@Rule "$amount $magnitude $plural"
                }

                val wholeWords = NumberSpeech.cardinal(wholeValue)
                val unitWord = if (wholeValue == 1L) singular else plural
                if (fraction.isEmpty()) return@Rule "$wholeWords $unitWord"

                val pence = fraction.padEnd(2, '0').toLong()
                if (pence == 0L) return@Rule "$wholeWords $unitWord"
                val subunit = CURRENCY_SUBUNITS[symbol]
                // "twelve pounds fifty" is how it is said; the subunit name is only needed
                // when there are no whole units at all.
                return@Rule if (wholeValue == 0L && subunit != null) {
                    "${NumberSpeech.cardinal(pence)} ${if (pence == 1L) subunit.first else subunit.second}"
                } else {
                    "$wholeWords $unitWord ${NumberSpeech.cardinal(pence)}"
                }
            },

            // ---------------------------------------------------------------- time
            Rule(
                pattern = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?\s?(a\.?m\.?|p\.?m\.?)?""", RegexOption.IGNORE_CASE),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val hours = match.groupValues[1].toIntOrNull() ?: return@Rule null
                val minutes = match.groupValues[2].toIntOrNull() ?: return@Rule null
                if (hours > 23 || minutes > 59) return@Rule null
                val seconds = match.groupValues[3].toIntOrNull()
                val meridiem = match.groupValues[4].lowercase().replace(".", "")
                val spoken = NumberSpeech.time(hours, minutes, seconds)
                when (meridiem) {
                    "am" -> "$spoken a.m."
                    "pm" -> "$spoken p.m."
                    else -> spoken
                }
            },

            // ---------------------------------------------------------------- dates
            Rule(
                pattern = Regex("""(\d{4})-(\d{2})-(\d{2})"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val year = match.groupValues[1].toInt()
                val month = match.groupValues[2].toIntOrNull() ?: return@Rule null
                val day = match.groupValues[3].toIntOrNull() ?: return@Rule null
                if (month !in 1..12 || day !in 1..31) return@Rule null
                "the ${NumberSpeech.ordinal(day.toLong())} of ${MONTHS[month - 1]} ${NumberSpeech.year(year)}"
            },
            Rule(
                pattern = Regex("""(\d{1,2})(?:st|nd|rd|th)?\s+(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{4})""", RegexOption.IGNORE_CASE),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val day = match.groupValues[1].toIntOrNull() ?: return@Rule null
                val month = match.groupValues[2].replaceFirstChar { it.uppercase() }
                val year = match.groupValues[3].toIntOrNull() ?: return@Rule null
                "the ${NumberSpeech.ordinal(day.toLong())} of $month ${NumberSpeech.year(year)}"
            },

            // ---------------------------------------------------------------- ranges
            Rule(
                pattern = Regex("""(\d{4})\s?[–—-]\s?(\d{2,4})(?![\d/])"""),
                enabled = { it.expandNumbers && it.readYearsAsYears },
            ) { match, _ ->
                val from = match.groupValues[1].toIntOrNull() ?: return@Rule null
                val to = match.groupValues[2]
                if (from !in 1000..2099) return@Rule null
                val toWords = if (to.length == 4) NumberSpeech.year(to.toInt())
                else NumberSpeech.cardinal(to.toLong())
                "${NumberSpeech.year(from)} to $toWords"
            },

            // ---------------------------------------------------------------- percentages and units
            Rule(
                pattern = Regex("""(\d{1,3}(?:,\d{3})*|\d+)(?:\.(\d+))?\s?%"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val value = match.groupValues[1] + if (match.groupValues[2].isEmpty()) "" else ".${match.groupValues[2]}"
                "${cardinalWithMagnitude(value, null)} per cent"
            },
            Rule(
                pattern = Regex("""(\d{1,3}(?:,\d{3})*|\d+)(?:\.(\d+))?\s?°\s?([CF])\b"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val value = match.groupValues[1] + if (match.groupValues[2].isEmpty()) "" else ".${match.groupValues[2]}"
                val scale = if (match.groupValues[3] == "C") "Celsius" else "Fahrenheit"
                "${cardinalWithMagnitude(value, null)} degrees $scale"
            },
            Rule(
                pattern = Regex("""(\d{1,3}(?:,\d{3})*|\d+)(?:\.(\d+))?\s?([a-zA-Z]{1,3})\b"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val unitKey = match.groupValues[3].lowercase()
                val (singular, plural) = UNITS[unitKey] ?: return@Rule null
                // A bare "m" or "l" next to a number is ambiguous ("3 m" could be "3 men"),
                // so single-letter units are only expanded when written without a space.
                if (unitKey.length == 1 && match.value.contains(' ')) return@Rule null
                val value = match.groupValues[1] + if (match.groupValues[2].isEmpty()) "" else ".${match.groupValues[2]}"
                val numeric = value.replace(",", "").toDoubleOrNull()
                val word = if (numeric == 1.0) singular else plural
                "${cardinalWithMagnitude(value, null)} $word"
            },

            // ---------------------------------------------------------------- ordinals and numbers
            Rule(
                pattern = Regex("""(\d{1,3}(?:,\d{3})*|\d+)(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val value = match.groupValues[1].replace(",", "").toLongOrNull() ?: return@Rule null
                NumberSpeech.ordinal(value)
            },
            Rule(
                pattern = Regex("""(\d+)\s*/\s*(\d+)(?![\d/])"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val numerator = match.groupValues[1].toLongOrNull() ?: return@Rule null
                val denominator = match.groupValues[2].toLongOrNull() ?: return@Rule null
                // Guard against dates written as 12/05 and against silly denominators.
                if (denominator > 1000 || denominator == 0L) return@Rule null
                NumberSpeech.fraction(numerator, denominator)
            },
            Rule(
                pattern = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d+)?\s*(million|billion|trillion|thousand)?"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val magnitude = match.groupValues[1].takeIf { it in MAGNITUDE_WORDS }
                cardinalWithMagnitude(numberPartOf(match.value, magnitude), magnitude)
            },
            Rule(
                pattern = Regex("""\d+\.\d+\s*(million|billion|trillion|thousand)?"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val magnitude = match.groupValues[1].takeIf { it in MAGNITUDE_WORDS }
                cardinalWithMagnitude(numberPartOf(match.value, magnitude), magnitude)
            },
            Rule(
                pattern = Regex("""\d+\s*(million|billion|trillion|thousand)\b"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val magnitude = match.groupValues[1]
                cardinalWithMagnitude(numberPartOf(match.value, magnitude), magnitude)
            },
            Rule(
                pattern = Regex("""\d+"""),
                enabled = { it.expandNumbers },
            ) { match, opts ->
                val digits = match.value
                val value = digits.toLongOrNull() ?: return@Rule NumberSpeech.digits(digits)
                when {
                    // Long digit strings are reference numbers, not quantities.
                    digits.length > 9 -> NumberSpeech.digits(digits)
                    opts.readYearsAsYears && digits.length == 4 && value in 1100..2099 &&
                        !digits.startsWith("0") -> NumberSpeech.year(value.toInt())
                    else -> NumberSpeech.cardinal(value)
                }
            },

            // ---------------------------------------------------------------- vulgar fractions
            Rule(
                pattern = Regex("""[½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞]"""),
                wordBoundary = false,
                enabled = { it.expandNumbers },
            ) { match, _ -> FRACTION_CHARACTERS[match.value.first()] },

            // ---------------------------------------------------------------- Latin and abbreviations
            Rule(
                pattern = Regex("""(?:e\.g\.|i\.e\.|cf\.|viz\.|et al\.|ibid\.|N\.B\.)"""),
                enabled = { it.expandAbbreviations },
            ) { match, _ -> LATIN[match.value] },
            Rule(
                pattern = Regex("""[A-Z][a-z]{0,5}\.?"""),
                enabled = { it.expandAbbreviations },
            ) { match, _ -> ABBREVIATIONS[match.value] },
            Rule(
                pattern = Regex("""(?:vs\.?|etc\.|approx\.|pp\.|ed\.|vol\.|fig\.|no\.)""", RegexOption.IGNORE_CASE),
                enabled = { it.expandAbbreviations },
            ) { match, _ ->
                ABBREVIATIONS[match.value] ?: ABBREVIATIONS[match.value.replaceFirstChar { it.uppercase() }]
            },

            // ---------------------------------------------------------------- roman numerals
            Rule(
                pattern = Regex("""(Chapter|Part|Book|Volume|Section|Act|King|Queen|Pope|Henry|Edward|George|William|Richard|Charles|Elizabeth|Louis|Philip|James)\s+([IVXLCDM]{1,8})\b"""),
                enabled = { it.expandNumbers },
            ) { match, _ ->
                val value = NumberSpeech.romanToInt(match.groupValues[2]) ?: return@Rule null
                val label = match.groupValues[1]
                // Monarchs take an ordinal ("Henry the Eighth"); structural divisions take a
                // cardinal ("Chapter Four"), which is how a narrator reads them.
                if (label in MONARCH_LABELS) {
                    "$label the ${NumberSpeech.ordinal(value.toLong()).replaceFirstChar { it.uppercase() }}"
                } else {
                    "$label ${NumberSpeech.cardinal(value.toLong())}"
                }
            },

            // ---------------------------------------------------------------- acronyms
            Rule(
                pattern = Regex("""[A-Z]{2,6}(?:\.[A-Z])?s?\b"""),
                enabled = { it.spellOutAcronyms },
            ) { match, _ ->
                val letters = match.value.trimEnd('s').replace(".", "")
                if (letters in PRONOUNCED_ACRONYMS) return@Rule null
                // A run of capitals that contains a vowel and looks like a word is left alone;
                // shouting is a stylistic choice in prose, not an acronym.
                if (letters.length > 4 && letters.any { it in "AEIOU" }) return@Rule null
                val spelled = letters.map { it }.joinToString(" ")
                if (match.value.endsWith("s")) "$spelled's" else spelled
            },

            // ---------------------------------------------------------------- symbols
            Rule(
                pattern = Regex("""[&@%©®™°×÷§¶№±≈≠≤≥√∞†‡•*_~|\\]"""),
                wordBoundary = false,
                enabled = { it.expandSymbols },
            ) { match, _ -> SYMBOLS[match.value.first()] },
            Rule(
                pattern = Regex("""\.{3,}|…"""),
                wordBoundary = false,
            ) { _, _ -> "…" },
            Rule(
                pattern = Regex("""[─-╿←-⇿☀-➿️\x{1F300}-\x{1FAFF}]+"""),
                wordBoundary = false,
                enabled = { it.stripDecorativeSymbols },
            ) { _, _ -> "" },
        )

        val MONARCH_LABELS = setOf(
            "King", "Queen", "Pope", "Henry", "Edward", "George", "William",
            "Richard", "Charles", "Elizabeth", "Louis", "Philip", "James",
        )
    }
}
