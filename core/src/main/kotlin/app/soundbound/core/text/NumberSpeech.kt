package app.soundbound.core.text

import kotlin.math.abs

/**
 * Turns numbers into the words a narrator would actually say.
 *
 * British conventions throughout: "one hundred and five", not "one hundred five". The
 * distinction matters more than it sounds — a neural voice reproduces the rhythm of whatever
 * it is given, so getting the words right is most of getting the delivery right.
 */
object NumberSpeech {

    private val UNITS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )

    private val TENS = listOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    private val SCALES = listOf(
        1_000_000_000_000_000_000L to "quintillion",
        1_000_000_000_000_000L to "quadrillion",
        1_000_000_000_000L to "trillion",
        1_000_000_000L to "billion",
        1_000_000L to "million",
        1_000L to "thousand",
    )

    private val ORDINAL_UNITS = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
        "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
    )

    /** Cardinal: 1,205 becomes "one thousand two hundred and five". */
    fun cardinal(value: Long): String {
        if (value == Long.MIN_VALUE) return "minus " + cardinal(Long.MAX_VALUE)
        if (value < 0) return "minus " + cardinal(-value)
        if (value < 20) return UNITS[value.toInt()]
        if (value < 100) {
            val tens = TENS[(value / 10).toInt()]
            val remainder = (value % 10).toInt()
            return if (remainder == 0) tens else "$tens-${UNITS[remainder]}"
        }
        if (value < 1_000) {
            val hundreds = UNITS[(value / 100).toInt()] + " hundred"
            val remainder = value % 100
            return if (remainder == 0L) hundreds else "$hundreds and ${cardinal(remainder)}"
        }

        SCALES.forEach { (scale, name) ->
            if (value >= scale) {
                val count = value / scale
                val remainder = value % scale
                val head = "${cardinal(count)} $name"
                return when {
                    remainder == 0L -> head
                    // "and" before a remainder under a hundred, as one would say it aloud.
                    remainder < 100 -> "$head and ${cardinal(remainder)}"
                    else -> "$head ${cardinal(remainder)}"
                }
            }
        }
        return value.toString()
    }

    /** Ordinal: 21 becomes "twenty-first". */
    fun ordinal(value: Long): String {
        val words = cardinal(value)
        val lastWord = words.split(' ', '-').last()
        ORDINAL_UNITS[lastWord]?.let { return words.dropLast(lastWord.length) + it }
        if (lastWord.endsWith("y")) return words.dropLast(1) + "ieth"
        if (lastWord == "zero") return "zeroth"
        return words + "th"
    }

    /**
     * Years read the way people say them: 1984 is "nineteen eighty-four", 2007 is
     * "two thousand and seven", 1900 is "nineteen hundred".
     */
    fun year(value: Int): String {
        if (value < 1000 || value > 9999) return cardinal(value.toLong())
        val high = value / 100
        val low = value % 100
        return when {
            value in 2000..2009 -> cardinal(value.toLong())
            low == 0 -> "${cardinal(high.toLong())} hundred"
            low < 10 -> "${cardinal(high.toLong())} oh ${UNITS[low]}"
            else -> "${cardinal(high.toLong())} ${cardinal(low.toLong())}"
        }
    }

    /** Reads a decimal digit by digit after the point, as a narrator would: "three point one four". */
    fun decimal(text: String): String {
        val negative = text.startsWith("-") || text.startsWith("−")
        val body = text.trimStart('-', '−')
        val integerPart = body.substringBefore('.').ifEmpty { "0" }
        val fractionPart = body.substringAfter('.', "")
        val integerWords = integerPart.replace(",", "").toLongOrNull()
            ?.let(::cardinal)
            ?: digits(integerPart)
        val prefix = if (negative) "minus " else ""
        if (fractionPart.isEmpty()) return prefix + integerWords
        // "nought point five" rather than "zero point five": the British reading, and the one
        // that matches how `digits` reads a zero elsewhere.
        val head = if (integerWords == "zero") "nought" else integerWords
        return prefix + head + " point " + digits(fractionPart)
    }

    /** Reads a run of digits one at a time: "nought seven nine one". */
    fun digits(text: String): String = text.asSequence()
        .filter { it.isDigit() }
        .joinToString(" ") { digit -> if (digit == '0') "nought" else UNITS[digit - '0'] }

    /** Common vulgar fractions, plus the general n/m case. */
    fun fraction(numerator: Long, denominator: Long): String {
        if (denominator == 0L) return "${cardinal(numerator)} over zero"
        NAMED_FRACTIONS["$numerator/$denominator"]?.let { return it }
        val denominatorWord = when (denominator) {
            2L -> "half"
            4L -> "quarter"
            else -> ordinal(denominator)
        }
        val plural = if (abs(numerator) == 1L) denominatorWord else pluralise(denominatorWord)
        return "${cardinal(numerator)} $plural"
    }

    private val NAMED_FRACTIONS = mapOf(
        "1/2" to "a half",
        "1/3" to "a third",
        "2/3" to "two thirds",
        "1/4" to "a quarter",
        "3/4" to "three quarters",
    )

    private fun pluralise(word: String): String = when {
        word.endsWith("half") -> word.dropLast(4) + "halves"
        word.endsWith("s") || word.endsWith("x") || word.endsWith("ch") -> word + "es"
        else -> word + "s"
    }

    /** Roman numerals, with a sanity check so that "I" and "MIX" are not mangled. */
    fun romanToInt(text: String): Int? {
        if (text.isEmpty() || !ROMAN.matches(text.uppercase())) return null
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var previous = 0
        text.uppercase().reversed().forEach { ch ->
            val value = values[ch] ?: return null
            if (value < previous) total -= value else { total += value; previous = value }
        }
        return total.takeIf { it in 1..3999 }
    }

    private val ROMAN = Regex("^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")

    /** Clock times: 14:05 becomes "two oh five", 09:00 becomes "nine o'clock". */
    fun time(hours: Int, minutes: Int, seconds: Int? = null): String {
        val hour12 = when {
            hours % 12 == 0 -> 12
            else -> hours % 12
        }
        val head = when {
            minutes == 0 && seconds == null -> "${cardinal(hour12.toLong())} o'clock"
            minutes < 10 -> "${cardinal(hour12.toLong())} oh ${cardinal(minutes.toLong())}"
            else -> "${cardinal(hour12.toLong())} ${cardinal(minutes.toLong())}"
        }
        val suffix = when {
            seconds != null -> " and ${cardinal(seconds.toLong())} seconds"
            hours < 12 && minutes != 0 -> ""
            else -> ""
        }
        return head + suffix
    }
}
