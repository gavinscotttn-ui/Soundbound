package app.soundbound.core.text

/**
 * The result of normalising a run of text for speech.
 *
 * [segments] map spoken characters back to the characters they came from, which is what makes
 * word-accurate highlighting possible even though "£12.50" is spoken as five words.
 */
data class NormalisedText(
    val spoken: String,
    val segments: List<TextSegment>,
    val source: String,
) {
    /** Maps a character offset in [spoken] back to an offset in [source]. */
    fun sourceOffsetOf(spokenOffset: Int): Int {
        if (segments.isEmpty()) return 0
        val index = segments.binarySearchBy(spokenOffset) { it.spokenEnd - 1 }
            .let { if (it < 0) -it - 1 else it }
            .coerceIn(0, segments.lastIndex)
        val segment = segments[index]
        return if (segment.verbatim) {
            segment.sourceStart + (spokenOffset - segment.spokenStart)
        } else {
            segment.sourceStart
        }
    }

    /**
     * Maps a character range in [source] onto the matching range of [spoken].
     *
     * Inside a verbatim segment the mapping is exact, character for character. A substituted
     * segment has no interior mapping — "£12.50" became four words — so any overlap with one
     * widens the result to that whole segment, which is what a highlighter wants anyway.
     */
    fun spokenRangeOf(sourceStart: Int, sourceEnd: Int): IntRange? {
        if (sourceEnd <= sourceStart) return null
        val overlapping = segments.filter { it.sourceStart < sourceEnd && it.sourceEnd > sourceStart }
        if (overlapping.isEmpty()) return null

        val first = overlapping.first()
        val last = overlapping.last()
        val from = if (first.verbatim) {
            first.spokenStart + (sourceStart - first.sourceStart).coerceAtLeast(0)
        } else {
            first.spokenStart
        }
        val to = if (last.verbatim) {
            last.spokenEnd - (last.sourceEnd - sourceEnd).coerceAtLeast(0)
        } else {
            last.spokenEnd
        }
        return if (to <= from) null else from until to
    }

    companion object {
        fun passthrough(text: String) = NormalisedText(
            spoken = text,
            segments = listOf(TextSegment(0, text.length, 0, text.length, verbatim = true)),
            source = text,
        )
    }
}

/** One contiguous mapping between source characters and spoken characters. */
data class TextSegment(
    val sourceStart: Int,
    val sourceEnd: Int,
    val spokenStart: Int,
    val spokenEnd: Int,
    /** True when the two ranges are character-for-character identical. */
    val verbatim: Boolean,
)

/** Builds a [NormalisedText], keeping the source mapping honest as it goes. */
internal class NormalisationBuilder(private val source: String) {
    private val spoken = StringBuilder(source.length + 32)
    private val segments = ArrayList<TextSegment>()

    fun appendVerbatim(from: Int, to: Int) {
        if (to <= from) return
        val start = spoken.length
        spoken.append(source, from, to)
        mergeOrAdd(TextSegment(from, to, start, spoken.length, verbatim = true))
    }

    fun appendReplacement(from: Int, to: Int, replacement: String) {
        if (replacement.isEmpty()) {
            // Still record the elision so offsets on either side stay correct.
            mergeOrAdd(TextSegment(from, to, spoken.length, spoken.length, verbatim = false))
            return
        }
        val needsSpace = spoken.isNotEmpty() &&
            !spoken.last().isWhitespace() &&
            replacement.first().isLetterOrDigit() &&
            spoken.last().isLetterOrDigit()
        if (needsSpace) spoken.append(' ')
        val start = spoken.length
        spoken.append(replacement)
        mergeOrAdd(TextSegment(from, to, start, spoken.length, verbatim = false))
    }

    private fun mergeOrAdd(segment: TextSegment) {
        val last = segments.lastOrNull()
        if (last != null && last.verbatim && segment.verbatim &&
            last.sourceEnd == segment.sourceStart && last.spokenEnd == segment.spokenStart
        ) {
            segments[segments.lastIndex] = last.copy(
                sourceEnd = segment.sourceEnd,
                spokenEnd = segment.spokenEnd,
            )
        } else {
            segments.add(segment)
        }
    }

    fun build(): NormalisedText = NormalisedText(spoken.toString(), segments, source)
}
