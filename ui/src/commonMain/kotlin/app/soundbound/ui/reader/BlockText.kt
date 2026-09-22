package app.soundbound.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.book.InlineStyle
import app.soundbound.core.model.Highlight
import app.soundbound.core.model.HighlightColour
import app.soundbound.core.prefs.HighlightStyle
import app.soundbound.core.prefs.TypographySettings
import app.soundbound.ui.theme.ReaderPalette
import app.soundbound.ui.theme.SoundboundColours

/** The colour a user highlight is drawn in. */
fun HighlightColour.toColour(): Color = when (this) {
    HighlightColour.YELLOW -> SoundboundColours.HighlightYellow
    HighlightColour.GREEN -> SoundboundColours.HighlightGreen
    HighlightColour.BLUE -> SoundboundColours.HighlightBlue
    HighlightColour.PINK -> SoundboundColours.HighlightPink
    HighlightColour.PURPLE -> SoundboundColours.HighlightPurple
    HighlightColour.UNDERLINE -> Color.Transparent
}

/**
 * Turns one block into styled text.
 *
 * Four things are layered on, in this order, so that each wins over the one before where they
 * overlap: the block's own inline emphasis, then the user's highlights, then the sentence being
 * spoken, then the word being spoken. That order is what makes read-aloud legible over a
 * highlighted passage instead of fighting with it.
 *
 * All offsets arriving here are in *chapter* coordinates, because that is the one coordinate
 * system the parsers, the speech planner and the saved position all agree on. They are converted
 * to block-relative offsets once, here.
 */
fun buildBlockText(
    block: ContentBlock,
    palette: ReaderPalette,
    highlights: List<Highlight>,
    speakingRange: IntRange?,
    speakingWordRange: IntRange?,
    highlightStyle: HighlightStyle,
): AnnotatedString = buildAnnotatedString {
    append(block.text)

    val blockStart = block.textStart
    val blockEnd = block.textEnd

    // 1. The publisher's own emphasis.
    block.spans.forEach { span ->
        val style = SpanStyle(
            fontWeight = if (InlineStyle.BOLD in span.styles) FontWeight.Bold else null,
            fontStyle = if (InlineStyle.ITALIC in span.styles) FontStyle.Italic else null,
            fontFamily = if (InlineStyle.CODE in span.styles) FontFamily.Monospace else null,
            textDecoration = when {
                InlineStyle.UNDERLINE in span.styles && InlineStyle.STRIKETHROUGH in span.styles ->
                    TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))

                InlineStyle.UNDERLINE in span.styles -> TextDecoration.Underline
                InlineStyle.STRIKETHROUGH in span.styles -> TextDecoration.LineThrough
                span.href != null -> TextDecoration.Underline
                else -> null
            },
            color = if (span.href != null) palette.link else Color.Unspecified,
            baselineShift = when {
                InlineStyle.SUPERSCRIPT in span.styles -> BaselineShift.Superscript
                InlineStyle.SUBSCRIPT in span.styles -> BaselineShift.Subscript
                else -> null
            },
            fontSize = if (
                InlineStyle.SUPERSCRIPT in span.styles || InlineStyle.SUBSCRIPT in span.styles
            ) {
                0.75.em
            } else {
                TextUnit.Unspecified
            },
            // Small caps has no direct equivalent; letter-spaced capitals are the usual stand-in.
            letterSpacing = if (InlineStyle.SMALL_CAPS in span.styles) 0.08.em else TextUnit.Unspecified,
        )
        addStyle(style, span.start.coerceIn(0, block.text.length), span.end.coerceIn(0, block.text.length))
        // Bound to locals: the properties live in another module, so they are not smart-castable.
        val href = span.href
        if (href != null) {
            addStringAnnotation(ANNOTATION_LINK, href, span.start, span.end)
        }
        val noteRef = span.noteRef
        if (noteRef != null) {
            addStringAnnotation(ANNOTATION_NOTE, noteRef, span.start, span.end)
        }
    }

    // 2. The reader's own highlights.
    highlights.forEach { highlight ->
        val from = (highlight.startOffset - blockStart).coerceIn(0, block.text.length)
        val to = (highlight.endOffset - blockStart).coerceIn(0, block.text.length)
        if (to <= from) return@forEach
        if (highlight.colour == HighlightColour.UNDERLINE) {
            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), from, to)
        } else {
            addStyle(SpanStyle(background = highlight.colour.toColour().copy(alpha = 0.55f)), from, to)
        }
        addStringAnnotation(ANNOTATION_HIGHLIGHT, highlight.id, from, to)
    }

    // 3. The sentence being spoken.
    if (highlightStyle != HighlightStyle.NONE && speakingRange != null) {
        val from = (speakingRange.first - blockStart).coerceIn(0, block.text.length)
        val to = (speakingRange.last + 1 - blockStart).coerceIn(0, block.text.length)
        if (to > from && speakingRange.first < blockEnd && speakingRange.last >= blockStart) {
            when (highlightStyle) {
                HighlightStyle.UNDERLINE ->
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), from, to)

                HighlightStyle.WORD -> Unit
                else -> addStyle(SpanStyle(background = palette.speakingBackground), from, to)
            }
        }
    }

    // 4. The word being spoken, which needs to read clearly on top of everything above.
    if (
        (highlightStyle == HighlightStyle.WORD || highlightStyle == HighlightStyle.SENTENCE_AND_WORD) &&
        speakingWordRange != null
    ) {
        val from = (speakingWordRange.first - blockStart).coerceIn(0, block.text.length)
        val to = (speakingWordRange.last + 1 - blockStart).coerceIn(0, block.text.length)
        if (to > from) {
            addStyle(
                SpanStyle(background = palette.speakingWord, fontWeight = FontWeight.Medium),
                from,
                to,
            )
        }
    }
}

const val ANNOTATION_LINK = "link"
const val ANNOTATION_NOTE = "note"
const val ANNOTATION_HIGHLIGHT = "highlight"

/**
 * The text style for a block.
 *
 * Headings are sized relative to the body text rather than fixed, so that raising the reading size
 * scales the whole page in proportion. Getting that wrong — fixed headings over scalable body
 * text — is what makes most readers look broken at large accessibility sizes.
 */
@Composable
fun blockTextStyle(
    block: ContentBlock,
    typography: TypographySettings,
    palette: ReaderPalette,
    fontFamily: FontFamily,
): TextStyle {
    val base = typography.fontSizeSp
    val scale = when (block.kind) {
        BlockKind.HEADING_1 -> 1.85f
        BlockKind.HEADING_2 -> 1.55f
        BlockKind.HEADING_3 -> 1.32f
        BlockKind.HEADING_4 -> 1.16f
        BlockKind.HEADING_5 -> 1.06f
        BlockKind.HEADING_6 -> 1.0f
        BlockKind.CODE -> 0.88f
        BlockKind.FIGURE_CAPTION, BlockKind.FOOTNOTE -> 0.85f
        BlockKind.TABLE -> 0.9f
        else -> 1f
    }

    val isHeading = block.kind.isHeading
    return TextStyle(
        color = when {
            isHeading -> palette.heading
            block.kind == BlockKind.FIGURE_CAPTION || block.kind == BlockKind.FOOTNOTE -> palette.muted
            else -> palette.text
        },
        fontSize = (base * scale).sp,
        // Headings want tighter leading than body text at the same size, or they look loose.
        lineHeight = (base * scale * if (isHeading) 1.2f else typography.lineHeightMultiplier).sp,
        fontFamily = if (block.kind == BlockKind.CODE) FontFamily.Monospace else fontFamily,
        fontWeight = when {
            isHeading -> FontWeight.SemiBold
            typography.boldText -> FontWeight.Medium
            else -> FontWeight.Normal
        },
        fontStyle = if (block.kind == BlockKind.BLOCKQUOTE) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = typography.letterSpacingEm.em,
        textAlign = when {
            isHeading -> TextAlign.Start
            block.kind == BlockKind.FIGURE_CAPTION -> TextAlign.Center
            typography.justifyText -> TextAlign.Justify
            else -> TextAlign.Start
        },
        textIndent = if (
            typography.indentParagraphs &&
            block.kind == BlockKind.PARAGRAPH
        ) {
            TextIndent(firstLine = (base * 1.6f).sp)
        } else {
            TextIndent.None
        },
        // Fonts dropped in by the user may have no bold or italic cut; let the platform synthesise
        // one rather than silently ignoring the emphasis in the book.
        fontSynthesis = FontSynthesis.All,
    )
}

/** Vertical space before a block, in multiples of the paragraph spacing setting. */
fun blockSpacingBefore(block: ContentBlock, previous: ContentBlock?): Float = when {
    previous == null -> 0f
    block.kind.isHeading -> 2.2f
    previous.kind.isHeading -> 0.9f
    block.kind == BlockKind.BLOCKQUOTE || previous.kind == BlockKind.BLOCKQUOTE -> 1.4f
    block.kind == BlockKind.LIST_ITEM && previous.kind == BlockKind.LIST_ITEM -> 0.45f
    block.kind == BlockKind.IMAGE || previous.kind == BlockKind.IMAGE -> 1.6f
    block.kind == BlockKind.SEPARATOR || previous.kind == BlockKind.SEPARATOR -> 1.6f
    block.kind == BlockKind.CODE || previous.kind == BlockKind.CODE -> 1.2f
    block.kind == BlockKind.FIGURE_CAPTION -> 0.4f
    else -> 1f
}

/** Highlights that touch a block, so each block is only handed what it needs to draw. */
fun highlightsFor(block: ContentBlock, all: List<Highlight>): List<Highlight> =
    if (all.isEmpty()) {
        emptyList()
    } else {
        all.filter { it.startOffset < block.textEnd && it.endOffset > block.textStart }
    }

/** The colour text is drawn in for an unstyled run. Used by the placeholder blocks. */
@Composable
fun readerTextColour(palette: ReaderPalette): Color =
    if (palette.text != Color.Unspecified) palette.text else MaterialTheme.colorScheme.onSurface
