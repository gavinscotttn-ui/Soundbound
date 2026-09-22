package app.soundbound.android.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.session.LocalFileHandle
import app.soundbound.ui.app.PlatformBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android half of [PlatformBridge].
 *
 * Files chosen through the Storage Access Framework are copied into the app's own storage rather
 * than read through their content URI. That costs a copy once, and buys correctness everywhere
 * else: an EPUB and a PDF both need random access, a URI permission can be revoked between
 * sessions, and the file behind one can be moved or deleted while a book is open.
 */
class AndroidPlatformBridge(
    private val activity: ComponentActivity,
    private val booksDirectory: File,
    private val exportsDirectory: File,
    override val appVersion: String,
) : PlatformBridge {

    private var pendingBooks: CompletableDeferred<List<Uri>>? = null
    private var pendingVoices: CompletableDeferred<List<Uri>>? = null
    private var pendingTree: CompletableDeferred<Uri?>? = null
    private var pendingText: CompletableDeferred<Uri?>? = null
    private var pendingSave: CompletableDeferred<Uri?>? = null

    // Launchers have to be registered before the activity starts, which is why they are created
    // here rather than on demand.
    private val openBooks: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pendingBooks?.complete(uris.orEmpty())
            pendingBooks = null
        }

    private val openVoices: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pendingVoices?.complete(uris.orEmpty())
            pendingVoices = null
        }

    private val openTree: ActivityResultLauncher<Uri?> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            pendingTree?.complete(uri)
            pendingTree = null
        }

    private val openText: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingText?.complete(uri)
            pendingText = null
        }

    private val createText: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            pendingSave?.complete(uri)
            pendingSave = null
        }

    override suspend fun pickBooks(): List<BookFileHandle> {
        val deferred = CompletableDeferred<List<Uri>>()
        pendingBooks = deferred
        val launched = runCatching { openBooks.launch(BOOK_MIME_TYPES) }.isSuccess
        if (!launched) {
            pendingBooks = null
            showMessage("No file picker is available on this device.")
            return emptyList()
        }
        val uris = deferred.await()
        return withContext(Dispatchers.IO) { uris.mapNotNull(::copyIntoLibrary) }
    }

    /** Copies a chosen document into app storage and returns a handle to the copy. */
    fun copyIntoLibrary(uri: Uri): BookFileHandle? = runCatching {
        if (!booksDirectory.isDirectory) booksDirectory.mkdirs()
        val name = displayNameOf(uri) ?: "book-${System.currentTimeMillis()}"
        val target = uniqueFile(booksDirectory, name)
        activity.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        } ?: return null
        if (target.length() == 0L) {
            target.delete()
            return null
        }
        LocalFileHandle(target)
    }.getOrNull()

    private fun uniqueFile(directory: File, name: String): File {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "book" }
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val stem = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "")
        var index = 2
        while (candidate.exists() && index < 1_000) {
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            candidate = File(directory, "$stem ($index)$suffix")
            index++
        }
        return candidate
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }.getOrNull()

    /**
     * Returns a folder under the app's own storage.
     *
     * A document tree URI cannot be handed to `java.io.File`, and the exporter writes through a
     * plain file for speed. So the picked folder is remembered only for the *share* step: the
     * files are written to app storage and then offered to the system, which is also what avoids
     * needing broad storage permissions.
     */
    override suspend fun pickFolder(title: String): File? {
        val deferred = CompletableDeferred<Uri?>()
        pendingTree = deferred
        val launched = runCatching { openTree.launch(null) }.isSuccess
        if (!launched) {
            pendingTree = null
            return defaultExportDirectory()
        }
        val uri = deferred.await() ?: return null
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        // Named after the chosen folder so the destination shown in the sheet is recognisable.
        val label = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
        return File(exportsDirectory, label?.ifBlank { null } ?: "Audio").also { it.mkdirs() }
    }

    override suspend fun pickVoiceFiles(): List<File> {
        val deferred = CompletableDeferred<List<Uri>>()
        pendingVoices = deferred
        val launched = runCatching { openVoices.launch(arrayOf("*/*")) }.isSuccess
        if (!launched) {
            pendingVoices = null
            return emptyList()
        }
        val uris = deferred.await()
        return withContext(Dispatchers.IO) {
            val staging = File(activity.cacheDir, "voice-staging").apply { mkdirs() }
            uris.mapNotNull { uri ->
                runCatching {
                    val name = displayNameOf(uri) ?: return@runCatching null
                    val target = File(staging, name)
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                    } ?: return@runCatching null
                    target
                }.getOrNull()
            }
        }
    }

    override suspend fun pickTextFile(): String? {
        val deferred = CompletableDeferred<Uri?>()
        pendingText = deferred
        val launched = runCatching { openText.launch(arrayOf("application/json", "text/plain")) }.isSuccess
        if (!launched) {
            pendingText = null
            return null
        }
        val uri = deferred.await() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }
    }

    override suspend fun saveTextFile(suggestedName: String, content: String): Boolean {
        val deferred = CompletableDeferred<Uri?>()
        pendingSave = deferred
        val launched = runCatching { createText.launch(suggestedName) }.isSuccess
        if (!launched) {
            pendingSave = null
            return false
        }
        val uri = deferred.await() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                activity.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                true
            }.getOrDefault(false)
        }
    }

    override fun defaultExportDirectory(): File =
        File(exportsDirectory, "Audio").also { it.mkdirs() }

    override fun share(files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.mapNotNull { file ->
            runCatching {
                FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            }.getOrNull()
        }
        if (uris.isEmpty()) return

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(Intent.createChooser(intent, "Share audio")) }
            .onFailure { showMessage("No app is available to receive these files.") }
    }

    override fun revealInFileManager(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showMessage(file.absolutePath)
        }
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        activity.runOnUiThread {
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun showMessage(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun isOnline(): Boolean = runCatching {
        val manager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private companion object {
        /**
         * The catch-all wildcard type at the end of this list is there on purpose: plenty of
         * file managers report an EPUB as `application/octet-stream`, and a picker that refuses
         * to show the user's own books is worse than one that shows a few files it cannot open.
         *
         * (It is spelled out in the list below rather than here, because the wildcard's own
         * characters would close this comment.)
         */
        val BOOK_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "application/pdf",
            "text/plain",
            "text/markdown",
            "application/octet-stream",
            "*/*",
        )
    }
}
