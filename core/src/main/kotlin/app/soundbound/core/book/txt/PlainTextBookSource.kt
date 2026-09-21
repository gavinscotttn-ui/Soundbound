package app.soundbound.core.book.txt

import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.book.BookParseException
import app.soundbound.core.book.BookParser
import app.soundbound.core.book.BookSource
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.book.InlineSpan
import app.soundbound.core.book.InlineStyle
import app.soundbound.core.book.epub.decodeText
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import okio.Source
import okio.buffer

/**
 * Reads `.txt` and `.md` files. Plain text is the format Project Gutenberg and a thousand
 * fan translations arrive in, so it earns proper treatment rather than a single wall of prose:
 * chapter headings are detected, and Markdown emphasis is honoured where present.
 */
class PlainTextParser : BookParser {
    override val format: BookFormat = BookFormat.PLAIN_TEXT

    override fun canOpen(fileName: String, header: ByteArray): Boolean {
        if (BookFormat.fromFileName(fileName) != BookFormat.PLAIN_TEXT) return false
        // Reject anything with NUL bytes in the first block: that is a binary file with a
        // misleading extension, not a book.
        return header.none { it == 0.toByte() }
    }

    override fun open(file: BookFileHandle): BookSource {
        val bytes = file.openSource().buffer().use { it.readByteArray() }
        if (bytes.isEmpty()) throw BookParseException("This file is empty.")
        val markdown = file.displayName.endsWith(".md", true) || file.displayName.endsWith(".markdown", true)
        return PlainTextBookSource(decodeText(bytes), file.displayName, markdown)
    }
}

class PlainTextBookSource internal constructor(
    rawText: String,
    displayName: String,
    private val markdown: Boolean,
) : BookSource {

    override val format: BookFormat = BookFormat.PLAIN_TEXT

    private val sections: List<Section> = splitIntoSections(rawText.replace("\r\n", "\n").replace('\r', '\n'))

    override val metadata: BookMetadata = BookMetadata(
        title = detectTitle(rawText) ?: displayName.substringBeforeLast('.'),
        authors = detectAuthor(rawText)?.let(::listOf).orEmpty(),
    )

    override val chapters: List<Chapter> = sections.mapIndexed { index, section ->
        Chapter(ChapterIndex(index), section.title, "section:$index", section.body.length)
    }

    override val toc: List<TocEntry> = sections.mapIndexed { index, section ->
        TocEntry(section.title ?: "Section ${index + 1}", ChapterIndex(index))
    }

    override fun chapterContent(index: ChapterIndex): ChapterContent {
        val section = sections.getOrNull(index.value)
            ?: throw BookParseException("Section ${index.value} is outside this file.")
        val blocks = ArrayList<ContentBlock>()
        val plain = StringBuilder()

        section.title?.let { title ->
            blocks.add(ContentBlock(BlockKind.HEADING_1, title, 0))
            plain.append(title)
        }

        section.body.split(Regex("\n[ \t]*\n+")).forEach { rawParagraph ->
            val collapsed = rawParagraph.trim().replace(Regex("[ \t]*\n[ \t]*"), " ").replace(Regex("[ \t]+"), " ")
            if (collapsed.isEmpty()) return@forEach
            if (plain.isNotEmpty()) plain.append("\n\n")
            val (text, spans, kind, marker) = if (markdown) parseMarkdownParagraph(collapsed) else
                MarkdownParagraph(collapsed, emptyList(), BlockKind.PARAGRAPH, null)
            if (text.isEmpty()) return@forEach
            val start = plain.length
            plain.append(text)
            blocks.add(ContentBlock(kind, text, start, spans, listMarker = marker))
        }

        return ChapterContent(index, section.title, blocks, plain.toString())
    }

    override fun readResource(ref: String): Source? = null

    override fun close() = Unit

    private data class Section(val title: String?, val body: String)

    private fun splitIntoSections(text: String): List<Section> {
        val lines = text.split('\n')
        val sections = ArrayList<Section>()
        var currentTitle: String? = null
        val current = StringBuilder()

        fun push() {
            if (current.isNotBlank() || currentTitle != null) {
                sections.add(Section(currentTitle, current.toString().trim('\n')))
            }
            current.setLength(0)
        }

        lines.forEachIndexed { index, line ->
            val heading = detectHeading(line, lines.getOrNull(index - 1), lines.getOrNull(index + 1))
            if (heading != null && (current.length > 200 || sections.isEmpty())) {
                push()
                currentTitle = heading
            } else {
                current.append(line).append('\n')
            }
        }
        push()

        if (sections.isEmpty()) return listOf(Section(null, text.trim()))
        // A very long single section is unpleasant to navigate, so split it on blank-line
        // boundaries into readable chunks.
        return sections.flatMap { section ->
            if (section.body.length <= MAX_SECTION_CHARACTERS) listOf(section)
            else splitLongSection(section)
        }
    }

    private fun splitLongSection(section: Section): List<Section> {
        val parts = ArrayList<Section>()
        val paragraphs = section.body.split(Regex("\n[ \t]*\n+"))
        val builder = StringBuilder()
        var partIndex = 1
        paragraphs.forEach { paragraph ->
            if (builder.length + paragraph.length > MAX_SECTION_CHARACTERS && builder.isNotEmpty()) {
                parts.add(
                    Section(
                        section.title?.let { if (partIndex == 1) it else "$it (part $partIndex)" },
                        builder.toString().trim(),
                    ),
                )
                builder.setLength(0)
                partIndex++
            }
            builder.append(paragraph).append("\n\n")
        }
        if (builder.isNotBlank()) {
            parts.add(
                Section(
                    section.title?.let { if (partIndex == 1) it else "$it (part $partIndex)" },
                    builder.toString().trim(),
                ),
            )
        }
        return parts
    }

    private fun detectHeading(line: String, previous: String?, next: String?): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 90) return null
        if (previous?.isNotBlank() == true) return null

        if (markdown && trimmed.startsWith("#")) {
            return trimmed.trimStart('#').trim().takeIf { it.isNotEmpty() }
        }
        // A Setext-style underline of = or -.
        if (next != null && next.trim().length >= 3 &&
            (next.trim().all { it == '=' } || next.trim().all { it == '-' })
        ) {
            return trimmed
        }
        if (CHAPTER_HEADING.matches(trimmed)) return trimmed
        // Short, all-caps, no terminal punctuation: the Gutenberg house style.
        if (trimmed.length in 3..60 && trimmed == trimmed.uppercase() &&
            trimmed.any { it.isLetter() } && !trimmed.endsWith('.')
        ) {
            return trimmed
        }
        return null
    }

    private fun detectTitle(text: String): String? = text.lineSequence()
        .take(40)
        .map { it.trim() }
        .firstOrNull { line ->
            line.length in 3..90 && line.any { it.isLetter() } &&
                !line.startsWith("The Project Gutenberg", ignoreCase = true)
        }

    private fun detectAuthor(text: String): String? = text.lineSequence()
        .take(60)
        .mapNotNull { AUTHOR_LINE.find(it.trim())?.groupValues?.getOrNull(1)?.trim() }
        .firstOrNull { it.length in 2..80 }

    private companion object {
        const val MAX_SECTION_CHARACTERS = 60_000
        val CHAPTER_HEADING = Regex(
            """^(chapter|part|book|section|act|scene)\s+([0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\b.{0,60}$""",
            RegexOption.IGNORE_CASE,
        )
        val AUTHOR_LINE = Regex("""^(?:by|author:)\s+(.+)$""", RegexOption.IGNORE_CASE)
    }
}

internal data class MarkdownParagraph(
    val text: String,
    val spans: List<InlineSpan>,
    val kind: BlockKind,
    val marker: String?,
)

/**
 * A deliberately small Markdown subset: headings, emphasis, inline code, list markers and
 * block quotes. Anything more elaborate belongs in a Markdown file, not an audiobook.
 */
internal fun parseMarkdownParagraph(raw: String): MarkdownParagraph {
    var text = raw
    var kind = BlockKind.PARAGRAPH
    var marker: String? = null

    when {
        text.startsWith("#") -> {
            val level = text.takeWhile { it == '#' }.length.coerceIn(1, 6)
            text = text.trimStart('#').trim()
            kind = BlockKind.entries.first { it.headingLevel == level }
        }

        text.startsWith("> ") -> {
            text = text.removePrefix("> ").trim()
            kind = BlockKind.BLOCKQUOTE
        }

        BULLET.matchAt(text, 0) != null -> {
            val match = BULLET.matchAt(text, 0)!!
            marker = match.value.trim()
            text = text.substring(match.value.length)
            kind = BlockKind.LIST_ITEM
        }
    }

    val spans = ArrayList<InlineSpan>()
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val remaining = text.length - i
        val emphasis = when {
            remaining >= 4 && (text.startsWith("**", i) || text.startsWith("__", i)) ->
                Triple(2, InlineStyle.BOLD, text.substring(i, i + 2))

            remaining >= 2 && (text[i] == '*' || text[i] == '_') ->
                Triple(1, InlineStyle.ITALIC, text[i].toString())

            remaining >= 2 && text[i] == '`' -> Triple(1, InlineStyle.CODE, "`")
            else -> null
        }
        if (emphasis != null) {
            val (width, style, delimiter) = emphasis
            val close = text.indexOf(delimiter, i + width)
            if (close > i + width) {
                val start = out.length
                out.append(text, i + width, close)
                spans.add(InlineSpan(start, out.length, setOf(style)))
                i = close + width
                continue
            }
        }
        out.append(text[i])
        i++
    }

    return MarkdownParagraph(out.toString().trim(), spans, kind, marker)
}

private val BULLET = Regex("""^([-*+]|\d{1,3}\.)\s+""")
