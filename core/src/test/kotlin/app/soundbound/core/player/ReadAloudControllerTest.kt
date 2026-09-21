package app.soundbound.core.player

import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.ReadingPosition
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.VoiceRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudControllerTest {

    /**
     * One scheduler for everything: the test scope, the controller's coroutines and its
     * chapter parsing. Nothing runs until the test says so, which makes the whole
     * synthesis-and-playback pipeline reproducible rather than a race with a real IO thread.
     */
    private val dispatcher = StandardTestDispatcher()

    /**
     * Lets the controller work, playing each buffered clip as it appears, until the book runs
     * out. The guard is there so that a bug in the producer fails the test rather than hanging
     * the build.
     */
    private fun TestScope.playEverything(sink: FakeSink, limit: Int = 500) {
        var guard = 0
        while (guard++ < limit) {
            runCurrent()
            if (!sink.playNext()) break
        }
        runCurrent()
        assertTrue(guard < limit) { "The producer never stopped: played ${sink.played.size} clips" }
    }

    private val book = FakeBook(
        listOf(
            listOf(
                "The first sentence of chapter one. The second sentence follows it.",
                "A second paragraph in chapter one.",
            ),
            listOf("Chapter two begins here. And then it ends."),
        ),
        titles = listOf("Opening", "Closing"),
    )

    private fun harness(scope: CoroutineScope, dispatcher: CoroutineDispatcher): Harness {
        val engine = FakeEngine(listOf(FakeEngine.voice()))
        val registry = VoiceRegistry(listOf(engine))
        val sink = FakeSink()
        val positions = ArrayList<ReadingPosition>()
        val controller = ReadAloudController(
            registry = registry,
            sink = sink,
            scope = scope,
            ioDispatcher = dispatcher,
            onPositionChanged = { positions.add(it) },
        )
        return Harness(controller, sink, engine, registry, positions)
    }

    private class Harness(
        val controller: ReadAloudController,
        val sink: FakeSink,
        val engine: FakeEngine,
        val registry: VoiceRegistry,
        val positions: MutableList<ReadingPosition>,
    )

    @Test
    fun `opening a book prepares the first chapter and pauses`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())

        val state = h.controller.state.value
        assertEquals(PlaybackStatus.PAUSED, state.status)
        assertEquals("Opening", state.chapterTitle)
        assertEquals(3, state.unitCount)
        assertEquals(0, state.unitIndex)
        assertTrue(state.currentSentence.startsWith("The first sentence"))
        assertEquals(1, h.sink.startCount) { "The device should be opened once for the voice" }

        h.controller.close()
    }

    @Test
    fun `playing speaks the sentences in order`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        h.controller.play()
        assertEquals(PlaybackStatus.PLAYING, h.controller.state.value.status)

        playEverything(h.sink)

        val spoken = h.engine.loaded.single().spoken
        assertTrue(spoken.size >= 3) { "Only spoke: $spoken" }
        assertTrue(spoken[0].startsWith("The first sentence"))
        assertTrue(spoken[1].startsWith("The second sentence"))
        assertTrue(spoken[2].startsWith("A second paragraph"))

        h.controller.close()
    }

    @Test
    fun `the highlight follows what is audible, not what has been rendered`() =
        runTest(dispatcher) {
            val h = harness(this, dispatcher)
            h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
            h.controller.play()
            runCurrent()

            // Synthesis runs ahead; the highlight must still be on the first sentence.
            assertTrue(h.engine.loaded.single().spoken.size >= 2) {
                "Expected the renderer to work ahead of playback"
            }
            h.sink.playNext()
            runCurrent()
            val state = h.controller.state.value
            assertEquals(0, state.unitIndex)
            assertNotNull(state.highlightRange)
            val range = state.highlightRange!!
            val content = book.chapterContent(ChapterIndex(0))
            assertEquals(
                state.currentSentence,
                content.plainText.substring(range.first, range.last + 1),
            )

            h.controller.close()
        }

    @Test
    fun `a full stop between sentences becomes real silence in the queue`() =
        runTest(dispatcher) {
            val h = harness(this, dispatcher)
            h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
            h.controller.play()
            playEverything(h.sink)
            // One clip per sentence plus one of silence after each.
            assertTrue(h.sink.played.size >= 6) { "Played ${h.sink.played.size} clips" }
            h.controller.close()
        }

    @Test
    fun `pausing stops the device without losing the position`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        h.controller.play()
        runCurrent()
        h.sink.playNext()
        runCurrent()
        val before = h.controller.state.value.unitIndex

        h.controller.pause()
        assertEquals(PlaybackStatus.PAUSED, h.controller.state.value.status)
        assertEquals(before, h.controller.state.value.unitIndex)

        h.controller.close()
    }

    @Test
    fun `skipping forwards and backwards moves by sentence`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())

        h.controller.skipSentences(2)
        assertEquals(2, h.controller.state.value.unitIndex)
        assertTrue(h.controller.state.value.currentSentence.startsWith("A second paragraph"))

        h.controller.skipSentences(-1)
        assertEquals(1, h.controller.state.value.unitIndex)

        h.controller.close()
    }

    @Test
    fun `skipping past the end of a chapter moves to the next one`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())

        h.controller.skipSentences(3)
        assertEquals(ChapterIndex(1), h.controller.state.value.chapter)
        assertEquals("Closing", h.controller.state.value.chapterTitle)
        assertEquals(0, h.controller.state.value.unitIndex)

        h.controller.close()
    }

    @Test
    fun `skipping back before the start of a chapter moves to the previous one`() =
        runTest(dispatcher) {
            val h = harness(this, dispatcher)
            h.controller.open(book, ReadingPosition(ChapterIndex(1)), FakeEngine.voice())
            h.controller.skipSentences(-1)
            assertEquals(ChapterIndex(0), h.controller.state.value.chapter)
            h.controller.close()
        }

    @Test
    fun `skipping past the last sentence of the last chapter finishes the book`() =
        runTest(dispatcher) {
            val h = harness(this, dispatcher)
            h.controller.open(book, ReadingPosition(ChapterIndex(1)), FakeEngine.voice())
            h.controller.skipSentences(5)
            assertEquals(PlaybackStatus.FINISHED, h.controller.state.value.status)
            h.controller.close()
        }

    @Test
    fun `changing speed re-renders from the current sentence`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        h.controller.play()
        runCurrent()
        h.sink.playNext()
        runCurrent()

        val flushesBefore = h.sink.flushCount
        h.controller.setParams(SpeechParams(rate = 1.5f))
        runCurrent()

        assertEquals(1.5f, h.controller.state.value.params.rate)
        assertTrue(h.sink.flushCount > flushesBefore) {
            "Changing speed must discard audio rendered at the old speed"
        }
        assertEquals(PlaybackStatus.PLAYING, h.controller.state.value.status)

        h.controller.close()
    }

    @Test
    fun `seeking to a character offset lands on the right sentence`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())

        val content = book.chapterContent(ChapterIndex(0))
        val offset = content.plainText.indexOf("A second paragraph")
        h.controller.seekTo(ReadingPosition(ChapterIndex(0), offset))

        assertTrue(h.controller.state.value.currentSentence.startsWith("A second paragraph")) {
            "Landed on: ${h.controller.state.value.currentSentence}"
        }
        h.controller.close()
    }

    @Test
    fun `the position is reported so the library can save it`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        h.controller.play()
        playEverything(h.sink)

        assertTrue(h.positions.isNotEmpty()) { "No position updates were reported" }
        val last = h.positions.last()
        assertTrue(last.characterOffset >= 0)
        assertTrue(last.sentenceIndex >= 0)

        h.controller.close()
    }

    @Test
    fun `progress rises through the book`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        val atStart = h.controller.state.value.progress.fraction

        h.controller.skipChapter(1)
        val laterOn = h.controller.state.value.progress.fraction

        assertTrue(laterOn > atStart) { "Progress went from $atStart to $laterOn" }
        assertTrue(laterOn <= 1.0)
        h.controller.close()
    }

    @Test
    fun `playing without a voice reports a readable error rather than crashing`() =
        runTest(dispatcher) {
            val h = harness(this, dispatcher)
            h.controller.open(book, ReadingPosition.START, voice = null)
            h.controller.play()

            assertEquals(PlaybackStatus.ERROR, h.controller.state.value.status)
            assertTrue(h.controller.state.value.errorMessage!!.contains("voice"))
            h.controller.close()
        }

    @Test
    fun `a sleep timer counts down and pauses playback`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        h.controller.play()
        runCurrent()

        h.controller.setSleepTimer(3_000)
        runCurrent()
        assertNotNull(h.controller.state.value.sleepTimerMillisRemaining)

        // runTest's virtual clock skips the wait entirely.
        testScheduler.advanceTimeBy(3_500)
        assertNull(h.controller.state.value.sleepTimerMillisRemaining)
        assertEquals(PlaybackStatus.PAUSED, h.controller.state.value.status)

        h.controller.close()
    }

    @Test
    fun `cancelling the sleep timer clears it`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        h.controller.setSleepTimer(60_000)
        h.controller.setSleepTimer(null)
        assertNull(h.controller.state.value.sleepTimerMillisRemaining)
        h.controller.close()
    }

    @Test
    fun `switching voice reloads the model and re-opens the device`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        val first = h.engine.loaded.single()

        h.controller.setVoice(FakeEngine.voice(id = "fake/other"))

        assertEquals(2, h.engine.loaded.size)
        assertTrue(first.closed) { "The previous model must be released, not left resident" }
        h.controller.close()
    }

    @Test
    fun `closing releases the device and the model`() = runTest(dispatcher) {
        val h = harness(this, dispatcher)
        h.controller.open(book, ReadingPosition.START, FakeEngine.voice())
        h.controller.close()

        assertTrue(h.sink.closed)
        assertTrue(h.engine.loaded.single().closed)
    }
}
