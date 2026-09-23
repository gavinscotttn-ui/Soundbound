package app.soundbound.core.library

import app.soundbound.core.audiobook.AudioFileEntry
import app.soundbound.core.audiobook.AudioFormats
import app.soundbound.core.audiobook.AudioTagReader
import app.soundbound.core.audiobook.AudioTags
import app.soundbound.core.audiobook.AudiobookBuilder
import app.soundbound.core.audiobook.Id3TagReader
import app.soundbound.core.audiobook.Mp4TagReader
import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.model.Book
import app.soundbound.core.model.BookFormat
import app.soundbound.core.model.BookId
import okio.buffer
import java.io.File

/**
 * Turns the audio files a person picked into one book in the library.
 *
 * Importing an audiobook is a different job from importing an EPUB, because the unit a person
 * chooses and the unit the library holds are not the same. Thirty MP3s are one book. One M4B is
 * one book. Two M4Bs picked together are — almost always — one book in two parts, but they might
 * be two books, and getting that wrong in either direction is annoying. The rule used here is
 * simple and predictable: everything picked in one go is one book, because that is what the
 * person just expressed by picking it.
 */
class AudiobookImporter(
    private val library: LibraryRepository,
    private val coverDirectory: File,
    private val readers: List<AudioTagReader> = listOf(Mp4TagReader, Id3TagReader),
) {

    /**
     * Imports [handles] as a single audiobook.
     *
     * [folderName] is used for the title when the files have no album tag between them, which is
     * common for a ripped CD set where the folder is the only place the book's name appears.
     */
    fun import(handles: List<BookFileHandle>, folderName: String? = null): ImportResult {
        val audio = handles.filter { AudioFormats.isAudio(it.displayName) }
        if (audio.isEmpty()) {
            return ImportResult.Failed(
                handles.firstOrNull()?.displayName.orEmpty(),
                "None of those files are audio.",
            )
        }

        val entries = audio.map { handle ->
            AudioFileEntry(
                uri = handle.localPath() ?: handle.displayName,
                fileName = handle.displayName,
                sizeBytes = handle.sizeBytes,
                tags = readTags(handle),
            )
        }

        val audiobook = AudiobookBuilder.build(entries, folderName)
            ?: return ImportResult.Failed(audio.first().displayName, "This audiobook could not be read.")

        if (audiobook.totalDurationMillis <= 0) {
            return ImportResult.Failed(
                audio.first().displayName,
                "None of those files have any audio in them.",
            )
        }

        val existing = library.entries.value.firstOrNull { entry ->
            entry.book.format.isAudio &&
                entry.book.audiobook?.tracks?.map { it.uri } == audiobook.tracks.map { it.uri }
        }
        if (existing != null) return ImportResult.AlreadyPresent(existing)

        // Identity is the track list: importing the same folder twice is the same book, and
        // moving it to another directory makes it a different one, which is the honest answer —
        // the old entry's files are gone.
        val id = idFor(audiobook.tracks.map { it.uri })
        val book = Book(
            id = id,
            metadata = audiobook.metadata,
            format = BookFormat.AUDIOBOOK,
            // The first file, so that a single-file book still has a sensible source path.
            sourceUri = audiobook.tracks.first().uri,
            fileSizeBytes = audio.sumOf { it.sizeBytes },
            addedAtEpochMillis = System.currentTimeMillis(),
            coverImageRef = runCatching { saveCover(id, audiobook.coverBytes) }.getOrNull(),
            chapterCount = audiobook.chapters.size,
            audiobook = audiobook,
        )

        return ImportResult.Added(library.upsert(book))
    }

    /**
     * Reads one file's tags.
     *
     * Only the head and tail of the file are read. Tags live at one end or the other — ID3v2 at
     * the front, MP4's metadata box usually at the front but legally at the back — and reading
     * a 700MB file into memory to find a title would be indefensible.
     */
    private fun readTags(handle: BookFileHandle): AudioTags {
        val bytes = runCatching { readEnds(handle) }.getOrNull() ?: return AudioTags()
        val reader = readers.firstOrNull { it.canRead(handle.displayName, bytes) }
            ?: return AudioTags()
        return reader.read(bytes)
    }

    /**
     * The head of the file, and its tail when the file is large enough for the two not to meet.
     *
     * The two are joined with the gap left as zeroes, which keeps every offset inside the head
     * correct — an MP4 box's size field points forward by an absolute amount, and shifting the
     * bytes would send the parser to the wrong place.
     */
    private fun readEnds(handle: BookFileHandle): ByteArray {
        val path = handle.localPath()
        if (path == null) {
            // No seekable file: read what is available from the front and make do.
            return handle.openSource().buffer().use { source ->
                source.readByteArray(minOf(HEAD_BYTES.toLong(), handle.sizeBytes.coerceAtLeast(1)))
            }
        }

        val file = File(path)
        val length = file.length()
        if (length <= (HEAD_BYTES + TAIL_BYTES)) return file.readBytes()

        val out = ByteArray(length.toInt().coerceAtMost(MAX_SCAN_BYTES))
        java.io.RandomAccessFile(file, "r").use { access ->
            access.readFully(out, 0, HEAD_BYTES)
            if (out.size > HEAD_BYTES) {
                val tail = minOf(TAIL_BYTES, out.size - HEAD_BYTES)
                access.seek(length - tail)
                access.readFully(out, out.size - tail, tail)
            }
        }
        return out
    }

    /** A stable identity derived from the files the book is made of. */
    private fun idFor(uris: List<String>): BookId {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        uris.forEach { digest.update(it.toByteArray()) }
        return BookId(digest.digest().joinToString("") { "%02x".format(it) }.take(32))
    }

    private fun saveCover(id: BookId, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        if (!coverDirectory.isDirectory && !coverDirectory.mkdirs()) return null
        val file = File(coverDirectory, "${id.value}.img")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private companion object {
        /** Enough for an ID3v2 tag with embedded art, and an MP4 header with a chapter list. */
        const val HEAD_BYTES = 2 * 1024 * 1024

        /** Enough for an MP4 whose metadata box sits at the end, as some writers leave it. */
        const val TAIL_BYTES = 1024 * 1024

        /**
         * The most that is ever held in memory for one file. A file larger than this is read as
         * its head and tail with the middle left as zeroes, which is all the parsers look at.
         */
        const val MAX_SCAN_BYTES = HEAD_BYTES + TAIL_BYTES
    }
}
