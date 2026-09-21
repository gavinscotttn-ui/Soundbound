package app.soundbound.core.voices

import app.soundbound.core.tts.onnx.VoiceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Progress while a voice is being installed. */
sealed interface InstallProgress {
    data class Downloading(
        val voiceKey: String,
        val fileName: String,
        val bytesDownloaded: Long,
        val bytesTotal: Long,
        val fileIndex: Int,
        val fileCount: Int,
    ) : InstallProgress {
        val fraction: Float
            get() = if (bytesTotal <= 0) 0f else (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    data class Verifying(val voiceKey: String, val fileName: String) : InstallProgress
    data class Installed(val voiceKey: String, val directory: File) : InstallProgress
    data class Failed(val voiceKey: String, val reason: String, val cause: Throwable? = null) : InstallProgress
}

/**
 * Fetches the voice index and installs voices into the local [VoiceStore].
 *
 * This is the only part of Soundbound that touches the network at all, and only while the
 * user is actively installing a voice. Reading and listening never do — which is the whole
 * point of the app, so it is worth being strict about.
 *
 * Downloads go to a `.part` file and are renamed into place only after the MD5 the index
 * publishes has been checked. A half-written 60 MB model that loads and then produces noise
 * is a genuinely unpleasant failure to debug, so it is prevented rather than diagnosed.
 */
class VoiceInstaller(
    private val store: VoiceStore,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = VoiceCatalog.PIPER_BASE_URL,
    private val indexUrl: String = VoiceCatalog.PIPER_INDEX_URL,
) {

    /** Downloads and parses the voice index. */
    suspend fun fetchCatalogue(): List<CatalogVoice> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(indexUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("The voice list could not be fetched (HTTP ${response.code}).")
            }
            val body = response.body?.string()
                ?: throw IOException("The voice list came back empty.")
            VoiceCatalog.parse(body)
        }
    }

    /** True when every file of [voice] is already present locally. */
    fun isInstalled(voice: CatalogVoice): Boolean {
        val directory = store.directoryFor(voice.key)
        return voice.files.all { file ->
            File(directory, file.fileName).let { it.isFile && (file.sizeBytes == 0L || it.length() == file.sizeBytes) }
        }
    }

    fun installedKeys(): Set<String> = store.discover().map { it.key }.toSet()

    /** Removes an installed voice. Returns false if nothing was there to remove. */
    fun uninstall(voiceKey: String): Boolean {
        val directory = store.directoryFor(voiceKey)
        if (!directory.isDirectory) return false
        return directory.deleteRecursively()
    }

    /**
     * Installs [voice], emitting progress as it goes. Cancelling the collecting coroutine
     * cancels the download and leaves no partial files behind.
     */
    fun install(voice: CatalogVoice): Flow<InstallProgress> = flow {
        if (!voice.isComplete) {
            emit(InstallProgress.Failed(voice.key, "This voice is missing a model or a configuration file."))
            return@flow
        }

        val directory = store.directoryFor(voice.key)
        if (!directory.isDirectory && !directory.mkdirs()) {
            emit(InstallProgress.Failed(voice.key, "The voices folder could not be created."))
            return@flow
        }

        val partials = ArrayList<File>()
        try {
            voice.files.forEachIndexed { index, file ->
                currentCoroutineContextEnsureActive()
                val target = File(directory, file.fileName)
                if (target.isFile && (file.sizeBytes == 0L || target.length() == file.sizeBytes)) {
                    return@forEachIndexed
                }

                val partial = File(directory, file.fileName + ".part")
                partials.add(partial)
                download(voice, file, partial, index) { downloaded, total ->
                    emit(
                        InstallProgress.Downloading(
                            voiceKey = voice.key,
                            fileName = file.fileName,
                            bytesDownloaded = downloaded,
                            bytesTotal = total,
                            fileIndex = index,
                            fileCount = voice.files.size,
                        ),
                    )
                }

                if (file.md5 != null) {
                    emit(InstallProgress.Verifying(voice.key, file.fileName))
                    val actual = md5Of(partial)
                    if (!actual.equals(file.md5, ignoreCase = true)) {
                        partial.delete()
                        emit(
                            InstallProgress.Failed(
                                voice.key,
                                "The download of ${file.fileName} was corrupted. Please try again.",
                            ),
                        )
                        return@flow
                    }
                }

                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                partials.remove(partial)
            }
            emit(InstallProgress.Installed(voice.key, directory))
        } catch (e: CancellationException) {
            partials.forEach { it.delete() }
            throw e
        } catch (e: Exception) {
            partials.forEach { it.delete() }
            emit(InstallProgress.Failed(voice.key, e.message ?: "The voice could not be installed.", e))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun download(
        voice: CatalogVoice,
        file: CatalogFile,
        target: File,
        @Suppress("UNUSED_PARAMETER") fileIndex: Int,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val url = baseUrl.trimEnd('/') + "/" + file.path.trimStart('/')
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("${file.fileName} could not be downloaded (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("${file.fileName} came back empty.")
            val total = body.contentLength().takeIf { it > 0 } ?: file.sizeBytes

            body.source().use { source ->
                target.sink().buffer().use { sink ->
                    var downloaded = 0L
                    var lastReported = 0L
                    val buffer = okio.Buffer()
                    while (true) {
                        currentCoroutineContextEnsureActive()
                        val read = source.read(buffer, DOWNLOAD_CHUNK)
                        if (read == -1L) break
                        sink.write(buffer, read)
                        downloaded += read
                        // Reporting every chunk would swamp the UI; a megabyte at a time is
                        // smooth enough for a progress bar and costs nothing.
                        if (downloaded - lastReported >= PROGRESS_INTERVAL || downloaded == total) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress(downloaded, total.coerceAtLeast(downloaded))
                }
            }
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun currentCoroutineContextEnsureActive() {
        kotlin.coroutines.coroutineContext.ensureActive()
    }

    companion object {
        private const val DOWNLOAD_CHUNK = 64L * 1024
        private const val PROGRESS_INTERVAL = 1024L * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
