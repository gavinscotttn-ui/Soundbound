package app.soundbound.core.audiobook

import app.soundbound.core.model.BookMetadata

/** One audio file offered for import, with whatever its tags said. */
data class AudioFileEntry(
    val uri: String,
    val fileName: String,
    val sizeBytes: Long = 0,
    val tags: AudioTags = AudioTags(),
)

/**
 * Assembles an [Audiobook] out of the files a person picked.
 *
 * The awkward part of importing an audiobook is that almost nothing can be relied on. The files
 * may be one M4B or four hundred MP3s. They may be tagged properly, tagged as music, or tagged
 * with the ripper's name and nothing else. They may be numbered 1..40, or 01..40, or
 * "Part 1 of 40", or not numbered at all. The order is the one thing that absolutely must come
 * out right: a book played in the wrong order is worthless, and it is the first thing a listener
 * notices.
 *
 * So ordering is decided in the order of how much the source can be trusted — disc and track
 * numbers first, then a natural sort of the file names — and everything else falls back through
 * a chain that ends somewhere safe.
 */
object AudiobookBuilder {

    fun build(entries: List<AudioFileEntry>, folderName: String? = null): Audiobook? {
        if (entries.isEmpty()) return null

        val ordered = entries.sortedWith(ORDER)
        var elapsed = 0L
        val tracks = ordered.mapIndexed { index, entry ->
            val duration = entry.tags.durationMillis?.coerceAtLeast(0) ?: 0L
            AudioTrack(
                index = index,
                uri = entry.uri,
                title = entry.tags.title?.takeIf { it.isNotBlank() } ?: displayName(entry.fileName),
                durationMillis = duration,
                startMillis = elapsed,
            ).also { elapsed += duration }
        }

        return Audiobook(
            metadata = metadataFor(ordered, folderName),
            tracks = tracks,
            chapters = chaptersFor(ordered, tracks),
            coverBytes = ordered.firstNotNullOfOrNull { it.tags.coverBytes?.takeIf { art -> art.isNotEmpty() } },
        )
    }

    // ---------------------------------------------------------------- ordering

    /**
     * Disc, then track, then the file name read the way a person reads it.
     *
     * Files with numbers sort ahead of files without, so that a stray "intro.mp3" with no track
     * number does not land in the middle of a numbered set.
     */
    private val ORDER = compareBy<AudioFileEntry>(
        { it.tags.discNumber ?: 1 },
        { it.tags.trackNumber ?: Int.MAX_VALUE },
    ).thenComparator { a, b -> naturalCompare(a.fileName, b.fileName) }

    /**
     * Compares names the way a person would: the digits inside them are compared as numbers.
     *
     * Plain text ordering puts "Chapter 10" before "Chapter 2", which silently shuffles a
     * forty-part book. Leading zeroes are ignored for the comparison but break ties, so
     * "track01" and "track1" have a stable order rather than being treated as equal.
     */
    fun naturalCompare(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left[i]
            val b = right[j]
            if (a.isDigit() && b.isDigit()) {
                val startI = i
                val startJ = j
                while (i < left.length && left[i].isDigit()) i++
                while (j < right.length && right[j].isDigit()) j++
                val numberA = left.substring(startI, i).trimStart('0')
                val numberB = right.substring(startJ, j).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length - numberB.length
                val byValue = numberA.compareTo(numberB)
                if (byValue != 0) return byValue
                // Same value: "01" before "1", so the order is at least deterministic.
                val byWidth = (i - startI) - (j - startJ)
                if (byWidth != 0) return byWidth
            } else {
                val byChar = a.lowercaseChar().compareTo(b.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (left.length - i) - (right.length - j)
    }

    // ---------------------------------------------------------------- metadata

    private fun metadataFor(entries: List<AudioFileEntry>, folderName: String?): BookMetadata {
        val tags = entries.map { it.tags }

        val title = tags.firstNotNullOfOrNull { it.album?.trim()?.takeIf(String::isNotEmpty) }
            ?: folderName?.trim()?.takeIf(String::isNotEmpty)
            ?: sharedPrefix(entries.map { displayName(it.fileName) })
            ?: tags.firstNotNullOfOrNull { it.title?.trim()?.takeIf(String::isNotEmpty) }
            ?: "Untitled audiobook"

        // The author is the album artist where there is one: on an audiobook the plain artist
        // field is as often the narrator as the writer.
        val author = tags.firstNotNullOfOrNull { it.albumArtist?.trim()?.takeIf(String::isNotEmpty) }
            ?: tags.firstNotNullOfOrNull { it.artist?.trim()?.takeIf(String::isNotEmpty) }

        val narrator = tags.firstNotNullOfOrNull { it.narrator?.trim()?.takeIf(String::isNotEmpty) }
            ?.takeIf { !it.equals(author, ignoreCase = true) }

        return BookMetadata(
            title = title,
            authors = listOfNotNull(author),
            publishedDate = tags.firstNotNullOfOrNull { it.year?.trim()?.takeIf(String::isNotEmpty) },
            description = tags.firstNotNullOfOrNull { it.comment?.trim()?.takeIf(String::isNotEmpty) },
            // Recorded as a subject so it shows on the book's details without inventing a field
            // that only audiobooks would ever use.
            subjects = listOfNotNull(narrator?.let { "Narrated by $it" }),
        )
    }

    /** The file name without its extension or a leading track number. */
    fun displayName(fileName: String): String {
        val withoutExtension = fileName.substringBeforeLast('.', fileName)
        return withoutExtension
            .replace(Regex("^\\s*\\d{1,3}\\s*[-._)]\\s*"), "")
            .replace('_', ' ')
            .trim()
            .ifEmpty { withoutExtension }
    }

    /**
     * The common opening of every file name, when there is a substantial one.
     *
     * "Bleak House 01", "Bleak House 02" ... gives "Bleak House", which is a far better title
     * than the first file's name. Trimmed back to a word boundary so it never ends mid-word.
     */
    private fun sharedPrefix(names: List<String>): String? {
        if (names.size < 2) return null
        var prefix = names.first()
        for (name in names.drop(1)) {
            var length = 0
            while (length < prefix.length && length < name.length &&
                prefix[length].lowercaseChar() == name[length].lowercaseChar()
            ) {
                length++
            }
            prefix = prefix.substring(0, length)
            if (prefix.isEmpty()) return null
        }
        return prefix.trimEnd { !it.isLetterOrDigit() }
            .substringBeforeLast(' ', prefix.trimEnd { !it.isLetterOrDigit() })
            .trim()
            .takeIf { it.length >= MINIMUM_PREFIX }
    }

    // ---------------------------------------------------------------- chapters

    /**
     * Works out the chapter list.
     *
     * A file that declares its own chapters is believed, and those are shifted into book time.
     * A file that does not becomes one chapter, because for a folder of MP3s that is exactly
     * what each file is. The two cases mix freely — a book can be one tagged M4B followed by an
     * untagged epilogue — so both are handled per file rather than for the book as a whole.
     */
    private fun chaptersFor(
        entries: List<AudioFileEntry>,
        tracks: List<AudioTrack>,
    ): List<AudioChapter> {
        val chapters = mutableListOf<AudioChapter>()

        entries.forEachIndexed { index, entry ->
            val track = tracks[index]
            val embedded = entry.tags.chapters.filter { it.startMillis < track.durationMillis }

            if (embedded.isEmpty()) {
                chapters += AudioChapter(
                    index = chapters.size,
                    title = track.title ?: "Part ${index + 1}",
                    startMillis = track.startMillis,
                    endMillis = track.endMillis,
                )
            } else {
                embedded.sortedBy { it.startMillis }.forEachIndexed { position, chapter ->
                    val nextStart = embedded.getOrNull(position + 1)?.startMillis
                    val end = chapter.endMillis ?: nextStart ?: track.durationMillis
                    chapters += AudioChapter(
                        index = chapters.size,
                        title = chapter.title.ifBlank { "Chapter ${chapters.size + 1}" },
                        startMillis = track.startMillis + chapter.startMillis,
                        endMillis = track.startMillis + end.coerceAtMost(track.durationMillis),
                    )
                }
            }
        }

        return chapters
    }

    private const val MINIMUM_PREFIX = 4
}
