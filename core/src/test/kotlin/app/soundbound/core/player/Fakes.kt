package app.soundbound.core.player

import app.soundbound.core.audio.AudioClip
import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.BookSource
import app.soundbound.core.book.ChapterContent
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookMetadata
import app.soundbound.core.model.Chapter
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import app.soundbound.core.model.VoiceId
import app.soundbound.core.tts.EngineKind
import app.soundbound.core.tts.LoadedVoice
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.TtsEngine
import app.soundbound.core.tts.TtsVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import okio.Source

/** A book made of plain paragraphs, for exercising the player without any file parsing. */
internal class FakeBook(
    private val chapterTexts: List<List<String>>,
    private val titles: List<String> = chapterTexts.indices.map { "Chapter ${it + 1}" },
) : BookSource {

    override val format: BookFormat = BookFormat.PLAIN_TEXT
    override val metadata = BookMetadata(title = "A Fake Book", authors = listOf("Test Author"))

    override val chapters: List<Chapter> = chapterTexts.mapIndexed { index, paragraphs ->
        Chapter(
            index = ChapterIndex(index),
            title = titles.getOrNull(index),
            contentRef = "chapter:$index",
            approximateCharacters = paragraphs.sumOf { it.length },
        )
    }

    override val toc: List<TocEntry> = chapters.map { TocEntry(it.title!!, it.index) }

    var chapterLoadCount = 0
        private set

    override fun chapterContent(index: ChapterIndex): ChapterContent {
        chapterLoadCount++
        val paragraphs = chapterTexts[index.value]
        val blocks = ArrayList<ContentBlock>()
        val plain = StringBuilder()
        paragraphs.forEach { paragraph ->
            if (plain.isNotEmpty()) plain.append("\n\n")
            val start = plain.length
            plain.append(paragraph)
            blocks.add(ContentBlock(BlockKind.PARAGRAPH, paragraph, start))
        }
        return ChapterContent(index, titles.getOrNull(index.value), blocks, plain.toString())
    }

    override fun readResource(ref: String): Source? = null
    override fun close() = Unit
}

/** A voice that returns silence of a length proportional to the text, and counts its calls. */
internal class FakeVoice(
    override val voice: TtsVoice,
    override val sampleRate: Int = 22_050,
    private val onSynthesise: (String) -> Unit = {},
) : LoadedVoice {

    val spoken = ArrayList<String>()
    var closed = false
        private set

    override suspend fun synthesise(text: String, params: SpeechParams): AudioClip {
        spoken.add(text)
        onSynthesise(text)
        // 60 ms per word is roughly natural, and keeps the arithmetic easy to reason about.
        val words = text.split(' ').count { it.isNotBlank() }.coerceAtLeast(1)
        return AudioClip(FloatArray(sampleRate * 60 * words / 1000) { 0.2f }, sampleRate)
    }

    override fun close() { closed = true }
}

internal class FakeEngine(private val voices: List<TtsVoice>) : TtsEngine {
    override val kind: EngineKind = EngineKind.SYSTEM
    override val isAvailable: Boolean = true
    val loaded = ArrayList<FakeVoice>()

    override suspend fun installedVoices(): List<TtsVoice> = voices

    override suspend fun load(voice: TtsVoice): LoadedVoice =
        FakeVoice(voice).also { loaded.add(it) }

    companion object {
        fun voice(
            id: String = "fake/narrator",
            language: String = "en-GB",
        ) = TtsVoice(
            id = VoiceId(id),
            displayName = "Fake Narrator",
            engine = EngineKind.SYSTEM,
            language = language,
            isInstalled = true,
        )
    }
}

/**
 * An audio sink that never actually plays anything.
 *
 * Clips are buffered and become "audible" only when the test calls [playNext], and [enqueue]
 * suspends once the buffer is full exactly as a real device does. That back-pressure is the
 * mechanism the controller relies on to stay a sentence or two ahead of playback rather than
 * rendering a whole chapter into memory, so the fake reproduces it rather than papering over it.
 */
internal class FakeSink(private val capacity: Int = 3) : AudioSink {

    override var sampleRate: Int = 22_050
        private set

    private val _position = MutableStateFlow(PlaybackPosition())
    override val position: StateFlow<PlaybackPosition> = _position

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    private val space = Semaphore(capacity)
    private val queue = ArrayDeque<Pair<Long, AudioClip>>()

    val played = ArrayList<Long>()
    var startCount = 0
        private set
    var flushCount = 0
        private set
    var closed = false
        private set

    override fun start(sampleRate: Int) {
        this.sampleRate = sampleRate
        startCount++
        _isPlaying.value = true
    }

    override suspend fun enqueue(clipId: Long, clip: AudioClip) {
        space.acquire()
        queue.addLast(clipId to clip)
    }

    override suspend fun enqueueSilence(clipId: Long, millis: Int) {
        enqueue(clipId, AudioClip.silence(millis, sampleRate))
    }

    /** Marks the head of the buffer as audible. Returns false when nothing is buffered. */
    fun playNext(): Boolean {
        val (id, clip) = queue.removeFirstOrNull() ?: return false
        space.release()
        played.add(id)
        _position.value = PlaybackPosition(id, clip.samples.size, clip.samples.size)
        return true
    }

    fun queuedCount(): Int = queue.size

    override fun pause() { _isPlaying.value = false }
    override fun resume() { _isPlaying.value = true }

    override fun flushAndStop() {
        val discarded = queue.size
        queue.clear()
        repeat(discarded) { space.release() }
        flushCount++
        _isPlaying.value = false
        _position.value = PlaybackPosition()
    }

    /**
     * Plays everything buffered. The controller only calls this once it has finished rendering,
     * so nothing can arrive while the loop runs and it always terminates.
     */
    override suspend fun drain() {
        while (playNext()) Unit
    }

    override fun setVolume(volume: Float) = Unit

    override fun bufferedMillis(): Int = queue.sumOf { (_, clip) -> clip.durationMillis }.toInt()

    override fun close() { closed = true }
}
