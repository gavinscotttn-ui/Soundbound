package app.soundbound.core.tts

import app.soundbound.core.tts.g2p.ArpabetToIpa
import app.soundbound.core.tts.g2p.Lexicon
import app.soundbound.core.tts.g2p.LexiconPhonemizer
import app.soundbound.core.tts.g2p.LetterToSound
import app.soundbound.core.tts.g2p.PhonemeToken
import app.soundbound.core.tts.g2p.TextTokeniser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class G2pTest {

    @Test
    fun `tokenisation keeps punctuation as its own token`() {
        val tokens = TextTokeniser.tokenise("Hello, world!")
        assertEquals(
            listOf("Hello", ",", " ", "world", "!"),
            tokens.map { it.text },
        )
        assertEquals(PhonemeToken.Kind.WORD, tokens[0].kind)
        assertEquals(PhonemeToken.Kind.PUNCTUATION, tokens[1].kind)
        assertEquals(PhonemeToken.Kind.WHITESPACE, tokens[2].kind)
    }

    @Test
    fun `token offsets address the original text`() {
        val text = "One two three"
        TextTokeniser.tokenise(text).forEach { token ->
            if (token.kind == PhonemeToken.Kind.WORD) {
                assertEquals(token.text, text.substring(token.start, token.start + token.text.length))
            }
        }
    }

    @Test
    fun `apostrophes stay inside a word but trailing ones do not`() {
        assertEquals(listOf("don't"), TextTokeniser.tokenise("don't").map { it.text })
        assertEquals(listOf("dogs", "'"), TextTokeniser.tokenise("dogs'").map { it.text })
    }

    @Test
    fun `ARPAbet converts to IPA with stress marks`() {
        assertEquals("həˈləʊ", ArpabetToIpa.convert("HH AH0 L OW1"))
        assertEquals("ˈkæt", ArpabetToIpa.convert("K AE1 T"))
        assertEquals("ˈθɪŋk", ArpabetToIpa.convert("TH IH1 NG K"))
    }

    @Test
    fun `unstressed schwa-able vowels reduce`() {
        // AH0 is a schwa, AH1 is the STRUT vowel.
        assertTrue(ArpabetToIpa.convert("AH0").contains("ə"))
        assertTrue(ArpabetToIpa.convert("AH1").contains("ʌ"))
    }

    @Test
    fun `the built-in lexicon knows the words rules get wrong`() {
        val lexicon = Lexicon.builtIn()
        assertEquals("sɛd", lexicon.lookup("said"))
        assertEquals("wʌns", lexicon.lookup("once"))
        assertEquals("ˈkɜːnəl", lexicon.lookup("colonel"))
        assertEquals("ðə", lexicon.lookup("The")) { "Lookup must be case-insensitive" }
        assertNull(lexicon.lookup("zzzyxqq"))
    }

    @Test
    fun `inflected forms are derived from a known stem`() {
        val lexicon = Lexicon.of(mapOf("walk" to "wɔːk", "place" to "pleɪs"))
        assertEquals("wɔːks", lexicon.lookup("walks")) { "Voiceless stem takes /s/" }
        assertEquals("wɔːkt", lexicon.lookup("walked")) { "Voiceless stem takes /t/" }
        assertEquals("wɔːkɪŋ", lexicon.lookup("walking"))
        assertEquals("pleɪsɪz", lexicon.lookup("places")) { "Sibilant stem takes /ɪz/" }
    }

    @Test
    fun `a lexicon file is parsed including gzip and ARPAbet`() {
        val text = "# soundbound-lexicon 1 arpabet\nhello\tHH AH0 L OW1\nworld\tW ER1 L D\n"
        val plain = Lexicon.load(text.byteInputStream(), "test")
        assertEquals("həˈləʊ", plain.lookup("hello"))

        val gzipped = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val compressed = Lexicon.load(gzipped.inputStream(), "test-gz")
        assertEquals("həˈloʊ".let { compressed.lookup("hello") }, plain.lookup("hello"))
    }

    @Test
    fun `alternative CMUdict pronunciations are ignored`() {
        val text = "# soundbound-lexicon 1 arpabet\nread\tR IY1 D\nread(2)\tR EH1 D\n"
        val lexicon = Lexicon.load(text.byteInputStream())
        assertEquals("ˈɹiːd", lexicon.lookup("read"))
    }

    @Test
    fun `letter to sound handles words the dictionary has never seen`() {
        // Not a real word; the point is that something plausible comes out, with stress.
        val phonemes = LetterToSound.phonemise("Brillingswick")
        assertTrue(phonemes.isNotEmpty())
        assertTrue(phonemes.startsWith("ˈ")) { "Expected a stress mark, got: $phonemes" }
        assertTrue(phonemes.contains("ɹ")) { "Expected an /r/ from the 'r', got: $phonemes" }
    }

    @Test
    fun `letter to sound knows the common digraphs`() {
        assertTrue(LetterToSound.phonemise("ship").contains("ʃ"))
        assertTrue(LetterToSound.phonemise("chip").contains("tʃ"))
        assertTrue(LetterToSound.phonemise("photo").contains("f"))
        assertTrue(LetterToSound.phonemise("think").contains("θ"))
        assertTrue(LetterToSound.phonemise("knight").contains("n"))
    }

    @Test
    fun `the phonemizer prefers the lexicon then falls back to rules`() {
        val phonemizer = LexiconPhonemizer()
        val tokens = phonemizer.phonemise("The zzyzx said hello", "en-GB")
        val words = tokens.filter { it.kind == PhonemeToken.Kind.WORD }
        assertEquals("ðə", words[0].phonemes) { "A dictionary word" }
        assertTrue(words[1].phonemes.isNotEmpty()) { "An unknown word must still get phonemes" }
        assertEquals("sɛd", words[2].phonemes)
    }

    @Test
    fun `user overrides always win`() {
        val phonemizer = LexiconPhonemizer(overrides = mapOf("said" to "ˈseɪd"))
        val words = phonemizer.phonemise("said", "en-GB")
        assertEquals("ˈseɪd", words.first().phonemes)
    }

    @Test
    fun `an initialism with no vowel is spelled out`() {
        val phonemizer = LexiconPhonemizer()
        val phonemes = phonemizer.phonemise("BBC", "en-GB").first().phonemes
        assertTrue(phonemes.contains("ˈbiː")) { "Got: $phonemes" }
    }

    @Test
    fun `the phonemizer only claims to support English`() {
        val phonemizer = LexiconPhonemizer()
        assertTrue(phonemizer.supports("en-GB"))
        assertTrue(phonemizer.supports("en"))
        assertTrue(!phonemizer.supports("de-DE"))
    }

    @Test
    fun `the phoneme string keeps punctuation and spacing`() {
        val phonemizer = LexiconPhonemizer()
        val result = phonemizer.phonemeString("The cat, said one.", "en-GB")
        assertNotNull(result)
        assertTrue(result.contains(",")) { "Got: $result" }
        assertTrue(result.contains(".")) { "Got: $result" }
        assertTrue(result.contains(" ")) { "Got: $result" }
    }
}
