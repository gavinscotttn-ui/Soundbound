package app.soundbound.ui.app

import app.soundbound.core.book.BookFileHandle
import java.io.File

/**
 * The few things the interface cannot do for itself.
 *
 * Everything here is a place where Android and the desktop genuinely differ — a file picker is a
 * Storage Access Framework intent on one and a Swing chooser on the other — rather than a
 * convenience. Keeping the list this short is what lets both apps share one interface.
 */
interface PlatformBridge {

    /** Opens the system file picker and copies whatever is chosen into app storage. */
    suspend fun pickBooks(): List<BookFileHandle>

    /**
     * Picks audio files for one audiobook.
     *
     * Separate from [pickBooks] because the picker has to allow a different set of types, and
     * because everything chosen in one go becomes a single book rather than one each.
     */
    suspend fun pickAudiobookFiles(): List<BookFileHandle> = emptyList()

    /** Opens a folder picker, for choosing where exports go. */
    suspend fun pickFolder(title: String): File?

    /** Picks `.onnx` and `.onnx.json` files, for installing a voice by hand. */
    suspend fun pickVoiceFiles(): List<File>

    /** Reads a text file the user chose, for importing settings. */
    suspend fun pickTextFile(): String?

    /** Writes text to a file the user chooses, for exporting settings. */
    suspend fun saveTextFile(suggestedName: String, content: String): Boolean

    /** Where exports land unless the user says otherwise. */
    fun defaultExportDirectory(): File

    /** Hands finished files to the system share sheet, where there is one. */
    fun share(files: List<File>)

    /** Opens the containing folder in the system file manager, where there is one. */
    fun revealInFileManager(file: File)

    /** Stops the screen dimming while reading. */
    fun setKeepScreenOn(enabled: Boolean)

    /** Shows a brief message. A snackbar on Android, a status line on the desktop. */
    fun showMessage(message: String)

    val appVersion: String

    /** True when the device is offline, so the voice store can say so rather than hanging. */
    fun isOnline(): Boolean = true
}

/** A bridge that does nothing, for previews and tests. */
object NoOpPlatformBridge : PlatformBridge {
    override suspend fun pickBooks(): List<BookFileHandle> = emptyList()
    override suspend fun pickAudiobookFiles(): List<BookFileHandle> = emptyList()
    override suspend fun pickFolder(title: String): File? = null
    override suspend fun pickVoiceFiles(): List<File> = emptyList()
    override suspend fun pickTextFile(): String? = null
    override suspend fun saveTextFile(suggestedName: String, content: String): Boolean = false
    override fun defaultExportDirectory(): File = File(System.getProperty("java.io.tmpdir"), "soundbound")
    override fun share(files: List<File>) = Unit
    override fun revealInFileManager(file: File) = Unit
    override fun setKeepScreenOn(enabled: Boolean) = Unit
    override fun showMessage(message: String) = Unit
    override val appVersion: String = "dev"
}
