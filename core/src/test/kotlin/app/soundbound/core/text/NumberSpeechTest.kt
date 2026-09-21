package app.soundbound.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NumberSpeechTest {

    @Test
    fun `cardinals use British and`() {
        assertEquals("zero", NumberSpeech.cardinal(0))
        assertEquals("seventeen", NumberSpeech.cardinal(17))
        assertEquals("twenty-one", NumberSpeech.cardinal(21))
        assertEquals("one hundred", NumberSpeech.cardinal(100))
        assertEquals("one hundred and five", NumberSpeech.cardinal(105))
        assertEquals("three hundred and forty-two", NumberSpeech.cardinal(342))
        assertEquals("one thousand and one", NumberSpeech.cardinal(1001))
        assertEquals("one thousand two hundred and five", NumberSpeech.cardinal(1205))
        assertEquals("two million and three", NumberSpeech.cardinal(2_000_003))
        assertEquals("minus forty-two", NumberSpeech.cardinal(-42))
    }

    @Test
    fun `ordinals handle the irregular ones`() {
        assertEquals("first", NumberSpeech.ordinal(1))
        assertEquals("second", NumberSpeech.ordinal(2))
        assertEquals("third", NumberSpeech.ordinal(3))
        assertEquals("fifth", NumberSpeech.ordinal(5))
        assertEquals("eighth", NumberSpeech.ordinal(8))
        assertEquals("ninth", NumberSpeech.ordinal(9))
        assertEquals("twelfth", NumberSpeech.ordinal(12))
        assertEquals("twentieth", NumberSpeech.ordinal(20))
        assertEquals("twenty-first", NumberSpeech.ordinal(21))
        assertEquals("fourth", NumberSpeech.ordinal(4))
        assertEquals("one hundredth", NumberSpeech.ordinal(100))
    }

    @Test
    fun `years read like years`() {
        assertEquals("nineteen eighty-four", NumberSpeech.year(1984))
        assertEquals("nineteen hundred", NumberSpeech.year(1900))
        assertEquals("nineteen oh five", NumberSpeech.year(1905))
        assertEquals("two thousand and seven", NumberSpeech.year(2007))
        assertEquals("twenty twenty-six", NumberSpeech.year(2026))
        assertEquals("eighteen twelve", NumberSpeech.year(1812))
    }

    @Test
    fun `decimals are read digit by digit after the point`() {
        assertEquals("three point one four", NumberSpeech.decimal("3.14"))
        assertEquals("nought point five", NumberSpeech.decimal("0.5"))
        assertEquals("minus two point nought one", NumberSpeech.decimal("-2.01"))
        assertEquals("one thousand two hundred and thirty-four point five",
            NumberSpeech.decimal("1234.5"))
    }

    @Test
    fun `fractions use the familiar names`() {
        assertEquals("a half", NumberSpeech.fraction(1, 2))
        assertEquals("three quarters", NumberSpeech.fraction(3, 4))
        assertEquals("two thirds", NumberSpeech.fraction(2, 3))
        assertEquals("five sixths", NumberSpeech.fraction(5, 6))
        assertEquals("seven twelfths", NumberSpeech.fraction(7, 12))
    }

    @Test
    fun `roman numerals round trip and reject nonsense`() {
        assertEquals(4, NumberSpeech.romanToInt("IV"))
        assertEquals(8, NumberSpeech.romanToInt("VIII"))
        assertEquals(1984, NumberSpeech.romanToInt("MCMLXXXIV"))
        assertNull(NumberSpeech.romanToInt("IIII"))
        assertNull(NumberSpeech.romanToInt("banana"))
        assertNull(NumberSpeech.romanToInt(""))
    }

    @Test
    fun `clock times`() {
        assertEquals("nine o'clock", NumberSpeech.time(9, 0))
        assertEquals("two oh five", NumberSpeech.time(14, 5))
        assertEquals("eleven forty-five", NumberSpeech.time(23, 45))
        assertEquals("twelve thirty", NumberSpeech.time(0, 30))
    }
}
