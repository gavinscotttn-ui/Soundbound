package app.soundbound.core.audiobook

/**
 * Reads metadata and chapters out of an MP4 container: `.m4b`, `.m4a`, `.mp4`.
 *
 * This is the format most commercial audiobooks arrive in, and the reason it gets its own reader
 * rather than being left to the platform is the chapter list. A nine-hour book in a single file
 * is unusable without one, and no mobile metadata API exposes it.
 *
 * MP4 is a tree of length-prefixed boxes. The parts that matter here:
 *
 *   moov/mvhd                      - timescale and duration
 *   moov/udta/meta/ilst/(c)nam etc - the iTunes-style tags
 *   moov/udta/chpl                 - Nero's chapter list, the usual one in an .m4b
 *   moov/trak/.../text             - a QuickTime chapter track, the other way it is done
 *
 * Both chapter conventions are read, because files in the wild use one, the other, or both.
 */
object Mp4TagReader : AudioTagReader {

    override fun canRead(fileName: String, header: ByteArray): Boolean {
        if (AudioFormats.extensionOf(fileName) in setOf("m4a", "m4b", "mp4", "m4p")) return true
        return header.size >= 8 && String(header, 4, 4, Charsets.ISO_8859_1) == "ftyp"
    }

    override fun read(bytes: ByteArray): AudioTags = runCatching { readOrThrow(bytes) }
        .getOrDefault(AudioTags())

    private fun readOrThrow(bytes: ByteArray): AudioTags {
        val moov = findBox(bytes, 0, bytes.size, "moov") ?: return AudioTags()

        val timing = readMovieHeader(bytes, moov)
        val tags = readItemList(bytes, moov)
        val chapters = readNeroChapters(bytes, moov, timing)
            .ifEmpty { readQuickTimeChapters(bytes, moov, timing) }

        return tags.copy(
            durationMillis = timing?.durationMillis,
            chapters = chapters,
        )
    }

    // ---------------------------------------------------------------- boxes

    private data class Box(val type: String, val start: Int, val end: Int) {
        /** Where the box's payload begins, past its own size and type. */
        val contentStart: Int get() = start + 8
    }

    /**
     * Walks the boxes directly inside [from, until).
     *
     * A size of 0 means "to the end of the file" and a size of 1 means a 64-bit size follows;
     * both appear in real files, usually around the media data box.
     */
    private fun boxes(bytes: ByteArray, from: Int, until: Int): List<Box> {
        val found = mutableListOf<Box>()
        var offset = from
        while (offset + 8 <= until) {
            var size = readInt(bytes, offset).toLong() and 0xFFFFFFFFL
            val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (offset + 16 > until) break
                size = readLong(bytes, offset + 8)
                header = 16
            } else if (size == 0L) {
                size = (until - offset).toLong()
            }
            if (size < header || offset + size > until) break
            found += Box(type, offset, (offset + size).toInt())
            // Boxes with a 64-bit size carry their payload past a longer header; record that by
            // shifting the start so contentStart still lands on the payload.
            if (header == 16) found[found.lastIndex] = Box(type, offset + 8, (offset + size).toInt())
            offset += size.toInt()
        }
        return found
    }

    private fun findBox(bytes: ByteArray, from: Int, until: Int, type: String): Box? =
        boxes(bytes, from, until).firstOrNull { it.type == type }

    /** Follows a path of box types down the tree, e.g. udta -> meta -> ilst. */
    private fun descend(bytes: ByteArray, parent: Box, vararg path: String): Box? {
        var current = parent
        for ((depth, type) in path.withIndex()) {
            // `meta` is a full box: four bytes of version and flags before its children. Some
            // writers omit them, so the children are looked for at both offsets.
            val contentStart = if (depth > 0 && current.type == "meta") {
                val skipped = current.contentStart + 4
                if (findBox(bytes, skipped, current.end, type) != null) skipped else current.contentStart
            } else {
                current.contentStart
            }
            current = findBox(bytes, contentStart, current.end, type) ?: return null
        }
        return current
    }

    // ---------------------------------------------------------------- movie header

    private data class Timing(val timescale: Int, val durationMillis: Long)

    private fun readMovieHeader(bytes: ByteArray, moov: Box): Timing? {
        val mvhd = findBox(bytes, moov.contentStart, moov.end, "mvhd") ?: return null
        val version = bytes.getOrNull(mvhd.contentStart)?.toInt()?.and(0xFF) ?: return null
        val base = mvhd.contentStart + 4 // version and flags
        return if (version == 1) {
            if (base + 28 > mvhd.end) return null
            val timescale = readInt(bytes, base + 16)
            val duration = readLong(bytes, base + 20)
            if (timescale <= 0) null else Timing(timescale, duration * 1000 / timescale)
        } else {
            if (base + 16 > mvhd.end) return null
            val timescale = readInt(bytes, base + 8)
            val duration = readInt(bytes, base + 12).toLong() and 0xFFFFFFFFL
            if (timescale <= 0) null else Timing(timescale, duration * 1000 / timescale)
        }
    }

    // ---------------------------------------------------------------- iTunes tags

    private fun readItemList(bytes: ByteArray, moov: Box): AudioTags {
        val ilst = descend(bytes, moov, "udta", "meta", "ilst") ?: return AudioTags()
        val items = boxes(bytes, ilst.contentStart, ilst.end)

        fun raw(type: String): Box? = items.firstOrNull { it.type == type }

        /** Every value sits in a `data` box: four bytes of type, four reserved, then the value. */
        fun value(type: String): Pair<Int, ByteArray>? {
            val item = raw(type) ?: return null
            val data = findBox(bytes, item.contentStart, item.end, "data") ?: return null
            val start = data.contentStart + 8
            if (start > data.end) return null
            return readInt(bytes, data.contentStart) and 0xFFFFFF to
                bytes.copyOfRange(start, data.end)
        }

        fun text(type: String): String? = value(type)?.second
            ?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }

        fun number(type: String): Int? {
            val payload = value(type)?.second ?: return null
            // Track and disc numbers are a little record: two reserved bytes, then the number.
            return when {
                payload.size >= 4 -> ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                payload.size >= 2 -> ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                else -> null
            }?.takeIf { it > 0 }
        }

        val cover = value("covr")?.second?.takeIf { it.isNotEmpty() }

        return AudioTags(
            title = text("©nam"),
            artist = text("©ART"),
            album = text("©alb"),
            albumArtist = text("aART"),
            // Audible and most rippers put the narrator in the composer field.
            narrator = text("©wrt") ?: freeformText(bytes, ilst, "NARRATOR"),
            year = text("©day")?.take(4),
            comment = text("©cmt") ?: text("desc") ?: text("ldes"),
            trackNumber = number("trkn"),
            discNumber = number("disk"),
            coverBytes = cover,
        )
    }

    /**
     * Reads a `----` freeform tag, which carries its own name.
     *
     * Its children are `mean` (who defined it, usually com.apple.iTunes), `name` and `data`.
     */
    private fun freeformText(bytes: ByteArray, ilst: Box, name: String): String? {
        for (item in boxes(bytes, ilst.contentStart, ilst.end)) {
            if (item.type != "----") continue
            val nameBox = findBox(bytes, item.contentStart, item.end, "name") ?: continue
            val declared = String(
                bytes,
                nameBox.contentStart + 4,
                (nameBox.end - nameBox.contentStart - 4).coerceAtLeast(0),
                Charsets.UTF_8,
            ).trim()
            if (!declared.equals(name, ignoreCase = true)) continue
            val data = findBox(bytes, item.contentStart, item.end, "data") ?: continue
            val start = data.contentStart + 8
            if (start >= data.end) continue
            return String(bytes, start, data.end - start, Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }
        return null
    }

    // ---------------------------------------------------------------- chapters

    /**
     * Nero's `chpl`: a version, a count, then for each chapter a start time in 100-nanosecond
     * units and a length-prefixed title.
     *
     * The units are the awkward part — they are not the movie timescale, and a reader that
     * assumes they are lands every chapter in the wrong place by a factor of thousands.
     */
    private fun readNeroChapters(bytes: ByteArray, moov: Box, timing: Timing?): List<EmbeddedChapter> {
        val chpl = descend(bytes, moov, "udta", "chpl") ?: return emptyList()
        var offset = chpl.contentStart + 4 // version and flags
        if (offset >= chpl.end) return emptyList()

        // Version 1 has a four-byte count; version 0 has a single byte. Both exist.
        val version = bytes[chpl.contentStart].toInt() and 0xFF
        val count = if (version == 1) {
            val value = readInt(bytes, offset + 1)
            offset += 5
            value
        } else {
            val value = bytes[offset].toInt() and 0xFF
            offset += 1
            value
        }
        if (count <= 0 || count > MAX_CHAPTERS) return emptyList()

        val starts = mutableListOf<Pair<Long, String>>()
        repeat(count) {
            if (offset + 9 > chpl.end) return@repeat
            val hundredNanos = readLong(bytes, offset)
            offset += 8
            val titleLength = bytes[offset].toInt() and 0xFF
            offset += 1
            if (offset + titleLength > chpl.end) return@repeat
            val title = String(bytes, offset, titleLength, Charsets.UTF_8).trim()
            offset += titleLength
            starts += (hundredNanos / 10_000) to title
        }

        return toChapters(starts, timing?.durationMillis)
    }

    /**
     * A QuickTime chapter track: a text track whose samples are the titles, with the times in
     * the track's own sample table.
     *
     * Read as a fallback, because a file that has one usually has `chpl` too — but Audible's
     * conversions and some encoders write only this.
     */
    private fun readQuickTimeChapters(
        bytes: ByteArray,
        moov: Box,
        timing: Timing?,
    ): List<EmbeddedChapter> {
        for (trak in boxes(bytes, moov.contentStart, moov.end).filter { it.type == "trak" }) {
            val mdia = findBox(bytes, trak.contentStart, trak.end, "mdia") ?: continue
            val hdlr = findBox(bytes, mdia.contentStart, mdia.end, "hdlr") ?: continue
            val handler = if (hdlr.contentStart + 12 <= hdlr.end) {
                String(bytes, hdlr.contentStart + 8, 4, Charsets.ISO_8859_1)
            } else {
                continue
            }
            if (handler != "text" && handler != "sbtl") continue

            val mdhd = findBox(bytes, mdia.contentStart, mdia.end, "mdhd") ?: continue
            val trackScale = readMediaTimescale(bytes, mdhd) ?: continue

            val minf = findBox(bytes, mdia.contentStart, mdia.end, "minf") ?: continue
            val stbl = findBox(bytes, minf.contentStart, minf.end, "stbl") ?: continue

            val durations = readSampleDurations(bytes, stbl) ?: continue
            val titles = readTextSamples(bytes, stbl, durations.size)

            var elapsed = 0L
            val starts = mutableListOf<Pair<Long, String>>()
            durations.forEachIndexed { index, sampleDuration ->
                starts += (elapsed * 1000 / trackScale) to (titles.getOrNull(index).orEmpty())
                elapsed += sampleDuration
            }
            if (starts.isNotEmpty()) return toChapters(starts, timing?.durationMillis)
        }
        return emptyList()
    }

    private fun readMediaTimescale(bytes: ByteArray, mdhd: Box): Int? {
        val version = bytes.getOrNull(mdhd.contentStart)?.toInt()?.and(0xFF) ?: return null
        val base = mdhd.contentStart + 4
        val timescale = if (version == 1) {
            if (base + 20 > mdhd.end) return null
            readInt(bytes, base + 16)
        } else {
            if (base + 12 > mdhd.end) return null
            readInt(bytes, base + 8)
        }
        return timescale.takeIf { it > 0 }
    }

    /** Expands the time-to-sample table into one duration per sample. */
    private fun readSampleDurations(bytes: ByteArray, stbl: Box): List<Long>? {
        val stts = findBox(bytes, stbl.contentStart, stbl.end, "stts") ?: return null
        var offset = stbl.let { stts.contentStart + 4 }
        if (offset + 4 > stts.end) return null
        val entries = readInt(bytes, offset)
        offset += 4
        if (entries <= 0 || entries > MAX_CHAPTERS) return null

        val durations = mutableListOf<Long>()
        repeat(entries) {
            if (offset + 8 > stts.end) return@repeat
            val count = readInt(bytes, offset)
            val duration = readInt(bytes, offset + 4).toLong() and 0xFFFFFFFFL
            offset += 8
            if (count in 1..MAX_CHAPTERS && durations.size + count <= MAX_CHAPTERS) {
                repeat(count) { durations += duration }
            }
        }
        return durations.takeIf { it.isNotEmpty() }
    }

    /**
     * Reads the chapter titles out of the sample data.
     *
     * Each text sample is a two-byte length followed by the text. Only the sample *offsets* are
     * available here without walking the chunk tables, so the titles are read from the sample
     * positions when they can be resolved simply, and left blank otherwise — a chapter with the
     * right time and no name is far more useful than no chapter at all.
     */
    private fun readTextSamples(bytes: ByteArray, stbl: Box, expected: Int): List<String> {
        val stco = findBox(bytes, stbl.contentStart, stbl.end, "stco")
            ?: findBox(bytes, stbl.contentStart, stbl.end, "co64")
            ?: return emptyList()
        val wide = stco.type == "co64"

        var offset = stco.contentStart + 4
        if (offset + 4 > stco.end) return emptyList()
        val count = readInt(bytes, offset)
        offset += 4
        if (count <= 0 || count > MAX_CHAPTERS) return emptyList()

        val titles = mutableListOf<String>()
        repeat(minOf(count, expected)) {
            val position = if (wide) {
                if (offset + 8 > stco.end) return titles
                val value = readLong(bytes, offset).toInt()
                offset += 8
                value
            } else {
                if (offset + 4 > stco.end) return titles
                val value = readInt(bytes, offset)
                offset += 4
                value
            }
            if (position <= 0 || position + 2 > bytes.size) {
                titles += ""
                return@repeat
            }
            val length = ((bytes[position].toInt() and 0xFF) shl 8) or (bytes[position + 1].toInt() and 0xFF)
            titles += if (length in 1..MAX_TITLE_LENGTH && position + 2 + length <= bytes.size) {
                String(bytes, position + 2, length, Charsets.UTF_8).trim()
            } else {
                ""
            }
        }
        return titles
    }

    /** Turns a list of start times into chapters, each ending where the next begins. */
    private fun toChapters(starts: List<Pair<Long, String>>, totalMillis: Long?): List<EmbeddedChapter> {
        val ordered = starts.sortedBy { it.first }
        return ordered.mapIndexed { index, (start, title) ->
            EmbeddedChapter(
                title = title,
                startMillis = start,
                endMillis = ordered.getOrNull(index + 1)?.first ?: totalMillis,
            )
        }
    }

    // ---------------------------------------------------------------- byte helpers

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        if (offset + 8 > bytes.size) return 0
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    /** A sanity bound: no real audiobook has this many chapters, but a corrupt box might claim to. */
    private const val MAX_CHAPTERS = 20_000
    private const val MAX_TITLE_LENGTH = 1_024
}
