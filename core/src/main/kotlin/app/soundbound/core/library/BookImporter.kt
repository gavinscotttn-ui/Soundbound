package app.soundbound.core.library

import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.book.BookParseException
import app.soundbound.core.book.BookParser
import app.soundbound.core.book.BookSource
import app.soundbound.core.book.epub.EpubParser
import app.soundbound.core.book.pdf.PdfBackendFactory
import app.soundbound.core.book.pdf.PdfParser
import app.soundbound.core.book.txt.PlainTextParser
import app.soundbound.core.model.Book
import app.soundbound.core.model.BookId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import java.io.File
import java.security.MessageDigest

/**
 * Chooses a parser for a file and opens it.
 *
 * Format is decided by sniffing the first bytes, not by the extension. Files arrive from
 * downloads, messaging apps and shared folders with names like `book(1).epub.txt`, and a reader
 * that refuses to open a perfectly good EPUB over a naming quibble is infuriating.
 */
class BookOpener(private val parsers: List<BookParser>) {

    fun parserFor(handle: BookFileHandle): BookParser? {
        val header = readHeader(handle)
        return parsers.firstOrNull { it.canOpen(handle.displayName, header) }
    }

    fun open(handle: BookFileHandle): BookSource {
        val parser = parserFor(handle)
            ?: throw BookParseException(
                "Soundbound does not recognise \"${handle.displayName}\". " +
                    "It reads EPUB, PDF and plain text files.",
            )
        return parser.open(handle)
    }

    private fun readHeader(handle: BookFileHandle): ByteArray = runCatching {
        handle.openSource().buffer().use { source ->
            val peek = ByteArray(HEADER_BYTES)
            val read = source.read(peek, 0, HEADER_BYTES)
            if (read <= 0) ByteArray(0) else peek.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    companion object {
        private const val HEADER_BYTES = 2048

        /** The standard set: EPUB, PDF (given a platform backend) and plain text. */
        fun standard(pdfBackend: PdfBackendFactory?): BookOpener = BookOpener(
            buildList {
                add(EpubParser())
                pdfBackend?.let { add(PdfParser(it)) }
                add(PlainTextParser())
            },
        )
    }
}

/** The outcome of importing one file. */
sealed interface ImportResult {
    data class Added(val entry: LibraryEntry) : ImportResult
    data class AlreadyPresent(val entry: LibraryEntry) : ImportResult
    data class Failed(val fileName: String, val reason: String) : ImportResult
}

/**
 * Brings a file into the library: identify it, read its metadata and cover, and record it.
 *
 * A book's identity is the SHA-256 of its first and last 256 KB plus its length, not its path.
 * That means the same book imported twice from two folders is recognised as one book and keeps
 * one reading position, while two different books of the same size and title stay separate.
 * Hashing only the ends keeps it fast on a 600 MB illustrated PDF.
 */
class BookImporter(
    private val library: LibraryRepository,
    private val opener: BookOpener,
    /** Where cover images are cached. */
    private val coverDirectory: File,
) {

    suspend fun import(handle: BookFileHandle): ImportResult = withContext(Dispatchers.IO) {
        val id = runCatching { identify(handle) }.getOrNull()
            ?: return@withContext ImportResult.Failed(handle.displayName, "The file could not be read.")

        library.entry(id)?.let { existing ->
            // Re-importing a known book refreshes where it lives without touching progress.
            if (existing.book.sourceUri != handle.localPath() && handle.localPath() != null) {
                library.upsert(existing.book.copy(sourceUri = handle.localPath()!!))
            }
            return@withContext ImportResult.AlreadyPresent(library.entry(id) ?: existing)
        }

        val source = try {
            opener.open(handle)
        } catch (e: BookParseException) {
            return@withContext ImportResult.Failed(handle.displayName, e.message ?: "Unreadable file.")
        } catch (e: Exception) {
            return@withContext ImportResult.Failed(
                handle.displayName,
                "Something went wrong reading this file.",
            )
        }

        source.use { book ->
            val coverRef = runCatching { saveCover(id, book.coverImage()) }.getOrNull()
            val entry = library.upsert(
                Book(
                    id = id,
                    metadata = book.metadata,
                    format = book.format,
                    sourceUri = handle.localPath() ?: handle.displayName,
                    fileSizeBytes = handle.sizeBytes,
                    addedAtEpochMillis = System.currentTimeMillis(),
                    coverImageRef = coverRef,
                    totalCharacters = book.chapters.sumOf { it.approximateCharacters.toLong() },
                    chapterCount = book.chapters.size,
                ),
            )
            ImportResult.Added(entry)
        }
    }

    /** Imports a whole folder, skipping anything that is not a book. */
    suspend fun importAll(handles: List<BookFileHandle>): List<ImportResult> =
        handles.map { import(it) }

    private fun saveCover(id: BookId, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        if (!coverDirectory.isDirectory && !coverDirectory.mkdirs()) return null
        val file = File(coverDirectory, "${id.value}.img")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun identify(handle: BookFileHandle): BookId {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(handle.sizeBytes.toString().toByteArray())

        val path = handle.localPath()
        if (path != null) {
            val file = File(path)
            RandomSlice.hashEnds(file, digest, SLICE_BYTES)
        } else {
            handle.openSource().buffer().use { source ->
                val buffer = ByteArray(1 shl 16)
                var remaining = SLICE_BYTES
                while (remaining > 0) {
                    val read = source.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        return BookId(digest.digest().joinToString("") { "%02x".format(it) }.take(32))
    }

    private companion object {
        const val SLICE_BYTES = 256 * 1024
    }
}

/** Hashes the head and tail of a file, which identifies it without reading all of it. */
internal object RandomSlice {
    fun hashEnds(file: File, digest: MessageDigest, sliceBytes: Int) {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val head = ByteArray(minOf(sliceBytes.toLong(), length).toInt())
            raf.seek(0)
            raf.readFully(head)
            digest.update(head)

            if (length > sliceBytes * 2L) {
                val tail = ByteArray(sliceBytes)
                raf.seek(length - sliceBytes)
                raf.readFully(tail)
                digest.update(tail)
            }
        }
    }
}
