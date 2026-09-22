package app.soundbound.core.player

import app.soundbound.core.audio.AudioClip
import app.soundbound.core.audiobook.AudioFileEntry
import app.soundbound.core.audiobook.AudioTags
import app.soundbound.core.audiobook.Audiobook
import app.soundbound.core.audiobook.AudiobookBuilder
import app.soundbound.core.audiobook.EmbeddedChapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The audiobook player, driven on a deterministic dispatcher.
 *
 * The decoder is a fake that hands out blocks of countable audio, so a test can say exactly
 * where in the book playback has reached and which file it came out of. What is being checked
 * throughout is the join between files: a book is one timeline to everything above this class,
 * and every bug worth having here is a place where that illusion breaks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Audiobook playback")
class AudiobookControllerTest {

    private val rate = 8_000

    /** Decodes anything, in one-second blocks, remembering what was asked of it. */
    private class FakeDecoders(
        private val trackDurations: Map<String, Long>,
        private val rate: Int,
    ) : AudioDecoderFactory {
        val opened = mutableListOf<String>()
        val seeks = mutableListOf<Pair<String, Long>>()
        var failOn: String? = null

        override fun canDecode(uri: String) = true

        override fun open(uri: String): AudioDecoder {
            if (uri == failOn) throw AudioDecodeException("refused on purpose")
            opened += uri
            return FakeDecoder(uri, trackDurations[uri] ?: 0L, rate, seeks)
        }
    }

    private class FakeDecoder(
        private val uri: String,
        override val durationMillis: Long,
        override val sampleRate: Int,
        private val seeks: MutableList<Pair<String, Long>>,
    ) : AudioDecoder {
        override val channels = 1
        private var positionMillis = 0L

        override fun seekTo(millis: Long) {
            seeks += uri to millis
            positionMillis = millis
        }

        override fun read(): AudioClip? {
            if (positionMillis >= durationMillis) return null
            val block = minOf(BLOCK_MILLIS, durationMillis - positionMillis)
            positionMillis += block
            return AudioClip(FloatArray((sampleRate * block / 1000).toInt()), sampleRate)
        }

        override fun close() = Unit

        companion object {
            const val BLOCK_MILLIS = 1_000L
        }
    }

    private fun book(vararg durations: Long): Audiobook = AudiobookBuilder.build(
        durations.mapIndexed { index, duration ->
            AudioFileEntry(
                uri = "/book/part${index + 1}.mp3",
                fileName = "part${index + 1}.mp3",
                tags = AudioTags(
                    title = "Part ${index + 1}",
                    album = "A Book",
                    trackNumber = index + 1,
                    durationMillis = duration,
                ),
            )
        },
    )!!

    private fun durations(book: Audiobook) = book.tracks.associate { it.uri to it.durationMillis }

    /** Plays [blocks] blocks through the sink, so the position advances the way hardware would. */
    private fun TestScope.drain(sink: FakeSink, blocks: Int) {
        repeat(blocks) {
            testScheduler.runCurrent()
            if (!sink.playNext()) return
            testScheduler.runCurrent()
        }
    }

    @Test
    fun `opening a book reports its whole length and where it starts`() = runTest {
        val audiobook = book(60_000, 90_000)
        val controller = AudiobookController(
            FakeSink(),
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook, positionMillis = 30_000)

        assertEquals(150_000L, controller.state.value.durationMillis)
        assertEquals(30_000L, controller.state.value.positionMillis)
        assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
        controller.close()
    }

    @Test
    fun `playing from partway through opens the right file and seeks into it`() = runTest {
        val audiobook = book(60_000, 90_000)
        val decoders = FakeDecoders(durations(audiobook), rate)
        val controller = AudiobookController(
            FakeSink(),
            decoders,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        // 75 seconds into the book is 15 seconds into the second file.
        controller.open(audiobook, positionMillis = 75_000)
        controller.play()
        testScheduler.runCurrent()

        assertEquals("/book/part2.mp3", decoders.opened.first())
        assertEquals("/book/part2.mp3" to 15_000L, decoders.seeks.first())
        controller.close()
    }

    @Test
    fun `playback carries on into the next file without being asked`() = runTest {
        // Two short files: the first runs out after three blocks, and the second must follow.
        val audiobook = book(3_000, 5_000)
        val decoders = FakeDecoders(durations(audiobook), rate)
        val sink = FakeSink(capacity = 2)
        val controller = AudiobookController(
            sink,
            decoders,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook)
        controller.play()
        drain(sink, blocks = 6)

        assertEquals(
            listOf("/book/part1.mp3", "/book/part2.mp3"),
            decoders.opened,
            "The second file should have been opened once the first ran out",
        )
        controller.close()
    }

    @Test
    fun `the position follows what is audible, not what has been decoded`() = runTest {
        val audiobook = book(10_000)
        val sink = FakeSink(capacity = 4)
        val controller = AudiobookController(
            sink,
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook)
        controller.play()
        testScheduler.runCurrent()

        // Four one-second blocks are queued but nothing has reached the speaker yet.
        assertEquals(0L, controller.state.value.positionMillis)

        drain(sink, blocks = 3)
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        val position = controller.state.value.positionMillis
        assertTrue(
            position in 2_000..3_000,
            "Three seconds played should read as about three seconds, was ${position}ms",
        )
        controller.close()
    }

    @Test
    fun `seeking throws away what was queued and starts again in the right place`() = runTest {
        val audiobook = book(60_000, 60_000)
        val decoders = FakeDecoders(durations(audiobook), rate)
        val sink = FakeSink(capacity = 3)
        val controller = AudiobookController(
            sink,
            decoders,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook)
        controller.play()
        testScheduler.runCurrent()
        val flushesBefore = sink.flushCount

        controller.seekTo(95_000)
        testScheduler.runCurrent()

        assertTrue(sink.flushCount > flushesBefore, "The queued audio should have been discarded")
        assertEquals(95_000L, controller.state.value.positionMillis)
        assertEquals("/book/part2.mp3" to 35_000L, decoders.seeks.last())
        controller.close()
    }

    @Test
    fun `chapter skipping moves by chapter, and back restarts the chapter first`() = runTest {
        val audiobook = book(60_000, 60_000, 60_000)
        val controller = AudiobookController(
            FakeSink(),
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook, positionMillis = 70_000)
        controller.skipChapter(-1)
        assertEquals(60_000L, controller.state.value.positionMillis, "Back to the chapter's start")

        controller.skipChapter(-1)
        assertEquals(0L, controller.state.value.positionMillis, "Then to the previous chapter")

        controller.skipChapter(1)
        assertEquals(60_000L, controller.state.value.positionMillis)
        controller.close()
    }

    @Test
    fun `the chapter title follows the position`() = runTest {
        val entries = listOf(
            AudioFileEntry(
                uri = "/book/whole.m4b",
                fileName = "whole.m4b",
                tags = AudioTags(
                    album = "One Long File",
                    durationMillis = 600_000,
                    chapters = listOf(
                        EmbeddedChapter("Opening", 0, 200_000),
                        EmbeddedChapter("The Middle", 200_000, 400_000),
                        EmbeddedChapter("The End", 400_000, 600_000),
                    ),
                ),
            ),
        )
        val audiobook = AudiobookBuilder.build(entries)!!
        val controller = AudiobookController(
            FakeSink(),
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook, positionMillis = 250_000)
        assertEquals("The Middle", controller.state.value.chapterTitle)

        controller.seekTo(450_000)
        assertEquals("The End", controller.state.value.chapterTitle)
        controller.close()
    }

    @Test
    fun `skipping by seconds cannot run off either end`() = runTest {
        val audiobook = book(30_000)
        val controller = AudiobookController(
            FakeSink(),
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook, positionMillis = 10_000)
        controller.skipSeconds(-30)
        assertEquals(0L, controller.state.value.positionMillis)

        controller.skipSeconds(3600)
        assertEquals(30_000L, controller.state.value.positionMillis)
        controller.close()
    }

    @Test
    fun `a file that will not open is reported rather than passed over in silence`() = runTest {
        val audiobook = book(10_000)
        val decoders = FakeDecoders(durations(audiobook), rate).apply { failOn = "/book/part1.mp3" }
        val controller = AudiobookController(
            FakeSink(),
            decoders,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook)
        controller.play()
        testScheduler.runCurrent()

        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertNotNull(controller.state.value.errorMessage)
        controller.close()
    }

    @Test
    fun `reaching the end is reported as finished`() = runTest {
        val audiobook = book(2_000)
        val sink = FakeSink(capacity = 4)
        val controller = AudiobookController(
            sink,
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook)
        controller.play()
        drain(sink, blocks = 4)

        assertEquals(PlaybackStatus.FINISHED, controller.state.value.status)
        controller.close()
    }

    @Test
    fun `the position is handed back for saving, but not on every update`() = runTest {
        val audiobook = book(20_000)
        val sink = FakeSink(capacity = 4)
        val saved = mutableListOf<Long>()
        val controller = AudiobookController(
            sink,
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            onPositionChanged = { saved += it },
        )

        controller.open(audiobook)
        controller.play()
        drain(sink, blocks = 4)
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        assertTrue(saved.isNotEmpty(), "The position should have been offered for saving")
        // A book runs for hours; saving on every hundred-millisecond poll would hammer the disk.
        assertTrue(saved.size <= 10, "Saved ${saved.size} times in four seconds of audio")
        controller.close()
    }

    @Test
    fun `changing speed discards what was queued at the old speed`() = runTest {
        val audiobook = book(60_000)
        val sink = FakeSink(capacity = 3)
        val controller = AudiobookController(
            sink,
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook)
        controller.play()
        testScheduler.runCurrent()
        val flushesBefore = sink.flushCount

        controller.setSpeed(1.5f)
        testScheduler.runCurrent()

        assertEquals(1.5f, controller.state.value.speed)
        assertTrue(sink.flushCount > flushesBefore, "Audio stretched at the old speed must go")
        controller.close()
    }

    @Test
    fun `the sleep timer stops playback when it runs out`() = runTest {
        val audiobook = book(600_000)
        val controller = AudiobookController(
            FakeSink(capacity = 3),
            FakeDecoders(durations(audiobook), rate),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.open(audiobook)
        controller.play()
        testScheduler.runCurrent()
        controller.setSleepTimer(5_000)

        testScheduler.advanceTimeBy(6_000)
        testScheduler.runCurrent()

        assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
        controller.close()
    }
}
