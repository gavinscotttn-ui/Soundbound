package app.soundbound.core.player

import app.soundbound.core.model.ReadingProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Listening time estimate")
class ListeningEstimateTest {

    @Test
    fun `a novel's worth of text comes out at a plausible listening length`() {
        // 400,000 characters is a long novel — War and Peace is about three million.
        val millis = ListeningEstimate.millisFor(400_000)
        val hours = millis / 3_600_000.0
        assertTrue(hours in 7.0..8.0, "Expected roughly 7-8 hours, got $hours")
    }

    @Test
    fun `doubling the speed halves the time`() {
        val normal = ListeningEstimate.millisFor(100_000, rate = 1f)
        val quick = ListeningEstimate.millisFor(100_000, rate = 2f)
        // Within a millisecond: the two divisions round independently.
        assertTrue(kotlin.math.abs(normal / 2 - quick) <= 1, "$normal vs $quick")
    }

    @Test
    fun `an absurd rate is clamped rather than dividing by nearly zero`() {
        val silly = ListeningEstimate.millisFor(100_000, rate = 0f)
        val slowest = ListeningEstimate.millisFor(100_000, rate = 0.25f)
        assertEquals(slowest, silly)
    }

    @Test
    fun `no text takes no time`() {
        assertEquals(0L, ListeningEstimate.millisFor(0))
        assertEquals(0L, ListeningEstimate.millisFor(-5))
    }

    @Test
    fun `remaining never goes negative when the counts disagree`() {
        // Progress counters can briefly overshoot when a chapter is re-parsed.
        val progress = ReadingProgress(1.0, charactersRead = 120, charactersTotal = 100, 1.0)
        assertEquals(0L, ListeningEstimate.remainingMillis(progress))
    }

    @Test
    fun `elapsed and remaining add up to the whole`() {
        val progress = ReadingProgress(0.4, charactersRead = 40_000, charactersTotal = 100_000, 0.4)
        val total = ListeningEstimate.totalMillis(progress)
        val sum = ListeningEstimate.elapsedMillis(progress) + ListeningEstimate.remainingMillis(progress)
        assertTrue(kotlin.math.abs(total - sum) <= 1, "$total vs $sum")
    }

    @Test
    fun `durations are described the way a person would say them`() {
        assertEquals("under a minute", ListeningEstimate.describe(30_000))
        assertEquals("38 min", ListeningEstimate.describe(38 * 60_000L))
        assertEquals("1 hour", ListeningEstimate.describe(60 * 60_000L))
        assertEquals("3 hours", ListeningEstimate.describe(3 * 60 * 60_000L))
        assertEquals("4h 12m", ListeningEstimate.describe((4 * 60 + 12) * 60_000L))
    }

    @Test
    fun `an unopened book has nothing to say about how long is left`() {
        assertNull(ListeningEstimate.describeRemaining(ReadingProgress.NONE))
    }

    @Test
    fun `a book in progress says how long is left`() {
        val progress = ReadingProgress(0.5, charactersRead = 200_000, charactersTotal = 400_000, 0.5)
        assertEquals("About 3h 54m left", ListeningEstimate.describeRemaining(progress))
    }
}
