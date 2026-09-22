package app.soundbound.core.audiobook

/**
 * What a tag reader can find inside an audio file.
 *
 * Every field is optional because real audiobooks are tagged carelessly: ripped CDs with the
 * narrator in the artist field, single files with no title at all, whole series where the only
 * usable information is the file name. Nothing here is trusted to exist.
 */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val narrator: String? = null,
    val year: String? = null,
    val comment: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    /** Duration where the container states it. Null when it has to be measured instead. */
    val durationMillis: Long? = null,
    /** Chapters listed inside the file. Empty for most MP3s, usual for an M4B. */
    val chapters: List<EmbeddedChapter> = emptyList(),
    val coverBytes: ByteArray? = null,
) {
    /** See the note on [Audiobook.equals]: the artwork is excluded from equality. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTags) return false
        return title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && narrator == other.narrator && year == other.year &&
            comment == other.comment && trackNumber == other.trackNumber &&
            discNumber == other.discNumber && durationMillis == other.durationMillis &&
            chapters == other.chapters
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (trackNumber ?: 0)
        result = 31 * result + (durationMillis?.hashCode() ?: 0)
        result = 31 * result + chapters.hashCode()
        return result
    }
}

/** A chapter as the file itself declares it, in milliseconds from the start of that file. */
data class EmbeddedChapter(
    val title: String,
    val startMillis: Long,
    val endMillis: Long?,
)

/**
 * Reads tags out of one audio file.
 *
 * Implemented in :core rather than handed to the platform on purpose. Android's metadata reader
 * cannot return a chapter list at all, and an M4B with thirty chapters inside one file is the
 * single most common shape an audiobook comes in — losing those chapters would leave a nine-hour
 * book with no way to move about in it. Doing it here also means the desktop and the phone read
 * the same file identically.
 */
interface AudioTagReader {
    /** True when this reader recognises the file from its name and first bytes. */
    fun canRead(fileName: String, header: ByteArray): Boolean

    /**
     * Reads what it can. Never throws for a malformed file: a book that imports with a wrong
     * title is recoverable, one that refuses to import is not.
     */
    fun read(bytes: ByteArray): AudioTags
}

/** Formats Soundbound recognises as audiobook audio. */
object AudioFormats {
    val EXTENSIONS = listOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma")

    fun isAudio(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()
}
