package app.soundbound.core.book.epub

import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.book.BookParseException
import app.soundbound.core.book.BookParser
import app.soundbound.core.book.BookSource
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import okio.Source
import okio.source
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Opens `.epub` containers (EPUB 2 and 3, including fixed-layout books read as reflowable text). */
class EpubParser : BookParser {
    override val format: BookFormat = BookFormat.EPUB

    override fun canOpen(fileName: String, header: ByteArray): Boolean {
        val looksLikeZip = header.size >= 4 &&
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
        if (!looksLikeZip) return false
        if (fileName.endsWith(".epub", ignoreCase = true)) return true
        // A conforming EPUB stores the mimetype first and uncompressed, so it is visible in
        // the first 64 bytes. Handy for files that arrived without a sensible extension.
        val text = String(header, Charsets.US_ASCII)
        return text.contains("application/epub+zip")
    }

    override fun open(file: BookFileHandle): BookSource {
        val path = file.localPath()
            ?: throw BookParseException("This EPUB must be copied to local storage before it can be opened.")
        return EpubBookSource(File(path), file.displayName)
    }
}

/**
 * A live EPUB. Holds the zip open for the lifetime of the reading session; chapter prose is
 * parsed on demand and cached in a small LRU so paging back and forth stays instant without
 * pinning a whole novel in memory.
 */
class EpubBookSource internal constructor(
    private val file: File,
    private val displayName: String,
) : BookSource {

    override val format: BookFormat = BookFormat.EPUB

    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: IOException) {
        throw BookParseException("This file could not be opened as an EPUB container.", e)
    }

    /**
     * Zip entry names are matched case-insensitively as a fallback. It is technically wrong and
     * practically essential: a depressing number of EPUBs in the wild reference `Images/x.jpg`
     * from the OPF while storing `images/x.jpg`.
     */
    private val entriesByLowerName: Map<String, ZipEntry> = buildMap {
        val names = zip.entries()
        while (names.hasMoreElements()) {
            val entry = names.nextElement()
            if (!entry.isDirectory) put(entry.name.lowercase(), entry)
        }
    }

    private val pkg: EpubPackage = run {
        val containerXml = readTextEntry("META-INF/container.xml")
            ?: throw BookParseException("This EPUB has no META-INF/container.xml, so it is not a valid container.")
        val opfPath = OpfParser.findOpfPath(containerXml)
        val opfXml = readTextEntry(opfPath)
            ?: throw BookParseException("This EPUB's package document ($opfPath) is missing from the container.")
        OpfParser.parse(opfXml, opfPath)
    }

    /** Spine entries that actually exist in the container, in reading order. */
    private val readableSpine: List<SpineItem> = pkg.spine.filter { item ->
        entry(ZipPaths.resolve(pkg.opfDirectory, item.item.href)) != null
    }.ifEmpty {
        throw BookParseException("None of this EPUB's chapters could be found inside the container.")
    }

    override val metadata: BookMetadata = pkg.metadata.let { meta ->
        if (meta.title.isNotBlank() && meta.title != "Untitled") meta
        else meta.copy(title = displayName.substringBeforeLast('.'))
    }

    override val chapters: List<Chapter> = readableSpine.mapIndexed { index, item ->
        val path = ZipPaths.resolve(pkg.opfDirectory, item.item.href)
        Chapter(
            index = ChapterIndex(index),
            title = null,
            contentRef = path,
            // Compressed size is a decent, free proxy for length. It is only used to weight
            // the progress bar before a chapter has actually been opened.
            approximateCharacters = (entry(path)?.size ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    private val cache = ChapterCache(maxEntries = 6)

    /**
     * Held in a nullable backing field because building the contents list may fall back to
     * parsing chapters, and chapter parsing consults the contents list for a title. The null
     * simply means "not built yet", which breaks the cycle without a lock.
     */
    private var tocOrNull: List<TocEntry>? = null

    override val toc: List<TocEntry> = buildToc().also { tocOrNull = it }

    private fun buildToc(): List<TocEntry> {
        val lookup = TocParser.SpineLookup(readableSpine, pkg.opfDirectory)

        // EPUB 3 navigation document first.
        pkg.manifest.values.firstOrNull { it.isNav }?.let { navItem ->
            val navPath = ZipPaths.resolve(pkg.opfDirectory, navItem.href)
            readTextEntry(navPath)?.let { xhtml ->
                val entries = runCatching { TocParser.fromNavDocument(xhtml, navPath, lookup) }.getOrDefault(emptyList())
                if (entries.isNotEmpty()) return entries
            }
        }

        // EPUB 2 NCX, either named by the spine or found by media type.
        val ncxItem = pkg.tocItemId?.let { pkg.manifest[it] }
            ?: pkg.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        ncxItem?.let { item ->
            val ncxPath = ZipPaths.resolve(pkg.opfDirectory, item.href)
            readTextEntry(ncxPath)?.let { xml ->
                val entries = runCatching { TocParser.fromNcx(xml, ncxPath, lookup) }.getOrDefault(emptyList())
                if (entries.isNotEmpty()) return entries
            }
        }

        // Nothing usable: synthesise one entry per chapter, titled from each document's own
        // first heading. Worth the parse — a contents list is not optional in a reader.
        return TocParser.fromSpine(readableSpine) { index ->
            runCatching { chapterContent(ChapterIndex(index)).title }.getOrNull()
        }
    }

    override fun chapterContent(index: ChapterIndex): ChapterContent {
        cache.get(index)?.let { return it }
        val chapter = chapters.getOrNull(index.value)
            ?: throw BookParseException("Chapter ${index.value} is outside this book (${chapters.size} chapters).")
        val xhtml = readTextEntry(chapter.contentRef)
            ?: throw BookParseException("Chapter ${index.value + 1} is missing from the EPUB container.")
        val converted = XhtmlToBlocks(ZipPaths.directoryOf(chapter.contentRef)).convert(xhtml)
        val content = ChapterContent(
            chapterIndex = index,
            title = converted.firstHeading ?: tocTitleFor(index),
            blocks = converted.blocks,
            plainText = converted.plainText,
            notes = converted.notes,
        )
        cache.put(index, content)
        return content
    }

    private fun tocTitleFor(index: ChapterIndex): String? =
        tocOrNull.orEmpty().asSequence().flatMap { it.flatten() }
            .firstOrNull { it.chapter == index && it.fragment == null }?.title

    override fun readResource(ref: String): Source? {
        val path = ref.substringBefore('#')
        val entry = entry(path) ?: return null
        return zip.getInputStream(entry).source()
    }

    override fun coverImage(): ByteArray? {
        pkg.coverItemId?.let { id ->
            pkg.manifest[id]?.let { item ->
                readBytesEntry(ZipPaths.resolve(pkg.opfDirectory, item.href))?.let { return it }
            }
        }
        // Some books only reveal the cover as the single image inside the first spine document.
        val firstDoc = readableSpine.firstOrNull() ?: return null
        val path = ZipPaths.resolve(pkg.opfDirectory, firstDoc.item.href)
        val content = runCatching { chapterContent(ChapterIndex(0)) }.getOrNull() ?: return null
        val imageRef = content.blocks.firstOrNull { it.imageRef != null }?.imageRef ?: return null
        return readBytesEntry(ZipPaths.resolve(ZipPaths.directoryOf(path), imageRef))
    }

    override fun styleSheets(): List<String> = pkg.manifest.values
        .filter { it.mediaType == "text/css" }
        .mapNotNull { readTextEntry(ZipPaths.resolve(pkg.opfDirectory, it.href)) }

    private fun entry(path: String): ZipEntry? =
        zip.getEntry(path) ?: entriesByLowerName[path.lowercase()]

    private fun readBytesEntry(path: String): ByteArray? {
        val entry = entry(path) ?: return null
        return runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    }

    private fun readTextEntry(path: String): String? {
        val bytes = readBytesEntry(path) ?: return null
        return decodeText(bytes)
    }

    override fun close() {
        cache.clear()
        runCatching { zip.close() }
    }
}

/**
 * XHTML files declare their own encoding, and a fair few lie about it. We honour a BOM,
 * then an XML declaration or meta charset, then fall back to UTF-8.
 */
internal fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }

    val prologue = String(bytes, 0, minOf(bytes.size, 2048), Charsets.US_ASCII)
    val declared = DECLARED_ENCODING.find(prologue)?.groupValues?.getOrNull(1)
        ?: META_CHARSET.find(prologue)?.groupValues?.getOrNull(1)
    if (declared != null && !declared.equals("utf-8", ignoreCase = true)) {
        runCatching { return String(bytes, charset(declared)) }
    }
    return String(bytes, Charsets.UTF_8)
}

private val DECLARED_ENCODING = Regex("""encoding\s*=\s*["']([\w.:-]+)["']""", RegexOption.IGNORE_CASE)
private val META_CHARSET = Regex("""charset\s*=\s*["']?([\w.:-]+)""", RegexOption.IGNORE_CASE)

/** A tiny LRU keyed by chapter. Deliberately small: parsed chapters are fat. */
internal class ChapterCache(private val maxEntries: Int) {
    private val entries = LinkedHashMap<Int, ChapterContent>(maxEntries, 0.75f, true)

    @Synchronized
    fun get(index: ChapterIndex): ChapterContent? = entries[index.value]

    @Synchronized
    fun put(index: ChapterIndex, content: ChapterContent) {
        entries[index.value] = content
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun clear() = entries.clear()
}
