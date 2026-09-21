package app.soundbound.desktop.platform

import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.session.LocalFileHandle
import app.soundbound.ui.app.PlatformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The desktop half of [PlatformBridge].
 *
 * Files are opened where they are rather than copied into app storage: on a desktop the user
 * manages their own folders, and silently duplicating a 600 MB scan into a hidden directory would
 * be rude. The library remembers the path, and a missing file is reported plainly when the book is
 * next opened.
 */
class DesktopPlatformBridge(
    private val exportsDirectory: File,
    override val appVersion: String,
    private val onMessage: (String) -> Unit,
) : PlatformBridge {

    private var lastBookDirectory: File? = null

    override suspend fun pickBooks(): List<BookFileHandle> = withContext(Dispatchers.Main) {
        val chooser = JFileChooser(lastBookDirectory ?: defaultDocumentsDirectory()).apply {
            dialogTitle = "Add books"
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            // A permissive filter first, then specific ones: plenty of books arrive with an
            // unhelpful extension, and a picker that hides them is infuriating.
            addChoosableFileFilter(
                FileNameExtensionFilter("Books (EPUB, PDF, text)", "epub", "pdf", "txt", "text", "md", "markdown"),
            )
            addChoosableFileFilter(FileNameExtensionFilter("EPUB", "epub"))
            addChoosableFileFilter(FileNameExtensionFilter("PDF", "pdf"))
            isAcceptAllFileFilterUsed = true
            fileFilter = choosableFileFilters.first()
        }

        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext emptyList()
        chooser.selectedFiles
            .also { files -> files.firstOrNull()?.parentFile?.let { lastBookDirectory = it } }
            .filter { it.isFile }
            .map { LocalFileHandle(it) }
    }

    override suspend fun pickFolder(title: String): File? = withContext(Dispatchers.Main) {
        val chooser = JFileChooser(exportsDirectory).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null
        chooser.selectedFile?.takeIf { it.isDirectory }
    }

    override suspend fun pickVoiceFiles(): List<File> = withContext(Dispatchers.Main) {
        val chooser = JFileChooser(defaultDocumentsDirectory()).apply {
            dialogTitle = "Add a voice (select both the .onnx and .onnx.json files)"
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = FileNameExtensionFilter("Piper voice files", "onnx", "json")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext emptyList()
        chooser.selectedFiles.filter { it.isFile }
    }

    override suspend fun pickTextFile(): String? = withContext(Dispatchers.Main) {
        val chooser = JFileChooser(defaultDocumentsDirectory()).apply {
            dialogTitle = "Import settings"
            fileFilter = FileNameExtensionFilter("Soundbound settings", "json")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null
        val file = chooser.selectedFile ?: return@withContext null
        withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
    }

    override suspend fun saveTextFile(suggestedName: String, content: String): Boolean =
        withContext(Dispatchers.Main) {
            val chooser = JFileChooser(defaultDocumentsDirectory()).apply {
                dialogTitle = "Export settings"
                selectedFile = File(suggestedName)
            }
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext false
            val file = chooser.selectedFile ?: return@withContext false
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(content) }.isSuccess
            }
        }

    override fun defaultExportDirectory(): File =
        File(exportsDirectory, "Audio").also { it.mkdirs() }

    /** On the desktop, "sharing" means showing the files in the file manager. */
    override fun share(files: List<File>) {
        files.firstOrNull()?.let(::revealInFileManager)
    }

    override fun revealInFileManager(file: File) {
        val target = if (file.isDirectory) file else file.parentFile ?: return
        val opened = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(target)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (!opened) {
            // Desktop.open is unreliable on some Linux desktops; fall back to the usual openers.
            val command = when {
                isMac -> listOf("open", target.absolutePath)
                isWindows -> listOf("explorer", target.absolutePath)
                else -> listOf("xdg-open", target.absolutePath)
            }
            val ran = runCatching { ProcessBuilder(command).start(); true }.getOrDefault(false)
            if (!ran) onMessage(target.absolutePath)
        }
    }

    /**
     * There is no equivalent on the desktop: the operating system's own display sleep settings are
     * the user's business, and an application that overrode them would be presumptuous.
     */
    override fun setKeepScreenOn(enabled: Boolean) = Unit

    override fun showMessage(message: String) = onMessage(message)

    override fun isOnline(): Boolean = runCatching {
        java.net.InetAddress.getByName("huggingface.co").isReachable(1_500)
    }.getOrDefault(true)

    private fun defaultDocumentsDirectory(): File {
        val home = File(System.getProperty("user.home"))
        return listOf("Documents", "Books", "Downloads")
            .map { File(home, it) }
            .firstOrNull { it.isDirectory }
            ?: home
    }

    private val isMac: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    private val isWindows: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
