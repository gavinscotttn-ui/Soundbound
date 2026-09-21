package app.soundbound.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextNormaliserTest {

    private val normaliser = TextNormaliser()

    private fun say(text: String) = normaliser.normaliseToString(text).replace(Regex("\\s+"), " ").trim()

    @Test
    fun `currency reads as a narrator would say it`() {
        assertEquals("twelve pounds fifty", say("£12.50"))
        assertEquals("one pound", say("£1"))
        assertEquals("ninety-nine pence", say("£0.99"))
        assertEquals("three million pounds", say("£3 million"))
        assertEquals("forty dollars", say("\$40"))
    }

    @Test
    fun `percentages and temperatures`() {
        assertEquals("forty-five per cent", say("45%"))
        assertEquals("nought point five per cent", say("0.5%"))
        assertEquals("twenty-one degrees Celsius", say("21°C"))
    }

    @Test
    fun `ordinals and plain numbers`() {
        assertEquals("twenty-first", say("21st"))
        assertEquals("third", say("3rd"))
        assertEquals("one thousand two hundred and thirty-four", say("1,234"))
        assertEquals("nineteen eighty-four", say("1984"))
        assertEquals("forty-two", say("42"))
    }

    @Test
    fun `year ranges`() {
        assertEquals("nineteen thirty-nine to forty-five", say("1939–45"))
    }

    @Test
    fun `titles are expanded`() {
        assertEquals("Mister Darcy", say("Mr. Darcy"))
        assertEquals("Doctor Watson", say("Dr Watson"))
        assertEquals("Saint Paul", say("St. Paul"))
    }

    @Test
    fun `latin abbreviations`() {
        assertEquals("for example, a badger", say("e.g. a badger"))
        assertEquals("that is, a badger", say("i.e. a badger"))
    }

    @Test
    fun `symbols become words`() {
        assertEquals("fish and chips", say("fish & chips"))
        assertEquals("Marks and Spencer", say("Marks & Spencer"))
    }

    @Test
    fun `roman numerals after a label`() {
        assertEquals("Chapter four", say("Chapter IV"))
        assertEquals("Henry the Eighth", say("Henry VIII"))
    }

    @Test
    fun `acronyms are spelled out but real words are not`() {
        assertEquals("B B C", say("BBC"))
        assertEquals("NASA", say("NASA"))
    }

    @Test
    fun `the source mapping survives expansion`() {
        val source = "He paid £12.50 for it."
        val result = normaliser.normalise(source)
        assertTrue(result.spoken.contains("twelve pounds fifty"))

        // The word "paid" is verbatim, so its offsets must map straight through.
        val paidStart = source.indexOf("paid")
        val spokenRange = result.spokenRangeOf(paidStart, paidStart + 4)!!
        assertEquals("paid", result.spoken.substring(spokenRange.first, spokenRange.last + 1))

        // An offset inside the expanded currency must map back into the original "£12.50".
        val expandedAt = result.spoken.indexOf("pounds")
        val back = result.sourceOffsetOf(expandedAt)
        assertTrue(back >= source.indexOf("£") && back <= source.indexOf("£") + "£12.50".length) {
            "Expected an offset inside the currency token, got $back"
        }
    }

    @Test
    fun `web addresses are dropped by default and read when asked`() {
        assertEquals("See .", say("See https://example.com/thing."))
        val verbose = TextNormaliser(NormalisationOptions(readWebAddresses = true))
        assertTrue(verbose.normaliseToString("https://example.com").contains("example dot com"))
    }

    @Test
    fun `ordinary prose is left completely alone`() {
        val prose = "It is a truth universally acknowledged, that a single man in possession " +
            "of a good fortune, must be in want of a wife."
        assertEquals(prose, normaliser.normaliseToString(prose))
    }

    @Test
    fun `decorative symbols are stripped`() {
        assertFalse(say("A chapter ✦ break").contains("✦"))
    }
}
