package app.soundbound.core.book

import app.soundbound.core.model.BookFormat
import okio.Source
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds real EPUB containers on disk so the parser is tested against actual zip files. */
internal object EpubTestFixtures {

    fun write(target: File, builder: EpubBuilder.() -> Unit): File {
        val spec = EpubBuilder().apply(builder)
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            // The mimetype entry must come first and be stored uncompressed, per the
            // specification. A STORED entry has to carry its own size and CRC.
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(spec.buildOpf().toByteArray())
            zip.closeEntry()

            spec.navXhtml?.let {
                zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
                zip.write(it.toByteArray())
                zip.closeEntry()
            }
            spec.ncxXml?.let {
                zip.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
                zip.write(it.toByteArray())
                zip.closeEntry()
            }
            spec.documents.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry("OEBPS/$name"))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            spec.binaries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry("OEBPS/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return target
    }

    class EpubBuilder {
        var title: String = "A Test Book"
        var author: String = "Austen, Jane"
        var language: String = "en-GB"
        var version: String = "3.0"
        var navXhtml: String? = null
        var ncxXml: String? = null
        var coverItem: String? = null
        val documents = LinkedHashMap<String, String>()
        val binaries = LinkedHashMap<String, ByteArray>()

        /** Adds a spine document whose body is [body] (no html/head boilerplate needed). */
        fun chapter(name: String, body: String) {
            documents[name] = """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>$name</title></head>
                <body>
                $body
                </body>
                </html>
            """.trimIndent()
        }

        fun buildOpf(): String {
            val manifest = buildString {
                documents.keys.forEachIndexed { index, name ->
                    append("""<item id="doc$index" href="$name" media-type="application/xhtml+xml"/>""")
                }
                binaries.keys.forEachIndexed { index, name ->
                    val properties = if (name == coverItem) """ properties="cover-image"""" else ""
                    append("""<item id="img$index" href="$name" media-type="image/png"$properties/>""")
                }
                if (navXhtml != null) {
                    append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
                }
                if (ncxXml != null) {
                    append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
                }
            }
            val spine = documents.keys.indices.joinToString("") { """<itemref idref="doc$it"/>""" }
            val tocAttribute = if (ncxXml != null) """ toc="ncx"""" else ""
            return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.w3.org/2005/OPF" version="$version" unique-identifier="pub-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="pub-id">urn:uuid:test-book</dc:identifier>
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                    <dc:language>$language</dc:language>
                  </metadata>
                  <manifest>$manifest</manifest>
                  <spine$tocAttribute>$spine</spine>
                </package>
            """.trimIndent()
        }
    }
}

/** A [BookFileHandle] over a real file on disk, for tests and for the desktop app's own use. */
internal class TestFileHandle(private val file: File) : BookFileHandle {
    override val displayName: String get() = file.name
    override val sizeBytes: Long get() = file.length()
    override fun openSource(): Source = file.inputStream().source()
    override fun localPath(): String = file.absolutePath
}

internal fun BookFormat.sanityCheck() = require(extensions.isNotEmpty())
