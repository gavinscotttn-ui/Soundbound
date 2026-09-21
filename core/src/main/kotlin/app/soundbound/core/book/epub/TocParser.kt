package app.soundbound.core.book.epub

import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Builds the table of contents from whichever navigation document the EPUB provides.
 * EPUB 3 books should carry a `nav` document; EPUB 2 books carry an NCX; plenty of books
 * carry both, or a broken one of each, so we try in order and fall back to the spine.
 */
internal object TocParser {

    /** Maps a resolved zip path to its spine position, for turning hrefs into chapter indices. */
    internal class SpineLookup(spine: List<SpineItem>, opfDirectory: String) {
        private val byPath: Map<String, Int> = spine.withIndex().associate { (index, item) ->
            ZipPaths.resolve(opfDirectory, item.item.href) to index
        }

        fun indexOf(resolvedPath: String): Int? = byPath[resolvedPath]
    }

    fun fromNavDocument(
        navXhtml: String,
        navPath: String,
        spine: SpineLookup,
    ): List<TocEntry> {
        val doc = Jsoup.parse(navXhtml, "", Parser.htmlParser())
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" }
            ?: doc.select("nav[role=doc-toc]").firstOrNull()
            ?: doc.selectFirst("nav")
            ?: return emptyList()
        val rootList = nav.selectFirst("ol") ?: nav.selectFirst("ul") ?: return emptyList()
        return parseNavList(rootList, ZipPaths.directoryOf(navPath), spine, depth = 0)
    }

    private fun parseNavList(
        list: Element,
        baseDirectory: String,
        spine: SpineLookup,
        depth: Int,
    ): List<TocEntry> = list.children()
        .filter { it.tagName().equals("li", ignoreCase = true) }
        .mapNotNull { li -> parseNavItem(li, baseDirectory, spine, depth) }

    private fun parseNavItem(
        li: Element,
        baseDirectory: String,
        spine: SpineLookup,
        depth: Int,
    ): TocEntry? {
        val anchor = li.children().firstOrNull { it.tagName().equals("a", true) }
            ?: li.selectFirst("> span")
        val label = anchor?.text()?.collapseWhitespace().orEmpty()
        val nestedList = li.children().firstOrNull { it.tagName() == "ol" || it.tagName() == "ul" }
        val children = nestedList?.let { parseNavList(it, baseDirectory, spine, depth + 1) }.orEmpty()

        val href = anchor?.takeIf { it.tagName().equals("a", true) }?.attr("href").orEmpty()
        if (href.isBlank()) {
            // A heading-only node: keep it if it groups real entries, otherwise drop it.
            if (children.isEmpty() || label.isEmpty()) return null
            return TocEntry(label, children.first().chapter, null, children, depth)
        }

        val resolved = ZipPaths.resolve(baseDirectory, href)
        val chapter = spine.indexOf(resolved)
            ?: children.firstOrNull()?.chapter?.value
            ?: return null
        return TocEntry(
            title = label.ifBlank { "Untitled section" },
            chapter = ChapterIndex(chapter),
            fragment = ZipPaths.fragmentOf(href),
            children = children,
            depth = depth,
        )
    }

    fun fromNcx(ncxXml: String, ncxPath: String, spine: SpineLookup): List<TocEntry> {
        val doc = Jsoup.parse(ncxXml, "", Parser.xmlParser())
        val navMap = doc.selectFirst("navMap") ?: return emptyList()
        return parseNcxPoints(navMap, ZipPaths.directoryOf(ncxPath), spine, depth = 0)
    }

    private fun parseNcxPoints(
        parent: Element,
        baseDirectory: String,
        spine: SpineLookup,
        depth: Int,
    ): List<TocEntry> = parent.children()
        .filter { it.tagName().equals("navPoint", ignoreCase = true) }
        .sortedBy { it.attr("playOrder").toIntOrNull() ?: Int.MAX_VALUE }
        .mapNotNull { point ->
            val label = point.selectFirst("navLabel > text")?.text()?.collapseWhitespace().orEmpty()
            val href = point.selectFirst("content")?.attr("src").orEmpty()
            val children = parseNcxPoints(point, baseDirectory, spine, depth + 1)
            if (href.isBlank()) {
                return@mapNotNull children.firstOrNull()?.let {
                    TocEntry(label.ifBlank { it.title }, it.chapter, null, children, depth)
                }
            }
            val resolved = ZipPaths.resolve(baseDirectory, href)
            val chapter = spine.indexOf(resolved) ?: children.firstOrNull()?.chapter?.value
            ?: return@mapNotNull null
            TocEntry(
                title = label.ifBlank { "Untitled section" },
                chapter = ChapterIndex(chapter),
                fragment = ZipPaths.fragmentOf(href),
                children = children,
                depth = depth,
            )
        }

    /**
     * Last resort when a book has no usable navigation: one entry per linear spine item, titled
     * from the document's own first heading where the caller can supply one.
     */
    fun fromSpine(spine: List<SpineItem>, titleOf: (Int) -> String?): List<TocEntry> =
        spine.indices.mapNotNull { index ->
            if (!spine[index].linear) return@mapNotNull null
            TocEntry(
                title = titleOf(index) ?: "Section ${index + 1}",
                chapter = ChapterIndex(index),
            )
        }
}
