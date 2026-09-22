package app.soundbound.core.player

import app.soundbound.core.model.ReadingProgress
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Turns a position measured in characters into one measured in time.
 *
 * A synthesised book has no recorded timeline: nothing exists until it is spoken, so there is no
 * duration to read off a file. But "how much longer" is the question a listener actually asks,
 * and the lock screen wants a scrubber, so the position is estimated from the amount of text
 * left and the speed it is being read at.
 *
 * The estimate is deliberately simple and deliberately labelled as one. It is accurate to within
 * a few per cent over a chapter, and drifts on text that is unusually dense with numbers or
 * abbreviations, because those expand when spoken — "Dr." is two characters and three syllables.
 */
object ListeningEstimate {

    /**
     * Characters of prose per second at normal speed.
     *
     * Derived from the usual measure of read-aloud speech, around 155 words per minute, and the
     * average English word length including its trailing space, about 5.5 characters:
     * 155 × 5.5 / 60 ≈ 14.2. Faster than an audiobook narrator's 150, slower than a podcast.
     */
    const val CHARACTERS_PER_SECOND = 14.2

    /** How long [characters] of prose take to speak at [rate], in milliseconds. */
    fun millisFor(characters: Long, rate: Float = 1f): Long {
        if (characters <= 0) return 0
        val safeRate = rate.coerceIn(0.25f, 4f)
        return (characters / (CHARACTERS_PER_SECOND * safeRate) * 1000.0).roundToLong()
    }

    /** How long the whole book takes at [rate]. */
    fun totalMillis(progress: ReadingProgress, rate: Float = 1f): Long =
        millisFor(progress.charactersTotal, rate)

    /** How far into the book the listener is, in milliseconds of speech. */
    fun elapsedMillis(progress: ReadingProgress, rate: Float = 1f): Long =
        millisFor(progress.charactersRead, rate)

    /** How much is left, in milliseconds. Never negative, even if the counts disagree. */
    fun remainingMillis(progress: ReadingProgress, rate: Float = 1f): Long =
        max(0L, millisFor(progress.charactersTotal - progress.charactersRead, rate))

    /**
     * A short, human phrasing of a duration: "4h 12m", "38 min", "under a minute".
     *
     * Rounded to the unit a listener cares about. Nobody plans their evening around
     * "4 hours 12 minutes and 9 seconds", and showing the seconds ticking on an estimate this
     * rough would claim a precision it does not have.
     */
    fun describe(millis: Long): String {
        if (millis < 60_000) return "under a minute"
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "$totalMinutes min"
            minutes == 0L -> if (hours == 1L) "1 hour" else "$hours hours"
            else -> "${hours}h ${minutes}m"
        }
    }

    /** "About 4h 12m left", or null when there is nothing sensible to say yet. */
    fun describeRemaining(progress: ReadingProgress, rate: Float = 1f): String? {
        if (progress.charactersTotal <= 0) return null
        return "About ${describe(remainingMillis(progress, rate))} left"
    }
}
