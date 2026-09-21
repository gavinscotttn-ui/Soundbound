package app.soundbound.core.player

import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.text.NormalisationOptions
import app.soundbound.core.text.NormalisedText
import app.soundbound.core.text.SegmentationOptions
import app.soundbound.core.text.SentenceSegmenter
import app.soundbound.core.text.TextNormaliser

/**
 * One utterance: what will be said, where it came from, and how long to wait afterwards.
 *
 * [sourceStart] and [sourceEnd] address the chapter's plain text, so the reader can scroll to
 * and highlight exactly what is being spoken, and a position saved while listening can be
 * resumed while reading.
 */
data class SpeechUnit(
    val index: Int,
    val chapter: ChapterIndex,
    val sourceStart: Int,
    val sourceEnd: Int,
    val displayText: String,
    val normalised: NormalisedText,
    val trailingPauseMillis: Int,
    val blockKind: BlockKind,
) {
    val spokenText: String get() = normalised.spoken

    /** Maps a position within the spoken text back to a range of the original text. */
    fun sourceRangeOf(spokenOffset: Int, spokenLength: Int): IntRange {
        val start = sourceStart + normalised.sourceOffsetOf(spokenOffset.coerceAtLeast(0))
        val end = sourceStart + normalised.sourceOffsetOf((spokenOffset + spokenLength).coerceAtMost(spokenText.length))
        return start until end.coerceAtLeast(start + 1)
    }

    /** Word boundaries in the spoken text, for word-by-word highlighting. */
    fun spokenWordRanges(): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var start = -1
        spokenText.forEachIndexed { index, ch ->
            if (ch.isLetterOrDigit() || ch == '\'' || ch == '’') {
                if (start < 0) start = index
            } else if (start >= 0) {
                ranges.add(start until index)
                start = -1
            }
        }
        if (start >= 0) ranges.add(start until spokenText.length)
        return ranges
    }
}

/** Everything that will be spoken for one chapter, in order. */
data class SpeechPlan(
    val chapter: ChapterIndex,
    val units: List<SpeechUnit>,
    val chapterCharacters: Int,
) {
    val isEmpty: Boolean get() = units.isEmpty()

    /** The unit that contains, or next follows, [characterOffset]. */
    fun unitAtOffset(characterOffset: Int): SpeechUnit? {
        if (units.isEmpty()) return null
        var lo = 0
        var hi = units.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val unit = units[mid]
            when {
                characterOffset < unit.sourceStart -> hi = mid - 1
                characterOffset >= unit.sourceEnd -> lo = mid + 1
                else -> return unit
            }
        }
        return units.getOrNull(lo) ?: units.last()
    }

    fun unitAt(index: Int): SpeechUnit? = units.getOrNull(index)
}

/** How a chapter is turned into utterances. */
data class SpeechPlanOptions(
    val segmentation: SegmentationOptions = SegmentationOptions(),
    val normalisation: NormalisationOptions = NormalisationOptions(),
    /** Read chapter headings aloud. Off for those who find it repetitive. */
    val speakHeadings: Boolean = true,
    /** Announce a footnote's presence rather than ignoring it entirely. */
    val announceFootnotes: Boolean = false,
    /** Read image alt text, which is often the only description a diagram has. */
    val speakImageDescriptions: Boolean = true,
    /** Extra silence before a heading, in milliseconds. */
    val pauseBeforeHeadingMillis: Int = 600,
)

/**
 * Builds a [SpeechPlan] from a parsed chapter.
 *
 * The block structure is used rather than the flat text, because block kind changes how a
 * passage should sound: a heading wants a pause and a slower delivery, a caption wants to be
 * distinguishable from the prose around it, and a page-break marker wants to be silent.
 */
class SpeechPlanBuilder(private val options: SpeechPlanOptions = SpeechPlanOptions()) {

    private val segmenter = SentenceSegmenter(options.segmentation)
    private val normaliser = TextNormaliser(options.normalisation)

    fun build(content: ChapterContent): SpeechPlan {
        val units = ArrayList<SpeechUnit>()

        content.blocks.forEach { block ->
            if (block.isSpeechSkipped) return@forEach
            if (block.kind.isHeading && !options.speakHeadings) return@forEach

            val text = when {
                block.kind == BlockKind.IMAGE ->
                    if (options.speakImageDescriptions) block.imageAlt.orEmpty() else ""

                else -> block.text
            }
            if (text.isBlank()) return@forEach

            // Images carry their description outside the plain text, so they are anchored to
            // the block's own start rather than to an offset inside it.
            val blockStart = if (block.kind == BlockKind.IMAGE) block.textStart else block.textStart

            segmenter.segment(text).forEach { sentence ->
                val normalised = normaliser.normalise(sentence.text)
                if (normalised.spoken.isBlank()) return@forEach

                val pause = when {
                    block.kind.isHeading -> maxOf(sentence.trailingPauseMillis, options.segmentation.pauseAfterHeadingMillis)
                    else -> sentence.trailingPauseMillis
                }

                units.add(
                    SpeechUnit(
                        index = units.size,
                        chapter = content.chapterIndex,
                        sourceStart = blockStart + sentence.start,
                        sourceEnd = blockStart + sentence.end,
                        displayText = sentence.text,
                        normalised = normalised,
                        trailingPauseMillis = pause,
                        blockKind = block.kind,
                    ),
                )
            }

            if (options.announceFootnotes) {
                val notes = block.spans.mapNotNull { it.noteRef }.distinct()
                notes.forEach { ref ->
                    val body = content.notes[ref] ?: return@forEach
                    if (body.isBlank()) return@forEach
                    val normalised = normaliser.normalise("Note: $body")
                    units.add(
                        SpeechUnit(
                            index = units.size,
                            chapter = content.chapterIndex,
                            sourceStart = block.textEnd,
                            sourceEnd = block.textEnd,
                            displayText = body,
                            normalised = normalised,
                            trailingPauseMillis = options.segmentation.pauseAfterParagraphMillis,
                            blockKind = BlockKind.FOOTNOTE,
                        ),
                    )
                }
            }
        }

        return SpeechPlan(content.chapterIndex, units, content.plainText.length)
    }
}
