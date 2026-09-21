package app.soundbound.core.text

/** One sentence, addressed by offsets into the text it was cut from. */
data class Sentence(
    val start: Int,
    val end: Int,
    val text: String,
    /** True when this unit was produced by splitting an over-long sentence for synthesis. */
    val isContinuation: Boolean = false,
    /** Suggested silence after this unit, in milliseconds. */
    val trailingPauseMillis: Int = 0,
) {
    val length: Int get() = end - start
}

/** Tuning for [SentenceSegmenter]. */
data class SegmentationOptions(
    /**
     * Longest unit handed to the synthesiser. Neural voices lose the thread of prosody and
     * start drifting off-pitch on very long inputs, so we cap them — at a clause boundary
     * where possible, so the seam is inaudible.
     */
    val maxUnitCharacters: Int = 420,
    /** Below this, a fragment is glued onto its neighbour rather than spoken alone. */
    val minUnitCharacters: Int = 12,
    val pauseAfterSentenceMillis: Int = 160,
    val pauseAfterParagraphMillis: Int = 420,
    val pauseAfterHeadingMillis: Int = 700,
)

/**
 * Splits prose into speakable units.
 *
 * Full stops are ambiguous in English — they end sentences, mark abbreviations, sit inside
 * decimals and separate initials — so the segmenter looks at what surrounds each candidate
 * before committing. Getting this wrong is instantly audible: the voice either charges
 * through a full stop or takes a breath in the middle of "Mr. Darcy".
 */
class SentenceSegmenter(private val options: SegmentationOptions = SegmentationOptions()) {

    fun segment(text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()

        val rawSentences = ArrayList<Sentence>()
        var start = 0
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            // A blank line always ends a unit: it is a paragraph break, and the parsers put
            // one between every block.
            if (ch == '\n' && index + 1 < text.length && text[index + 1] == '\n') {
                addSentence(rawSentences, text, start, index, options.pauseAfterParagraphMillis)
                index = skipWhitespaceFrom(text, index)
                start = index
                continue
            }

            if (ch !in TERMINATORS) {
                index++
                continue
            }

            val boundaryEnd = findBoundaryEnd(text, index)
            if (boundaryEnd == null) {
                index++
                continue
            }

            // A sentence that is also the end of its paragraph earns the longer pause, so
            // look at the whitespace that follows before deciding.
            val afterWhitespace = skipWhitespaceFrom(text, boundaryEnd)
            val endsParagraph = text.substring(boundaryEnd, afterWhitespace.coerceAtMost(text.length))
                .count { it == '\n' } >= 2 || afterWhitespace >= text.length
            addSentence(
                rawSentences,
                text,
                start,
                boundaryEnd,
                if (endsParagraph) options.pauseAfterParagraphMillis else options.pauseAfterSentenceMillis,
            )
            index = afterWhitespace
            start = index
        }

        if (start < text.length) {
            addSentence(rawSentences, text, start, text.length, options.pauseAfterParagraphMillis)
        }

        return rawSentences
            .let(::mergeTinyFragments)
            .flatMap(::splitIfTooLong)
    }

    /**
     * Returns the offset just past the sentence-ending punctuation (including any closing
     * quotes and brackets), or null if this terminator does not actually end a sentence.
     */
    private fun findBoundaryEnd(text: String, terminatorIndex: Int): Int? {
        var end = terminatorIndex + 1

        // Absorb a run of terminators: "?!" and "..." both end one sentence, not three.
        while (end < text.length && text[end] in TERMINATORS) end++
        // Absorb closing quotes, brackets and the odd footnote marker.
        while (end < text.length && text[end] in CLOSERS) end++

        if (end >= text.length) return end

        val terminator = text[terminatorIndex]
        if (terminator == '.' && !isRealFullStop(text, terminatorIndex, end)) return null

        // There must be whitespace after the punctuation, or it is mid-token ("3.14", "U.K.").
        if (!text[end].isWhitespace()) return null

        val nextIndex = nextNonWhitespace(text, end) ?: return end
        val next = text[nextIndex]

        // A lower-case continuation means the punctuation was not a boundary — as in
        // `"Stop!" he said.` — unless a blank line intervened.
        val blankLineBetween = text.substring(end, nextIndex).count { it == '\n' } >= 2
        if (!blankLineBetween && next.isLowerCase()) return null

        return end
    }

    private fun isRealFullStop(text: String, dotIndex: Int, afterPunctuation: Int): Boolean {
        // A decimal point: digit on both sides.
        if (dotIndex > 0 && text[dotIndex - 1].isDigit() &&
            afterPunctuation < text.length && text[afterPunctuation].isDigit()
        ) {
            return false
        }

        // A single initial: "J. R. R. Tolkien".
        if (dotIndex >= 1 && text[dotIndex - 1].isUpperCase() &&
            (dotIndex == 1 || !text[dotIndex - 2].isLetter())
        ) {
            return false
        }

        // A known abbreviation immediately before the stop.
        val wordStart = run {
            var i = dotIndex
            while (i > 0 && (text[i - 1].isLetter() || text[i - 1] == '.')) i--
            i
        }
        val word = text.substring(wordStart, dotIndex)
        if (word.isNotEmpty()) {
            if (word in NON_TERMINAL_ABBREVIATIONS) return false
            if (word.lowercase() in NON_TERMINAL_ABBREVIATIONS_LOWER) return false
            // A dotted acronym such as "U.S." or "B.B.C.".
            if (word.length >= 2 && word.contains('.') && word.all { it.isUpperCase() || it == '.' }) {
                // It is still a boundary if what follows starts a new sentence properly, so
                // only decline when the next word is lower case — handled by the caller.
                val next = nextNonWhitespace(text, afterPunctuation)
                if (next != null && !text[next].isUpperCase()) return false
            }
        }
        return true
    }

    private fun addSentence(out: MutableList<Sentence>, text: String, start: Int, end: Int, pause: Int) {
        var from = start
        var to = end
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        if (to <= from) return
        out.add(Sentence(from, to, text.substring(from, to), trailingPauseMillis = pause))
    }

    /** First non-whitespace offset at or after [from]; the text length if there is none. */
    private fun skipWhitespaceFrom(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun nextNonWhitespace(text: String, from: Int): Int? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length) i else null
    }

    /** Glues stray fragments ("Yes." on its own) onto the following unit so speech flows. */
    private fun mergeTinyFragments(sentences: List<Sentence>): List<Sentence> {
        if (sentences.size < 2) return sentences
        val out = ArrayList<Sentence>(sentences.size)
        var pending: Sentence? = null

        sentences.forEach { sentence ->
            val previous = pending
            val merged = if (previous != null) {
                Sentence(
                    start = previous.start,
                    end = sentence.end,
                    text = previous.text + " " + sentence.text,
                    trailingPauseMillis = sentence.trailingPauseMillis,
                )
            } else {
                sentence
            }
            pending = null

            if (merged.text.length < options.minUnitCharacters &&
                merged.trailingPauseMillis < options.pauseAfterParagraphMillis
            ) {
                pending = merged
            } else {
                out.add(merged)
            }
        }
        pending?.let(out::add)
        return out
    }

    /**
     * Splits an over-long sentence at the most natural clause boundary available, preferring
     * a semicolon, then a colon, then a dash, then a comma, then a space. The split is only
     * ever made in the middle third or so of the unit, which keeps both halves substantial.
     */
    private fun splitIfTooLong(sentence: Sentence): List<Sentence> {
        if (sentence.text.length <= options.maxUnitCharacters) return listOf(sentence)

        val parts = ArrayList<Sentence>()
        var remainingStart = sentence.start
        var remainingText = sentence.text

        while (remainingText.length > options.maxUnitCharacters) {
            val limit = options.maxUnitCharacters
            val searchFrom = (limit * 0.45).toInt()
            val cut = CLAUSE_BREAKERS.firstNotNullOfOrNull { breaker ->
                remainingText.lastIndexOf(breaker, limit).takeIf { it >= searchFrom }
                    ?.plus(breaker.length)
            } ?: remainingText.lastIndexOf(' ', limit).takeIf { it >= searchFrom }?.plus(1)
            ?: limit

            val head = remainingText.substring(0, cut).trimEnd()
            if (head.isEmpty()) break
            parts.add(
                Sentence(
                    start = remainingStart,
                    end = remainingStart + head.length,
                    text = head,
                    isContinuation = parts.isNotEmpty(),
                    // A clause seam gets a short breath, not a full sentence pause.
                    trailingPauseMillis = 90,
                ),
            )
            val consumed = remainingText.substring(0, cut).length
            val skipped = remainingText.substring(cut).takeWhile { it.isWhitespace() }.length
            remainingStart += consumed + skipped
            remainingText = remainingText.substring(cut + skipped)
        }

        if (remainingText.isNotBlank()) {
            parts.add(
                Sentence(
                    start = remainingStart,
                    end = remainingStart + remainingText.length,
                    text = remainingText,
                    isContinuation = parts.isNotEmpty(),
                    trailingPauseMillis = sentence.trailingPauseMillis,
                ),
            )
        }
        return parts.ifEmpty { listOf(sentence) }
    }

    private companion object {
        val TERMINATORS = charArrayOf('.', '!', '?', '…', '。', '！', '？').toSet()
        val CLOSERS = charArrayOf('"', '\'', '’', '”', ')', ']', '}', '»', '›').toSet()
        val CLAUSE_BREAKERS = listOf("; ", ": ", " — ", " – ", ", ")

        /** Words which, followed by a full stop, are almost never the end of a sentence. */
        val NON_TERMINAL_ABBREVIATIONS = setOf(
            "Mr", "Mrs", "Ms", "Dr", "Prof", "Rev", "Hon", "Sgt", "Capt", "Lt", "Col", "Gen",
            "Jr", "Sr", "St", "Ave", "Rd", "Blvd", "Ltd", "Inc", "Co", "Corp", "Dept",
            "Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sept", "Sep", "Oct", "Nov", "Dec",
            "Mon", "Tue", "Tues", "Wed", "Thu", "Thur", "Thurs", "Fri", "Sat", "Sun",
            "Vol", "Ch", "Fig", "Sec", "Ed", "No", "Op", "Ref", "Est",
        )

        val NON_TERMINAL_ABBREVIATIONS_LOWER = setOf(
            "e.g", "i.e", "cf", "viz", "etc", "al", "ibid", "approx", "vs", "pp", "ed", "vol",
            "fig", "no", "op", "ref", "est", "min", "max", "approx",
        )
    }
}
