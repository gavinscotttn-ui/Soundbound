package app.soundbound.pdfjvm

import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.book.pdf.PdfParser
import app.soundbound.core.model.ChapterIndex
import okio.Source
import okio.source
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Round-trips a PDF built with PDFBox back through the reader, so the geometry the backend
 * reports is checked against something real rather than a hand-written fixture.
 */
class PdfBoxBackendTest {

    @TempDir
    lateinit var tempDir: File

    private class FileHandle(private val file: File) : BookFileHandle {
        override val displayName: String get() = file.name
        override val sizeBytes: Long get() = file.length()
        override fun openSource(): Source = file.inputStream().source()
        override fun localPath(): String = file.absolutePath
    }

    private fun writePdf(name: String, pages: List<List<Line>>): File {
        val file = File(tempDir, name)
        PDDocument().use { document ->
            pages.forEach { lines ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    lines.forEach { line ->
                        val font = PDType1Font(
                            if (line.bold) Standard14Fonts.FontName.HELVETICA_BOLD
                            else Standard14Fonts.FontName.HELVETICA,
                        )
                        stream.beginText()
                        stream.setFont(font, line.size)
                        stream.newLineAtOffset(line.x, PDRectangle.A4.height - line.y)
                        stream.showText(line.text)
                        stream.endText()
                    }
                }
            }
            document.documentInformation.title = "A Generated Document"
            document.documentInformation.author = "Soundbound Tests"
            document.save(file)
        }
        return file
    }

    private data class Line(
        val text: String,
        val x: Float,
        val y: Float,
        val size: Float = 11f,
        val bold: Boolean = false,
    )

    @Test
    fun `text position and font size come back faithfully`() {
        val file = writePdf(
            "positions.pdf",
            listOf(
                listOf(
                    Line("A Large Heading", 60f, 80f, size = 22f, bold = true),
                    Line("Some ordinary body text on the page.", 60f, 120f, size = 11f),
                ),
            ),
        )
        PdfBoxBackend.open(file.absolutePath).use { backend ->
            assertEquals(1, backend.pageCount)
            val page = backend.page(0)
            assertEquals(2, page.lines.size)

            val heading = page.lines.first { it.text.contains("Heading") }
            val body = page.lines.first { it.text.contains("ordinary") }

            assertTrue(heading.fontSize > body.fontSize) {
                "Heading was ${heading.fontSize}pt, body ${body.fontSize}pt"
            }
            assertTrue(heading.bold) { "Helvetica-Bold should be reported as bold" }
            assertFalse(body.bold)
            assertTrue(heading.top < body.top) { "The heading sits above the body text" }
            assertTrue(heading.right > heading.left)
            assertEquals(595f, page.widthPoints, 1f)
            assertEquals(842f, page.heightPoints, 1f)
        }
    }

    @Test
    fun `a whole document reads through the reader end to end`() {
        val file = writePdf(
            "book.pdf",
            (0 until 3).map { pageIndex ->
                listOf(
                    Line("A HISTORY OF NORFOLK", 60f, 40f, size = 9f),
                    Line("Chapter ${pageIndex + 1}", 60f, 90f, size = 20f, bold = true),
                    Line("The first line of the chapter runs to the right margin and", 60f, 130f),
                    Line("then continues on the next line before stopping short.", 60f, 146f),
                    Line("${pageIndex + 1}", 300f, 800f, size = 9f),
                )
            },
        )
        val source = PdfParser(PdfBoxBackend).open(FileHandle(file))
        source.use { book ->
            assertEquals("A Generated Document", book.metadata.title)
            assertTrue(book.chapters.isNotEmpty())

            val content = book.chapterContent(ChapterIndex(0))
            content.validate()

            assertFalse(content.plainText.contains("A HISTORY OF NORFOLK")) {
                "The running head should have been stripped:\n${content.plainText}"
            }
            assertTrue(content.plainText.contains("Chapter 1"))
            assertTrue(content.plainText.contains("then continues on the next line")) {
                content.plainText
            }
            assertTrue(content.blocks.any { it.kind.isHeading }) {
                "Expected the 20pt bold line to be detected as a heading"
            }
        }
    }

    @Test
    fun `a page renders to a PNG for the cover`() {
        val file = writePdf("cover.pdf", listOf(listOf(Line("Cover page", 60f, 100f, size = 30f))))
        PdfBoxBackend.open(file.absolutePath).use { backend ->
            val png = backend.renderPage(0, 320)
            assertNotNull(png)
            // PNG magic number.
            assertEquals(listOf<Byte>(-119, 80, 78, 71), png!!.take(4))
        }
    }

    @Test
    fun `an empty page is not mistaken for a scan when text follows`() {
        val file = writePdf(
            "mixed.pdf",
            listOf(
                emptyList(),
                listOf(Line("There is plenty of extractable text on this page indeed.", 60f, 100f)),
            ),
        )
        PdfBoxBackend.open(file.absolutePath).use { backend ->
            assertEquals(2, backend.pageCount)
            assertTrue(backend.page(0).lines.isEmpty())
            assertTrue(backend.page(1).lines.isNotEmpty())
        }
    }

    @Test
    fun `a document with no text layer is reported as scanned`() {
        val file = writePdf("scan.pdf", listOf(emptyList(), emptyList()))
        PdfBoxBackend.open(file.absolutePath).use { backend ->
            assertTrue(backend.isScanned())
        }
    }
}
