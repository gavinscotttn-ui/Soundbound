package app.soundbound.core.book

/** Inline emphasis carried by a run of characters inside a [ContentBlock]. */
enum class InlineStyle { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, CODE, SUPERSCRIPT, SUBSCRIPT, SMALL_CAPS }

/**
 * A styled run inside a block, addressed by offsets into that block's [ContentBlock.text].
 * Ranges may overlap: the renderer is expected to apply them additively.
 */
data class InlineSpan(
    val start: Int,
    val end: Int,
    val styles: Set<InlineStyle> = emptySet(),
    /** Internal (`chapter.xhtml#anchor`) or external (`https://…`) link target. */
    val href: String? = null,
    /** Footnote/endnote id this run points at, if the source marked it up as one. */
    val noteRef: String? = null,
) {
    init { require(end >= start) { "InlineSpan end ($end) precedes start ($start)" } }
}

enum class BlockKind {
    PARAGRAPH,
    HEADING_1, HEADING_2, HEADING_3, HEADING_4, HEADING_5, HEADING_6,
    BLOCKQUOTE,
    LIST_ITEM,
    CODE,
    IMAGE,
    FIGURE_CAPTION,
    SEPARATOR,
    TABLE,
    FOOTNOTE,
    PAGE_BREAK,
    ;

    val isHeading: Boolean
        get() = this == HEADING_1 || this == HEADING_2 || this == HEADING_3 ||
            this == HEADING_4 || this == HEADING_5 || this == HEADING_6

    val headingLevel: Int
        get() = if (isHeading) ordinal - HEADING_1.ordinal + 1 else 0
}

/**
 * One renderable unit of a chapter.
 *
 * [textStart] is the block's offset into the chapter's concatenated plain text. That single
 * number is what ties the visual reader, the speech engine and the saved reading position
 * together, so it must be assigned by the parser and never recomputed downstream.
 */
data class ContentBlock(
    val kind: BlockKind,
    val text: String,
    val textStart: Int,
    val spans: List<InlineSpan> = emptyList(),
    /** Anchor ids declared on or inside this block, for TOC fragments and internal links. */
    val anchors: List<String> = emptyList(),
    /** Resource reference for [BlockKind.IMAGE]. Resolved through [BookSource.readResource]. */
    val imageRef: String? = null,
    val imageAlt: String? = null,
    /** Nesting depth for lists and blockquotes. */
    val indentLevel: Int = 0,
    /** Marker for ordered lists, e.g. "3." — null for unordered. */
    val listMarker: String? = null,
    /** Rows for [BlockKind.TABLE]; the first row is treated as the header. */
    val tableRows: List<List<String>> = emptyList(),
    /** True when the block is decorative or navigational and should be skipped by read-aloud. */
    val isSpeechSkipped: Boolean = false,
    val language: String? = null,
) {
    val textEnd: Int get() = textStart + text.length

    fun containsOffset(offset: Int): Boolean = offset >= textStart && offset < textEnd
}

/**
 * A fully parsed chapter: the blocks for the eye, and the flat plain text for the ear.
 *
 * Invariant: `plainText.substring(block.textStart, block.textEnd) == block.text` for every block.
 * [ChapterContent.validate] checks it and the parsers are unit-tested against it, because every
 * synchronisation feature in the app rests on it.
 */
data class ChapterContent(
    val chapterIndex: app.soundbound.core.model.ChapterIndex,
    val title: String?,
    val blocks: List<ContentBlock>,
    val plainText: String,
    /** Footnote bodies keyed by anchor id, lifted out of the flow so they do not interrupt speech. */
    val notes: Map<String, String> = emptyMap(),
) {
    fun blockAtOffset(offset: Int): ContentBlock? {
        // Blocks are sorted by textStart, so binary search rather than scan: chapters can be huge.
        var lo = 0
        var hi = blocks.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val block = blocks[mid]
            when {
                offset < block.textStart -> hi = mid - 1
                offset >= block.textEnd -> lo = mid + 1
                else -> return block
            }
        }
        return blocks.getOrNull(lo.coerceAtMost(blocks.lastIndex))
    }

    fun offsetOfAnchor(anchor: String): Int? =
        blocks.firstOrNull { anchor in it.anchors }?.textStart

    /** Throws if the block/plain-text invariant has been broken. Used by tests and debug builds. */
    fun validate() {
        var previousEnd = -1
        blocks.forEach { block ->
            require(block.textStart >= previousEnd) {
                "Blocks out of order at ${block.textStart} (previous end $previousEnd)"
            }
            require(block.textEnd <= plainText.length) {
                "Block [${block.textStart},${block.textEnd}) overruns plain text of ${plainText.length}"
            }
            require(plainText.substring(block.textStart, block.textEnd) == block.text) {
                "Block text does not match plain text at ${block.textStart}"
            }
            previousEnd = block.textEnd
        }
    }
}
