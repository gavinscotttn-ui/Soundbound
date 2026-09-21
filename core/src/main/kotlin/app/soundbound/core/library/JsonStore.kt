package app.soundbound.core.library

import kotlinx.serialization.json.Json
import okio.buffer
import okio.sink
import okio.source
import java.io.File

/**
 * Reads and writes a small JSON document atomically.
 *
 * Every write goes to a sibling temporary file and is then renamed over the original, so a
 * process killed mid-save — which on Android is entirely routine — leaves the previous version
 * intact rather than a truncated one. A `.bak` copy of the last good version is kept as a
 * second line of defence.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal class JsonStore<T>(
    private val file: File,
    private val serializer: kotlinx.serialization.KSerializer<T>,
    private val default: () -> T,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    @Synchronized
    fun load(): T {
        readFrom(file)?.let { return it }
        // The main file is missing or corrupt; try the backup before giving up.
        readFrom(backupFile())?.let { recovered ->
            runCatching { write(recovered) }
            return recovered
        }
        return default()
    }

    private fun readFrom(candidate: File): T? {
        if (!candidate.isFile || candidate.length() == 0L) return null
        return runCatching {
            candidate.source().buffer().use { json.decodeFromString(serializer, it.readUtf8()) }
        }.getOrNull()
    }

    @Synchronized
    fun write(value: T) {
        file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val text = json.encodeToString(serializer, value)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.sink().buffer().use { it.writeUtf8(text) }

        if (file.isFile) {
            val backup = backupFile()
            runCatching {
                backup.delete()
                file.copyTo(backup, overwrite = true)
            }
        }
        if (!temporary.renameTo(file)) {
            // Some filesystems refuse a rename over an existing file.
            file.delete()
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun backupFile() = File(file.parentFile, file.name + ".bak")
}
