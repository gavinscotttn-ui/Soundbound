package app.soundbound.core.book

import app.soundbound.core.book.epub.EpubParser
import app.soundbound.core.model.ChapterIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EpubParserTest {

    @TempDir
    lateinit var tempDir: File

    private fun openBook(builder: EpubTestFixtures.EpubBuilder.() -> Unit): BookSource {
        val file = EpubTestFixtures.write(File(tempDir, "book-${System.nanoTime()}.epub"), builder)
        val handle = TestFileHandle(file)
        val parser = EpubParser()
        assertTrue(parser.canOpen(file.name, file.readBytes().copyOf(128))) {
            "The parser refused to recognise its own fixture"
        }
        return parser.open(handle)
    }

    @Test
    fun `metadata is read and the author name is put the right way round`() {
        openBook {
            title = "Pride and Prejudice"
            author = "Austen, Jane"
            chapter("c1.xhtml", "<p>It is a truth universally acknowledged.</p>")
        }.use { book ->
            assertEquals("Pride and Prejudice", book.metadata.title)
            assertEquals(listOf("Jane Austen"), book.metadata.authors)
            assertEquals("en-GB", book.metadata.language)
            assertEquals("Jane Austen", book.metadata.authorLine)
            assertEquals("Austen, Jane", book.metadata.sortAuthor)
        }
    }

    @Test
    fun `headings paragraphs and emphasis survive the round trip`() {
        openBook {
            chapter(
                "c1.xhtml",
                """
                <h1 id="start">Chapter One</h1>
                <p>She said <em>nothing</em> at all, and <strong>meant</strong> it.</p>
                <blockquote><p>A quoted remark.</p></blockquote>
                <ul><li>First</li><li>Second</li></ul>
                """,
            )
        }.use { book ->
            val content = book.chapterContent(ChapterIndex(0))
            content.validate()

            val kinds = content.blocks.map { it.kind }
            assertTrue(BlockKind.HEADING_1 in kinds)
            assertTrue(BlockKind.BLOCKQUOTE in kinds)
            assertEquals(2, kinds.count { it == BlockKind.LIST_ITEM })

            val heading = content.blocks.first { it.kind == BlockKind.HEADING_1 }
            assertEquals("Chapter One", heading.text)
            assertTrue("start" in heading.anchors) { "The heading should carry its own id" }

            val paragraph = content.blocks.first { it.kind == BlockKind.PARAGRAPH }
            assertEquals("She said nothing at all, and meant it.", paragraph.text)
            val italic = paragraph.spans.first { InlineStyle.ITALIC in it.styles }
            assertEquals("nothing", paragraph.text.substring(italic.start, italic.end))
            val bold = paragraph.spans.first { InlineStyle.BOLD in it.styles }
            assertEquals("meant", paragraph.text.substring(bold.start, bold.end))
        }
    }

    @Test
    fun `the plain text and block offsets always agree`() {
        openBook {
            chapter(
                "c1.xhtml",
                """
                <h2>A Heading</h2>
                <p>One paragraph with   sloppy    whitespace.</p>
                <p>Another<br/>with a line break.</p>
                <img src="pic.png" alt="A picture"/>
                <p>And a final paragraph.</p>
                """,
            )
        }.use { book ->
            val content = book.chapterContent(ChapterIndex(0))
            content.validate()
            assertTrue(content.plainText.contains("One paragraph with sloppy whitespace."))
            content.blocks.forEach { block ->
                assertEquals(
                    block.text,
                    content.plainText.substring(block.textStart, block.textEnd),
                ) { "Block of kind ${block.kind} is misaligned with the plain text" }
            }
        }
    }

    @Test
    fun `the navigation document becomes the table of contents`() {
        openBook {
            chapter("c1.xhtml", "<h1 id=\"one\">One</h1><p>Text.</p>")
            chapter("c2.xhtml", "<h1 id=\"two\">Two</h1><p>Text.</p>")
            navXhtml = """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body><nav epub:type="toc">
                  <ol>
                    <li><a href="c1.xhtml#one">Chapter One</a>
                      <ol><li><a href="c1.xhtml#one">A Subsection</a></li></ol>
                    </li>
                    <li><a href="c2.xhtml#two">Chapter Two</a></li>
                  </ol>
                </nav></body></html>
            """.trimIndent()
        }.use { book ->
            assertEquals(2, book.toc.size)
            assertEquals("Chapter One", book.toc[0].title)
            assertEquals(ChapterIndex(0), book.toc[0].chapter)
            assertEquals("one", book.toc[0].fragment)
            assertEquals(1, book.toc[0].children.size)
            assertEquals("A Subsection", book.toc[0].children.first().title)
            assertEquals(ChapterIndex(1), book.toc[1].chapter)
        }
    }

    @Test
    fun `an EPUB 2 NCX is understood too`() {
        openBook {
            version = "2.0"
            chapter("c1.xhtml", "<p>One.</p>")
            chapter("c2.xhtml", "<p>Two.</p>")
            ncxXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="n1" playOrder="1">
                      <navLabel><text>The First Part</text></navLabel>
                      <content src="c1.xhtml"/>
                    </navPoint>
                    <navPoint id="n2" playOrder="2">
                      <navLabel><text>The Second Part</text></navLabel>
                      <content src="c2.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
            """.trimIndent()
        }.use { book ->
            assertEquals(listOf("The First Part", "The Second Part"), book.toc.map { it.title })
        }
    }

    @Test
    fun `a book with no navigation still gets a contents list`() {
        openBook {
            chapter("c1.xhtml", "<h1>Opening Remarks</h1><p>Text.</p>")
            chapter("c2.xhtml", "<h1>Closing Remarks</h1><p>Text.</p>")
        }.use { book ->
            assertEquals(2, book.toc.size)
            assertEquals("Opening Remarks", book.toc[0].title)
            assertEquals("Closing Remarks", book.toc[1].title)
        }
    }

    @Test
    fun `footnotes are lifted out of the spoken flow`() {
        openBook {
            chapter(
                "c1.xhtml",
                """
                <p>The battle was lost<a epub:type="noteref" href="#fn1">1</a> that morning.</p>
                <aside epub:type="footnote" id="fn1"><p>Some say it was won.</p></aside>
                """,
            )
        }.use { book ->
            val content = book.chapterContent(ChapterIndex(0))
            content.validate()
            assertTrue(content.notes.containsKey("fn1"))
            assertEquals("Some say it was won.", content.notes["fn1"])
            assertTrue(content.plainText.contains("The battle was lost"))
            assertTrue(!content.plainText.contains("Some say it was won")) {
                "The footnote body must not appear inline: it would be read out mid-sentence"
            }
            val noteSpan = content.blocks.flatMap { it.spans }.first { it.noteRef != null }
            assertEquals("fn1", noteSpan.noteRef)
        }
    }

    @Test
    fun `resources resolve through relative and case-mismatched paths`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        openBook {
            binaries["images/pic.png"] = png
            coverItem = "images/pic.png"
            chapter("c1.xhtml", """<p>Look.</p><img src="./images/pic.png" alt="x"/>""")
        }.use { book ->
            val content = book.chapterContent(ChapterIndex(0))
            val image = content.blocks.first { it.kind == BlockKind.IMAGE }
            assertEquals("OEBPS/images/pic.png", image.imageRef)
            assertNotNull(book.readResource(image.imageRef!!)) { "The image should resolve" }
            assertEquals(png.toList(), book.coverImage()?.toList())
        }
    }

    @Test
    fun `anchors can be located by offset for table of contents jumps`() {
        openBook {
            chapter(
                "c1.xhtml",
                """<p>Opening.</p><h2 id="part-two">Part Two</h2><p>Continuing.</p>""",
            )
        }.use { book ->
            val content = book.chapterContent(ChapterIndex(0))
            val offset = content.offsetOfAnchor("part-two")
            assertNotNull(offset)
            assertTrue(content.plainText.startsWith("Part Two", offset!!))
            assertEquals("Part Two", content.blockAtOffset(offset)?.text)
        }
    }

    @Test
    fun `tables are given a readable linear form`() {
        openBook {
            chapter(
                "c1.xhtml",
                """<table><tr><th>Year</th><th>Event</th></tr><tr><td>1066</td><td>Hastings</td></tr></table>""",
            )
        }.use { book ->
            val content = book.chapterContent(ChapterIndex(0))
            content.validate()
            val table = content.blocks.first { it.kind == BlockKind.TABLE }
            assertEquals(listOf(listOf("Year", "Event"), listOf("1066", "Hastings")), table.tableRows)
            assertTrue(table.text.contains("Year, Event"))
        }
    }
}
