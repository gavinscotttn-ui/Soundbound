package app.soundbound.core.audiobook

import app.soundbound.core.model.BookMetadata

/**
 * One audio file in an audiobook.
 *
 * [startMillis] is where this file begins within the whole book, so that a single timeline runs
 * across a folder of forty MP3s exactly as it does across one M4B. Everything above this — the
 * scrubber, the sleep timer, the position saved when the app closes — works in book time and
 * never has to know how many files there are.
 */
data class AudioTrack(
    val index: Int,
    /** Where the file is: a path on the desktop, a copied file or content URI on Android. */
    val uri: String,
    val title: String?,
    val durationMillis: Long,
    val startMillis: Long,
) {
    val endMillis: Long get() = startMillis + durationMillis

    operator fun contains(bookMillis: Long): Boolean =
        bookMillis >= startMillis && bookMillis < endMillis
}

/**
 * A chapter of an audiobook.
 *
 * Chapters and files are not the same thing and the app must not assume they are. A folder of
 * MP3s usually has one chapter per file; a single M4B usually has thirty chapters inside one
 * file, listed in its own chapter table. Both end up here, in book time.
 */
data class AudioChapter(
    val index: Int,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)

    operator fun contains(bookMillis: Long): Boolean =
        bookMillis >= startMillis && bookMillis < endMillis
}

/**
 * An audiobook that already exists as audio — imported, not synthesised.
 *
 * Soundbound's own reading and a recorded audiobook are deliberately modelled as the same kind
 * of thing to everything above the player: one book, one position, one set of bookmarks, one
 * progress figure. Only the layer that actually produces sound knows the difference.
 */
data class Audiobook(
    val metadata: BookMetadata,
    val tracks: List<AudioTrack>,
    val chapters: List<AudioChapter>,
    val coverBytes: ByteArray? = null,
) {
    val totalDurationMillis: Long = tracks.sumOf { it.durationMillis }

    /** The file that holds a given point in the book, or null if the point is past the end. */
    fun trackAt(bookMillis: Long): AudioTrack? {
        if (tracks.isEmpty()) return null
        if (bookMillis < 0) return tracks.first()
        // Binary search: a badly split audiobook can run to several hundred files.
        var low = 0
        var high = tracks.lastIndex
        while (low <= high) {
            val middle = (low + high) / 2
            val track = tracks[middle]
            when {
                bookMillis < track.startMillis -> high = middle - 1
                bookMillis >= track.endMillis -> low = middle + 1
                else -> return track
            }
        }
        return null
    }

    /** How far into its own file a point in the book is. */
    fun offsetWithinTrack(bookMillis: Long): Long {
        val track = trackAt(bookMillis) ?: return 0
        return (bookMillis - track.startMillis).coerceAtLeast(0)
    }

    fun chapterAt(bookMillis: Long): AudioChapter? =
        chapters.lastOrNull { bookMillis >= it.startMillis } ?: chapters.firstOrNull()

    /** Start of the chapter [delta] chapters away from [bookMillis], clamped to the book. */
    fun chapterBoundary(bookMillis: Long, delta: Int): Long {
        if (chapters.isEmpty()) return bookMillis
        val current = chapterAt(bookMillis) ?: return bookMillis

        // Going back from partway into a chapter restarts it, the way every audiobook player
        // behaves — one press to the start of this chapter, a second to the previous one.
        val fromStart = bookMillis - current.startMillis
        val effective = if (delta < 0 && fromStart > RESTART_THRESHOLD_MILLIS) delta + 1 else delta

        val target = (current.index + effective).coerceIn(0, chapters.lastIndex)
        return chapters[target].startMillis
    }

    /**
     * Equality on a data class with a [ByteArray] compares the array by identity, which would
     * make two identical audiobooks unequal. The cover is excluded instead: two books with the
     * same tracks and metadata are the same book whichever copy of the artwork they carry.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Audiobook) return false
        return metadata == other.metadata && tracks == other.tracks && chapters == other.chapters
    }

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + tracks.hashCode()
        result = 31 * result + chapters.hashCode()
        return result
    }

    companion object {
        /** Past this far into a chapter, "previous" means "start this one again". */
        const val RESTART_THRESHOLD_MILLIS = 3_000L
    }
}
