package app.soundbound.core.tts.g2p

/**
 * Converts CMUdict's ARPAbet notation to the IPA that Piper models are trained on.
 *
 * CMUdict is General American; Piper's British voices were trained on espeak-ng's RP output.
 * The [BRITISH] table applies the handful of systematic differences that are actually audible
 * — non-rhoticity, the TRAP–BATH split's vowel, and the LOT vowel — which keeps an English
 * voice from drifting mid-Atlantic when it falls back to the lexicon.
 */
object ArpabetToIpa {

    private val BASE = mapOf(
        // Vowels
        "AA" to "ɑː", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔː", "AW" to "aʊ", "AY" to "aɪ",
        "EH" to "ɛ", "ER" to "ɜː", "EY" to "eɪ", "IH" to "ɪ", "IY" to "iː",
        "OW" to "oʊ", "OY" to "ɔɪ", "UH" to "ʊ", "UW" to "uː",
        // Consonants
        "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð", "F" to "f", "G" to "ɡ",
        "HH" to "h", "JH" to "dʒ", "K" to "k", "L" to "l", "M" to "m", "N" to "n",
        "NG" to "ŋ", "P" to "p", "R" to "ɹ", "S" to "s", "SH" to "ʃ", "T" to "t",
        "TH" to "θ", "V" to "v", "W" to "w", "Y" to "j", "Z" to "z", "ZH" to "ʒ",
    )

    private val BRITISH = BASE + mapOf(
        "AA" to "ɑː",
        "AO" to "ɔː",
        "OW" to "əʊ",
        "ER" to "ɜː",
        "AH" to "ʌ",
    )

    /** Unstressed ARPAbet vowels (stress digit 0) reduce to schwa in connected speech. */
    private val REDUCIBLE = setOf("AH", "IH", "ER", "UH")

    /**
     * @param arpabet space-separated ARPAbet phones, optionally carrying stress digits,
     *   e.g. `HH AH0 L OW1`.
     * @param british true for the RP-leaning table.
     */
    fun convert(arpabet: String, british: Boolean = true): String {
        val table = if (british) BRITISH else BASE
        val phones = ArrayList<Phone>()

        arpabet.trim().split(' ').forEach { rawPhone ->
            if (rawPhone.isEmpty()) return@forEach
            val stress = rawPhone.lastOrNull()?.takeIf { it.isDigit() }
            val phone = if (stress != null) rawPhone.dropLast(1) else rawPhone
            val isVowel = phone in VOWEL_PHONES
            val ipa = when {
                stress == '0' && phone == "ER" -> "ə"
                stress == '0' && phone in REDUCIBLE -> "ə"
                else -> table[phone] ?: return@forEach
            }
            phones.add(Phone(ipa, isVowel, stress))
        }

        return renderWithStress(phones)
    }

    private data class Phone(val ipa: String, val isVowel: Boolean, val stress: Char?)

    /**
     * Places the stress marks.
     *
     * IPA puts the mark at the start of the stressed *syllable*, not immediately before its
     * vowel — "hello" is /həˈləʊ/, not /həlˈəʊ/ — so each mark is walked back over the
     * consonant cluster that forms the syllable's onset. espeak-ng does the same, and since
     * Piper was trained on its output, matching it matters.
     */
    private fun renderWithStress(phones: List<Phone>): String {
        val marks = arrayOfNulls<Char>(phones.size)
        phones.forEachIndexed { index, phone ->
            val mark = when (phone.stress) {
                '1' -> 'ˈ'
                '2' -> 'ˌ'
                else -> null
            } ?: return@forEachIndexed
            if (!phone.isVowel) {
                marks[index] = mark
                return@forEachIndexed
            }
            // Walk back over the onset consonants, stopping at the previous vowel or at a
            // position that already carries a mark.
            var onset = index
            while (onset > 0 && !phones[onset - 1].isVowel && marks[onset - 1] == null) onset--
            marks[onset] = mark
        }

        val out = StringBuilder()
        phones.forEachIndexed { index, phone ->
            marks[index]?.let(out::append)
            out.append(phone.ipa)
        }
        return out.toString()
    }

    private val VOWEL_PHONES = setOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY",
        "IH", "IY", "OW", "OY", "UH", "UW",
    )
}
