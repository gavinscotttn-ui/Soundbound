package app.soundbound.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes an encoded image to an [ImageBitmap].
 *
 * Written as an expect/actual rather than using a shared decoder, because the platform decoders
 * are the ones that handle awkward real-world covers — progressive JPEGs, CMYK, the odd WebP —
 * and they are hardware-accelerated where that matters.
 */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

/** Downsamples while decoding where the platform can, so a 4000 px cover does not cost 60 MB. */
expect fun decodeImageBytes(bytes: ByteArray, targetWidthPixels: Int): ImageBitmap?

/**
 * Loads a cover from disk off the main thread, caching the result.
 *
 * The cache is bounded and keyed by path and size: a library of five hundred covers scrolling at
 * 120 Hz on a phone is exactly the situation where an unbounded cache gets the app killed.
 */
@Composable
fun rememberCoverImage(path: String?, targetWidthPixels: Int = 480): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = CoverCache.peek(path, targetWidthPixels), path, targetWidthPixels) {
        if (path.isNullOrBlank()) {
            value = null
            return@produceState
        }
        CoverCache.peek(path, targetWidthPixels)?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.isFile) return@withContext null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
            decodeImageBytes(bytes, targetWidthPixels)?.also { CoverCache.put(path, targetWidthPixels, it) }
        }
    }

/** A small least-recently-used cache of decoded covers. */
object CoverCache {
    private const val MAX_ENTRIES = 96
    private val entries = LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun peek(path: String?, width: Int): ImageBitmap? =
        if (path.isNullOrBlank()) null else entries[key(path, width)]

    @Synchronized
    fun put(path: String, width: Int, image: ImageBitmap) {
        entries[key(path, width)] = image
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun key(path: String, width: Int) = "$path@$width"
}
