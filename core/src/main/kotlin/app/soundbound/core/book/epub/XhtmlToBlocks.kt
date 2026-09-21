package app.soundbound.core.book.epub

import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.book.InlineSpan
import app.soundbound.core.book.InlineStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** The product of converting one XHTML document. */
internal data class ConvertedDocument(
    val blocks: List<ContentBlock>,
    val plainText: String,
    val notes: Map<String, String>,
    val firstHeading: String?,
)

/**
 * Turns an EPUB content document into renderable blocks plus the flat plain text that the
 * speech engine reads.
 *
 * Two things make this harder than it looks, and both are handled here rather than being
 * papered over downstream:
 *
 *  1. Every block records its exact offset into the plain text, so highlighting the spoken
 *     sentence, saving a position and scrolling the view all agree with one another.
 *  2. Footnotes are lifted out of the flow. Publishers routinely drop a 200-word endnote in
 *     the middle of a paragraph; read aloud verbatim that is baffling, so notes are collected
 *     separately and surfaced as a tappable marker instead.
 */
internal class XhtmlToBlocks(
    private val baseDirectory: String,
    private val preserveBlankParagraphs: Boolean = false,
) {

    fun convert(xhtml: String): ConvertedDocument {
        val document = Jsoup.parse(xhtml, "", Parser.htmlParser())
        document.outputSettings().prettyPrint(false)
        val body = document.body()

        val state = ConversionState()
        visitChildren(body, state, Context())
        state.flush()

        return ConvertedDocument(
            blocks = state.blocks,
            plainText = state.plain.toString(),
            notes = state.notes,
            firstHeading = state.firstHeading,
        )
    }

    /** Inherited formatting as we descend the tree. */
    private data class Context(
        val styles: Set<InlineStyle> = emptySet(),
        val href: String? = null,
        val indent: Int = 0,
        val preformatted: Boolean = false,
        val listMarker: String? = null,
        val language: String? = null,
        val insideNote: Boolean = false,
        val inQuote: Boolean = false,
    )

    private inner class ConversionState {
        val blocks = ArrayList<ContentBlock>()
        val plain = StringBuilder()
        val notes = LinkedHashMap<String, String>()
        var firstHeading: String? = null

        private var buffer = StringBuilder()
        private var bufferSpans = ArrayList<InlineSpan>()
        private var bufferAnchors = ArrayList<String>()
        private var pendingKind: BlockKind = BlockKind.PARAGRAPH
        private var pendingIndent = 0
        private var pendingMarker: String? = null
        private var pendingLanguage: String? = null
        private var pendingSkipSpeech = false

        /** Note id currently being captured, if any; its text bypasses the main flow. */
        var captureNoteId: String? = null
        val noteBuffer = StringBuilder()

        fun beginBlock(
            kind: BlockKind,
            context: Context,
            anchor: String? = null,
            skipSpeech: Boolean = false,
        ) {
            flush()
            pendingKind = if (kind == BlockKind.PARAGRAPH && context.inQuote) BlockKind.BLOCKQUOTE else kind
            pendingIndent = context.indent
            pendingMarker = context.listMarker
            pendingLanguage = context.language
            pendingSkipSpeech = skipSpeech
            if (anchor != null) addAnchor(anchor)
        }

        fun appendText(text: String, context: Context) {
            if (captureNoteId != null) {
                noteBuffer.append(text)
                return
            }
            if (text.isEmpty()) return
            val start = buffer.length
            buffer.append(text)
            if (context.styles.isNotEmpty() || context.href != null) {
                bufferSpans.add(
                    InlineSpan(start, buffer.length, context.styles, context.href),
                )
            }
        }

        fun addNoteReference(text: String, context: Context, noteRef: String) {
            val start = buffer.length
            buffer.append(text)
            bufferSpans.add(InlineSpan(start, buffer.length, context.styles, context.href, noteRef))
        }

        fun addAnchor(id: String) {
            if (captureNoteId != null) return
            if (id.isNotBlank() && id !in bufferAnchors) bufferAnchors.add(id)
        }

        fun addStandaloneBlock(anchor: String? = null, block: (Int) -> ContentBlock) {
            flush()
            separate()
            val start = plain.length
            val created = block(start)
            plain.append(created.text)
            blocks.add(
                if (anchor == null) created else created.copy(anchors = created.anchors + anchor),
            )
        }

        /** Emits the buffered inline content as a block, if there is anything worth emitting. */
        fun flush() {
            val raw = buffer.toString()
            val text = if (pendingKind == BlockKind.CODE) raw.trimEnd('\n') else raw.collapseSoftWhitespace()
            val keepEmpty = preserveBlankParagraphs && bufferAnchors.isNotEmpty()
            if (text.isBlank() && !keepEmpty) {
                if (bufferAnchors.isNotEmpty()) {
                    // Keep the anchor reachable even though the element held no text: TOC
                    // fragments frequently point at an empty <a id="…"/>.
                    separate()
                    blocks.add(
                        ContentBlock(
                            kind = BlockKind.SEPARATOR,
                            text = "",
                            textStart = plain.length,
                            anchors = bufferAnchors.toList(),
                            isSpeechSkipped = true,
                        ),
                    )
                }
                reset()
                return
            }

            separate()
            val start = plain.length
            // Re-map spans through the whitespace collapse so emphasis still lands on the
            // right characters after normalisation.
            val spans = if (pendingKind == BlockKind.CODE) {
                bufferSpans.clampTo(text.length)
            } else {
                remapSpans(raw, text, bufferSpans)
            }
            plain.append(text)
            blocks.add(
                ContentBlock(
                    kind = pendingKind,
                    text = text,
                    textStart = start,
                    spans = spans,
                    anchors = bufferAnchors.toList(),
                    indentLevel = pendingIndent,
                    listMarker = pendingMarker,
                    isSpeechSkipped = pendingSkipSpeech,
                    language = pendingLanguage,
                ),
            )
            if (firstHeading == null && pendingKind.isHeading) firstHeading = text
            reset()
        }

        private fun separate() {
            if (plain.isNotEmpty() && !plain.endsWith("\n\n")) {
                if (plain.endsWith("\n")) plain.append('\n') else plain.append("\n\n")
            }
        }

        private fun reset() {
            buffer = StringBuilder()
            bufferSpans = ArrayList()
            bufferAnchors = ArrayList()
            pendingKind = BlockKind.PARAGRAPH
            pendingIndent = 0
            pendingMarker = null
            pendingLanguage = null
            pendingSkipSpeech = false
        }

        fun currentlyEmpty(): Boolean = buffer.isBlank()
    }

    private fun visitChildren(element: Element, state: ConversionState, context: Context) {
        element.childNodes().forEach { visit(it, state, context) }
    }

    private fun visit(node: Node, state: ConversionState, context: Context) {
        when (node) {
            is TextNode -> {
                val raw = node.wholeText
                if (raw.isEmpty()) return
                state.appendText(if (context.preformatted) raw else raw.replace(' ', ' '), context)
            }

            is Element -> visitElement(node, state, context)
        }
    }

    private fun visitElement(element: Element, state: ConversionState, context: Context) {
        val tag = element.tagName().lowercase()
        if (tag in SKIPPED_TAGS) return

        // `epub:type` is a space-separated token list, so it is compared token by token.
        // Substring matching would classify a `noteref` link as a `note` body, which drops
        // the reference marker and swallows the sentence it sat in.
        val epubTypes = element.attr("epub:type").splitOnWhitespace()
            .plus(element.attr("role").splitOnWhitespace())
            .map { it.substringAfter(':').lowercase() }
            .toSet()
        val anchor = element.attr("id").takeIf { it.isNotBlank() }
        // Block-level elements hand their anchor to the block they start, so a TOC fragment
        // pointing at `<h2 id="chapter-3">` lands on the heading rather than on whatever
        // paragraph happened to be buffered beforehand.
        if (anchor != null && tag !in BLOCK_TAGS) state.addAnchor(anchor)

        // Footnote and endnote bodies are captured out-of-band.
        val isNoteReference = epubTypes.any { it in NOTE_REF_TYPES } ||
            element.attr("class").contains("noteref", ignoreCase = true)
        if (!isNoteReference && tag != "a" &&
            (epubTypes.any { it in NOTE_TYPES } || element.hasClass("footnote") || element.hasClass("endnote"))
        ) {
            val id = element.attr("id").ifBlank { "note-${state.notes.size + 1}" }
            state.flush()
            state.captureNoteId = id
            state.noteBuffer.setLength(0)
            visitChildren(element, state, context.copy(insideNote = true))
            state.notes[id] = state.noteBuffer.toString().collapseSoftWhitespace()
            state.captureNoteId = null
            return
        }

        // Navigation, page-number markers and skippable furniture: keep them out of speech.
        if (epubTypes.any { it in NON_SPOKEN_TYPES }) return

        val language = element.attr("xml:lang").ifBlank { element.attr("lang") }
            .takeIf { it.isNotBlank() } ?: context.language

        when (tag) {
            "br" -> state.appendText("\n", context)
            "hr" -> state.addStandaloneBlock(anchor) { start ->
                ContentBlock(BlockKind.SEPARATOR, "", start, isSpeechSkipped = true)
            }

            "img" -> emitImage(element, state, context, anchor)
            "image" -> emitSvgImage(element, state)
            "svg" -> {
                val inner = element.selectFirst("image")
                if (inner != null) emitSvgImage(inner, state) else emitImage(element, state, context)
            }

            "table" -> emitTable(element, state, anchor)

            "p", "div", "section", "article", "header", "footer", "main", "aside", "blockquote",
            "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "li", "dd", "dt",
            "ul", "ol", "dl", "center", "body",
            -> emitBlockLevel(tag, element, state, context.copy(language = language), epubTypes, anchor)

            "a" -> {
                val href = element.attr("href").takeIf { it.isNotBlank() }
                val noteRef = href?.takeIf { it.startsWith("#") }?.removePrefix("#")
                if (isNoteReference && noteRef != null) {
                    val label = element.text().collapseWhitespace()
                    // Speech skips the marker entirely; the reader shows it as a superscript.
                    state.addNoteReference(label, context.copy(styles = context.styles + InlineStyle.SUPERSCRIPT), noteRef)
                } else {
                    visitChildren(element, state, context.copy(href = href?.let { resolveHref(it) }))
                }
            }

            else -> {
                val style = INLINE_STYLES[tag]
                val next = if (style != null) context.copy(styles = context.styles + style, language = language)
                else context.copy(language = language)
                visitChildren(element, state, next)
            }
        }
    }

    private fun emitBlockLevel(
        tag: String,
        element: Element,
        state: ConversionState,
        context: Context,
        epubTypes: Set<String>,
        anchor: String?,
    ) {
        when (tag) {
            "ul", "ol", "dl" -> {
                state.flush()
                anchor?.let(state::addAnchor)
                var ordinal = element.attr("start").toIntOrNull() ?: 1
                element.children().forEach { child ->
                    val marker = if (tag == "ol" && child.tagName().equals("li", true)) "${ordinal++}." else null
                    visitElement(child, state, context.copy(indent = context.indent + 1, listMarker = marker))
                }
                state.flush()
                return
            }

            "li", "dd", "dt" -> {
                state.beginBlock(BlockKind.LIST_ITEM, context, anchor)
                visitChildren(element, state, context)
                state.flush()
                return
            }

            "pre" -> {
                state.beginBlock(BlockKind.CODE, context, anchor)
                visitChildren(element, state, context.copy(preformatted = true))
                state.flush()
                return
            }

            "blockquote" -> {
                state.flush()
                anchor?.let(state::addAnchor)
                visitChildren(element, state, context.copy(indent = context.indent + 1, inQuote = true))
                state.flush()
                return
            }

            "figcaption" -> {
                state.beginBlock(BlockKind.FIGURE_CAPTION, context, anchor)
                visitChildren(element, state, context)
                state.flush()
                return
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag.substring(1).toInt()
                state.beginBlock(HEADING_KINDS[level - 1], context, anchor)
                visitChildren(element, state, context)
                state.flush()
                return
            }

            "p" -> {
                state.beginBlock(BlockKind.PARAGRAPH, context, anchor)
                visitChildren(element, state, context)
                state.flush()
                return
            }

            else -> {
                // Container elements (div/section/…): only start a block if they hold text
                // directly, otherwise just descend so we do not fabricate empty paragraphs.
                val holdsDirectText = element.childNodes().any { it is TextNode && it.wholeText.isNotBlank() }
                val isPageBreak = "pagebreak" in epubTypes
                if (isPageBreak) {
                    state.addStandaloneBlock(anchor) { start ->
                        ContentBlock(BlockKind.PAGE_BREAK, "", start, isSpeechSkipped = true)
                    }
                    return
                }
                if (holdsDirectText) {
                    state.beginBlock(BlockKind.PARAGRAPH, context, anchor)
                    visitChildren(element, state, context)
                    state.flush()
                } else {
                    state.flush()
                    anchor?.let(state::addAnchor)
                    visitChildren(element, state, context)
                    state.flush()
                }
            }
        }
    }

    private fun emitImage(
        element: Element,
        state: ConversionState,
        context: Context,
        anchor: String? = null,
    ) {
        val src = element.attr("src").ifBlank { element.attr("data-src") }
        if (src.isBlank()) return
        val alt = element.attr("alt").collapseWhitespace().takeIf { it.isNotBlank() }
        state.addStandaloneBlock(anchor) { start ->
            ContentBlock(
                kind = BlockKind.IMAGE,
                text = "",
                textStart = start,
                imageRef = resolveHref(src),
                imageAlt = alt,
                indentLevel = context.indent,
                isSpeechSkipped = true,
            )
        }
    }

    private fun emitSvgImage(element: Element, state: ConversionState) {
        val href = element.attr("xlink:href").ifBlank { element.attr("href") }
        if (href.isBlank()) return
        state.addStandaloneBlock { start ->
            ContentBlock(
                kind = BlockKind.IMAGE,
                text = "",
                textStart = start,
                imageRef = resolveHref(href),
                isSpeechSkipped = true,
            )
        }
    }

    private fun emitTable(element: Element, state: ConversionState, anchor: String? = null) {
        val rows = element.select("tr").map { tr ->
            tr.select("th, td").map { it.text().collapseWhitespace() }
        }.filter { row -> row.any { it.isNotEmpty() } }
        if (rows.isEmpty()) return
        // Tables are given a readable linear form so read-aloud does not simply go silent,
        // while the renderer still gets the grid in `tableRows`.
        val spoken = rows.joinToString("\n") { it.joinToString(", ") }
        state.addStandaloneBlock(anchor) { start ->
            ContentBlock(
                kind = BlockKind.TABLE,
                text = spoken,
                textStart = start,
                tableRows = rows,
            )
        }
    }

    private fun resolveHref(href: String): String =
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
            href
        } else {
            val fragment = ZipPaths.fragmentOf(href)
            val path = ZipPaths.resolve(baseDirectory, href)
            if (fragment != null) "$path#$fragment" else path
        }

    private companion object {
        val HEADING_KINDS = listOf(
            BlockKind.HEADING_1, BlockKind.HEADING_2, BlockKind.HEADING_3,
            BlockKind.HEADING_4, BlockKind.HEADING_5, BlockKind.HEADING_6,
        )

        val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "header", "footer", "main", "aside", "blockquote",
            "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "li", "dd", "dt",
            "ul", "ol", "dl", "center", "body", "table", "hr", "img",
        )

        val SKIPPED_TAGS = setOf("script", "style", "head", "title", "link", "meta", "audio", "video", "iframe")

        val INLINE_STYLES = mapOf(
            "b" to InlineStyle.BOLD,
            "strong" to InlineStyle.BOLD,
            "i" to InlineStyle.ITALIC,
            "em" to InlineStyle.ITALIC,
            "cite" to InlineStyle.ITALIC,
            "dfn" to InlineStyle.ITALIC,
            "u" to InlineStyle.UNDERLINE,
            "ins" to InlineStyle.UNDERLINE,
            "s" to InlineStyle.STRIKETHROUGH,
            "del" to InlineStyle.STRIKETHROUGH,
            "strike" to InlineStyle.STRIKETHROUGH,
            "code" to InlineStyle.CODE,
            "kbd" to InlineStyle.CODE,
            "samp" to InlineStyle.CODE,
            "tt" to InlineStyle.CODE,
            "sup" to InlineStyle.SUPERSCRIPT,
            "sub" to InlineStyle.SUBSCRIPT,
        )

        val NOTE_TYPES = setOf("footnote", "endnote", "rearnote", "note", "doc-footnote", "doc-endnote")
        val NOTE_REF_TYPES = setOf("noteref", "footnoteref", "endnoteref", "doc-noteref", "backlink")
        val NON_SPOKEN_TYPES = setOf("pagebreak", "page-list", "toc", "landmarks", "loi", "lot", "doc-pagebreak")
    }
}

/**
 * Collapses runs of whitespace to single spaces while keeping deliberate line breaks
 * (from `<br>`) intact, then trims the result.
 */
internal fun String.collapseSoftWhitespace(): String {
    if (isEmpty()) return this
    val out = StringBuilder(length)
    var pendingSpace = false
    var pendingNewlines = 0
    var started = false
    for (ch in this) {
        when {
            ch == '\n' -> { pendingNewlines++; pendingSpace = false }
            ch.isWhitespace() -> pendingSpace = true
            else -> {
                if (started) {
                    if (pendingNewlines > 0) repeat(minOf(pendingNewlines, 2)) { out.append('\n') }
                    else if (pendingSpace) out.append(' ')
                }
                out.append(ch)
                started = true
                pendingSpace = false
                pendingNewlines = 0
            }
        }
    }
    return out.toString()
}

/**
 * Re-projects spans measured against [raw] onto [collapsed]. We walk both strings in step,
 * which is O(n) and exact, rather than guessing with a ratio.
 */
internal fun remapSpans(raw: String, collapsed: String, spans: List<InlineSpan>): List<InlineSpan> {
    if (spans.isEmpty()) return emptyList()
    if (raw == collapsed) return spans.clampTo(collapsed.length)

    val map = IntArray(raw.length + 1)
    var out = 0
    var rawIndex = 0
    while (rawIndex < raw.length && out < collapsed.length) {
        map[rawIndex] = out
        if (raw[rawIndex] == collapsed[out]) {
            out++
            rawIndex++
        } else {
            // A character that the collapse removed (leading/duplicate whitespace).
            rawIndex++
        }
    }
    while (rawIndex <= raw.length) {
        map[rawIndex] = out
        rawIndex++
    }

    return spans.mapNotNull { span ->
        val start = map[span.start.coerceIn(0, raw.length)]
        val end = map[span.end.coerceIn(0, raw.length)]
        if (end <= start) null else InlineSpan(start, end, span.styles, span.href, span.noteRef)
    }
}

internal fun List<InlineSpan>.clampTo(length: Int): List<InlineSpan> = mapNotNull { span ->
    val start = span.start.coerceIn(0, length)
    val end = span.end.coerceIn(0, length)
    if (end <= start) null else if (start == span.start && end == span.end) span
    else InlineSpan(start, end, span.styles, span.href, span.noteRef)
}
