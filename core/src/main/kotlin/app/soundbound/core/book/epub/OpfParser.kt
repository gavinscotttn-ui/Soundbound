package app.soundbound.core.book.epub

import app.soundbound.core.book.BookParseException
import app.soundbound.core.model.BookMetadata
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Reads `META-INF/container.xml` and the OPF package document it points at. */
internal object OpfParser {

    private const val CONTAINER_PATH = "META-INF/container.xml"

    fun findOpfPath(containerXml: String): String {
        val doc = Jsoup.parse(containerXml, "", Parser.xmlParser())
        val rootFile = doc.select("rootfile").firstOrNull { element ->
            val type = element.attr("media-type")
            type.isEmpty() || type == "application/oebps-package+xml"
        } ?: throw BookParseException("This EPUB has no rootfile in $CONTAINER_PATH.")
        val path = rootFile.attr("full-path")
        if (path.isBlank()) throw BookParseException("This EPUB's $CONTAINER_PATH names no package document.")
        return ZipPaths.normalise(ZipPaths.percentDecode(path))
    }

    fun parse(opfXml: String, opfPath: String): EpubPackage {
        val doc = Jsoup.parse(opfXml, "", Parser.xmlParser())
        val packageEl = doc.selectFirst("package")
            ?: throw BookParseException("This EPUB's package document has no <package> element.")
        val version = packageEl.attr("version").ifBlank { "2.0" }

        val manifest = parseManifest(doc)
        val spineEl = doc.selectFirst("spine")
        val spine = parseSpine(spineEl, manifest)
        if (spine.isEmpty()) throw BookParseException("This EPUB's spine is empty, so there is nothing to read.")

        val metadata = parseMetadata(doc, manifest)
        val coverItemId = findCoverItemId(doc, manifest)

        return EpubPackage(
            metadata = metadata,
            manifest = manifest,
            spine = spine,
            opfPath = opfPath,
            tocItemId = spineEl?.attr("toc")?.takeIf { it.isNotBlank() },
            coverItemId = coverItemId,
            version = version,
            pageProgressionRightToLeft = spineEl?.attr("page-progression-direction") == "rtl",
        )
    }

    private fun parseManifest(doc: Document): Map<String, ManifestItem> {
        val items = doc.select("manifest > item")
        val result = LinkedHashMap<String, ManifestItem>(items.size)
        items.forEach { el ->
            val id = el.attr("id")
            val href = el.attr("href")
            if (id.isBlank() || href.isBlank()) return@forEach
            result[id] = ManifestItem(
                id = id,
                href = href,
                mediaType = el.attr("media-type").ifBlank { guessMediaType(href) },
                properties = el.attr("properties").splitOnWhitespace(),
            )
        }
        if (result.isEmpty()) throw BookParseException("This EPUB's manifest is empty.")
        return result
    }

    private fun parseSpine(spineEl: Element?, manifest: Map<String, ManifestItem>): List<SpineItem> {
        if (spineEl == null) return emptyList()
        return spineEl.select("itemref").mapNotNull { el ->
            val item = manifest[el.attr("idref")] ?: return@mapNotNull null
            SpineItem(
                item = item,
                linear = el.attr("linear") != "no",
                properties = el.attr("properties").splitOnWhitespace(),
            )
        }
    }

    private fun parseMetadata(doc: Document, manifest: Map<String, ManifestItem>): BookMetadata {
        val meta = doc.selectFirst("metadata")
        val title = meta?.dcText("title")?.takeIf { it.isNotBlank() }
            ?: manifest.values.firstOrNull()?.href?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "Untitled"

        val authors = meta?.dcAll("creator").orEmpty()
            .ifEmpty { meta?.dcAll("contributor").orEmpty() }
            .map(::tidyAuthor)
            .filter { it.isNotBlank() }
            .distinct()

        // EPUB 3 puts series info in <meta property="belongs-to-collection">; EPUB 2 (Calibre)
        // uses <meta name="calibre:series">. Both are common enough to be worth supporting.
        val series = meta?.selectFirst("meta[property=belongs-to-collection]")?.text()?.trim()
            ?: meta?.selectFirst("meta[name=calibre:series]")?.attr("content")?.trim()
        val seriesIndex = meta?.selectFirst("meta[property=group-position]")?.text()?.trim()?.toDoubleOrNull()
            ?: meta?.selectFirst("meta[name=calibre:series_index]")?.attr("content")?.trim()?.toDoubleOrNull()

        return BookMetadata(
            title = title.collapseWhitespace(),
            authors = authors,
            series = series?.takeIf { it.isNotBlank() },
            seriesIndex = seriesIndex,
            publisher = meta?.dcText("publisher")?.takeIf { it.isNotBlank() },
            language = meta?.dcText("language")?.takeIf { it.isNotBlank() },
            description = meta?.dcText("description")?.takeIf { it.isNotBlank() }?.let(::stripTags),
            subjects = meta?.dcAll("subject").orEmpty().filter { it.isNotBlank() }.distinct(),
            identifier = meta?.dcText("identifier")?.takeIf { it.isNotBlank() },
            publishedDate = meta?.dcText("date")?.takeIf { it.isNotBlank() },
        )
    }

    private fun findCoverItemId(doc: Document, manifest: Map<String, ManifestItem>): String? {
        // EPUB 3: properties="cover-image".
        manifest.values.firstOrNull { it.isCover }?.let { return it.id }
        // EPUB 2: <meta name="cover" content="itemId"/>.
        doc.selectFirst("metadata > meta[name=cover]")?.attr("content")
            ?.takeIf { it.isNotBlank() && manifest.containsKey(it) }
            ?.let { return it }
        // Last resort: an image whose id or filename says "cover".
        return manifest.values.firstOrNull { item ->
            item.mediaType.startsWith("image/") &&
                (item.id.contains("cover", true) || item.href.contains("cover", true))
        }?.id
    }

    private fun Element.dcText(name: String): String? =
        selectFirst("dc|$name")?.text() ?: selectFirst(name)?.text()

    private fun Element.dcAll(name: String): List<String> =
        select("dc|$name").map { it.text() }.ifEmpty { select(name).map { it.text() } }

    /** "Austen, Jane" reads better in a library as "Jane Austen". */
    private fun tidyAuthor(raw: String): String {
        val value = raw.collapseWhitespace()
        val comma = value.indexOf(',')
        if (comma <= 0 || value.count { it == ',' } > 1) return value
        val last = value.substring(0, comma).trim()
        val first = value.substring(comma + 1).trim()
        return if (first.isEmpty() || last.isEmpty()) value else "$first $last"
    }

    private fun stripTags(html: String): String =
        Jsoup.parse(html).text().collapseWhitespace()

    private fun guessMediaType(href: String): String = when (href.substringAfterLast('.').lowercase()) {
        "xhtml", "html", "htm" -> "application/xhtml+xml"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ncx" -> "application/x-dtbncx+xml"
        "otf" -> "font/otf"
        "ttf" -> "font/ttf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}

internal fun String.splitOnWhitespace(): Set<String> =
    split(Regex("\\s+")).mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()

internal fun String.collapseWhitespace(): String =
    replace(Regex("\\s+"), " ").trim()
