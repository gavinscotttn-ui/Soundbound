package app.soundbound.core.export

import app.soundbound.core.audio.WavCodec
import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.BookSource
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.model.Book
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookId
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import app.soundbound.core.model.VoiceId
import app.soundbound.core.audio.AudioClip
import app.soundbound.core.tts.EngineKind
import app.soundbound.core.tts.LoadedVoice
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.TtsEngine
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.tts.VoiceRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class AudiobookExporterTest {

    @TempDir
    lateinit var tempDir: File

    private val book = Book(
        id = BookId("abc"),
        metadata = BookMetadata(
            title = "A Book: With / Awkward \\ Characters",
            authors = listOf("Jane Austen"),
            publishedDate = "1813-01-28",
        ),
        format = BookFormat.EPUB,
        sourceUri = "/tmp/book.epub",
        chapterCount = 3,
    )

    private class StubBook(private val chapterCount: Int = 3) : BookSource {
        override val format = BookFormat.EPUB
        override val metadata = BookMetadata("A Book", listOf("Jane Austen"))
        override val chapters = (0 until chapterCount).map {
            Chapter(ChapterIndex(it), "Chapter ${it + 1}", "c$it", 100)
        }
        override val toc = chapters.map { TocEntry(it.title!!, it.index) }

        override fun chapterContent(index: ChapterIndex): ChapterContent {
            val heading = "Chapter ${index.value + 1}"
            val body = "The first sentence of this chapter. The second sentence of it."
            val plain = "$heading\n\n$body"
            return ChapterContent(
                chapterIndex = index,
                title = heading,
                blocks = listOf(
                    ContentBlock(BlockKind.HEADING_1, heading, 0),
                    ContentBlock(BlockKind.PARAGRAPH, body, heading.length + 2),
                ),
                plainText = plain,
            )
        }

        override fun readResource(ref: String): Source? = null
        override fun close() = Unit
    }

    /** A voice that emits a real tone, so the encoded output has something to measure. */
    private class ToneVoice(override val voice: TtsVoice) : LoadedVoice {
        override val sampleRate = 22_050
        var calls = 0
            private set

        override suspend fun synthesise(text: String, params: SpeechParams): AudioClip {
            calls++
            val words = text.split(' ').count { it.isNotBlank() }.coerceAtLeast(1)
            val count = sampleRate * 80 * words / 1000
            return AudioClip(
                FloatArray(count) { (0.4 * sin(2.0 * PI * 330.0 * it / sampleRate)).toFloat() },
                sampleRate,
            )
        }

        override fun close() = Unit
    }

    private class ToneEngine : TtsEngine {
        override val kind = EngineKind.SYSTEM
        override val isAvailable = true
        val loaded = ArrayList<ToneVoice>()
        override suspend fun installedVoices() = listOf(VOICE)
        override suspend fun load(voice: TtsVoice): LoadedVoice =
            ToneVoice(voice).also { loaded.add(it) }

        companion object {
            val VOICE = TtsVoice(
                id = VoiceId("fake/narrator"),
                displayName = "Fake Narrator",
                engine = EngineKind.SYSTEM,
                language = "en-GB",
                isInstalled = true,
            )
        }
    }

    private fun request(
        format: ExportFormat = ExportFormat.MP3,
        grouping: ExportGrouping = ExportGrouping.PER_CHAPTER,
        chapters: List<ChapterIndex> = emptyList(),
        artwork: ByteArray? = null,
    ) = ExportRequest(
        book = book,
        chapters = chapters,
        outputDirectory = File(tempDir, "out"),
        format = format,
        grouping = grouping,
        voice = ToneEngine.VOICE,
        artwork = artwork,
    )

    private fun exporter(engine: ToneEngine = ToneEngine()) =
        AudiobookExporter(VoiceRegistry(listOf(engine)))

    @Test
    fun `one MP3 per chapter is written with sortable names`() = runTest {
        val progress = exporter().export(StubBook(3), request()).toList()

        val finished = progress.filterIsInstance<ExportProgress.Finished>().single()
        assertEquals(3, finished.files.size)
        assertTrue(finished.totalDurationMillis > 0)

        val names = finished.files.map { it.name }.sorted()
        assertEquals(listOf("01 - ", "02 - ", "03 - "), names.map { it.take(5) })
        assertTrue(names.all { it.endsWith(".mp3") })
        // Every illegal filename character must be gone, or Windows refuses the write.
        assertTrue(names.none { name -> name.any { it in "\\/:*?\"<>|" } }) { names.toString() }

        finished.files.forEach { file ->
            assertTrue(file.isFile)
            assertTrue(file.length() > 1_000) { "${file.name} is only ${file.length()} bytes" }
        }
    }

    @Test
    fun `the MP3 starts with an ID3 tag naming the book and chapter`() = runTest {
        val finished = exporter().export(StubBook(2), request())
            .toList()
            .filterIsInstance<ExportProgress.Finished>()
            .single()

        val bytes = finished.files.first().readBytes()
        assertEquals("ID3", String(bytes, 0, 3, Charsets.US_ASCII))
        assertEquals(3, bytes[3].toInt()) { "Expected ID3v2.3, which every player understands" }

        val text = String(bytes, 0, minOf(bytes.size, 4_096), Charsets.ISO_8859_1)
        assertTrue(text.contains("TIT2")) { "No title frame" }
        assertTrue(text.contains("TALB")) { "No album frame" }
        assertTrue(text.contains("TPE1")) { "No artist frame" }
        assertTrue(text.contains("TRCK")) { "No track frame" }

        // The values are UTF-16LE, so the ASCII characters appear with NUL between them.
        val utf16 = String(bytes, 0, minOf(bytes.size, 4_096), Charsets.UTF_16LE)
        assertTrue(utf16.contains("Jane Austen")) { "The author is not in the tag" }
    }

    @Test
    fun `artwork is embedded when supplied`() = runTest {
        val artwork = ByteArray(2_048) { (it % 251).toByte() }
        val finished = exporter().export(StubBook(1), request(artwork = artwork))
            .toList()
            .filterIsInstance<ExportProgress.Finished>()
            .single()

        val bytes = finished.files.single().readBytes()
        val header = String(bytes, 0, minOf(bytes.size, 8_192), Charsets.ISO_8859_1)
        assertTrue(header.contains("APIC")) { "No picture frame in the tag" }
        assertTrue(header.contains("image/jpeg"))
    }

    @Test
    fun `a single file can hold the whole book`() = runTest {
        val finished = exporter()
            .export(StubBook(3), request(grouping = ExportGrouping.SINGLE_FILE))
            .toList()
            .filterIsInstance<ExportProgress.Finished>()
            .single()

        assertEquals(1, finished.files.size)
        assertFalse(finished.files.single().name.startsWith("01 - ")) {
            "A single file should not be numbered"
        }
    }

    @Test
    fun `exporting to WAV writes a header matching the audio`() = runTest {
        val finished = exporter()
            .export(StubBook(1), request(format = ExportFormat.WAV))
            .toList()
            .filterIsInstance<ExportProgress.Finished>()
            .single()

        val file = finished.files.single()
        assertTrue(file.name.endsWith(".wav"))

        val clip = WavCodec.decode(file.readBytes())
        assertEquals(22_050, clip.sampleRate)
        assertTrue(clip.samples.isNotEmpty())
        // The declared length must match what is actually there, or players report nonsense.
        val declaredMillis = clip.durationMillis
        assertTrue(kotlin.math.abs(declaredMillis - finished.totalDurationMillis) < 50) {
            "Header says ${declaredMillis}ms, the export reported ${finished.totalDurationMillis}ms"
        }
        assertTrue(clip.rms() > 0.05f) { "The WAV is silent" }
    }

    @Test
    fun `only the requested chapters are exported`() = runTest {
        val finished = exporter()
            .export(StubBook(5), request(chapters = listOf(ChapterIndex(1), ChapterIndex(3))))
            .toList()
            .filterIsInstance<ExportProgress.Finished>()
            .single()

        assertEquals(2, finished.files.size)
        assertTrue(finished.files.any { it.name.contains("Chapter 2") }) { finished.files.map { it.name }.toString() }
        assertTrue(finished.files.any { it.name.contains("Chapter 4") })
    }

    @Test
    fun `progress is reported and reaches the end`() = runTest {
        val progress = exporter().export(StubBook(2), request()).toList()

        assertTrue(progress.first() is ExportProgress.Preparing)
        val rendering = progress.filterIsInstance<ExportProgress.Rendering>()
        assertTrue(rendering.isNotEmpty())
        assertEquals(2, rendering.first().chapterCount)
        assertTrue(rendering.last().fraction > rendering.first().fraction)
        assertEquals(2, progress.filterIsInstance<ExportProgress.Wrote>().size)
        assertNotNull(progress.lastOrNull() as? ExportProgress.Finished)
    }

    @Test
    fun `no part files are left behind`() = runTest {
        exporter().export(StubBook(2), request()).toList()
        val leftovers = File(tempDir, "out").listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue(leftovers.isEmpty()) { "Left behind: ${leftovers.map { it.name }}" }
    }

    @Test
    fun `a book with no chapters fails with a readable message`() = runTest {
        val progress = exporter().export(StubBook(0), request()).toList()
        val failure = progress.filterIsInstance<ExportProgress.Failed>().single()
        assertTrue(failure.reason.contains("no chapters", ignoreCase = true)) { failure.reason }
    }

    @Test
    fun `every sentence of every chapter is actually synthesised`() = runTest {
        val engine = ToneEngine()
        AudiobookExporter(VoiceRegistry(listOf(engine)))
            .export(StubBook(2), request())
            .toList()

        // Two chapters, each a heading plus two sentences.
        assertEquals(6, engine.loaded.single().calls)
    }

    @Test
    fun `a variable bit rate export is written and tagged`() = runTest {
        val vbrRequest = request().copy(
            mp3Settings = Mp3Settings(variableBitRate = true, vbrQuality = 5),
        )
        val finished = exporter().export(StubBook(1), vbrRequest)
            .toList()
            .filterIsInstance<ExportProgress.Finished>()
            .single()

        val file = finished.files.single()
        assertTrue(file.length() > 1_000)
        assertEquals("ID3", String(file.readBytes(), 0, 3, Charsets.US_ASCII))
    }
}
