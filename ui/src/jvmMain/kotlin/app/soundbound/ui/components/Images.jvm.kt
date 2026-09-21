package app.soundbound.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

/**
 * Skia decodes at full size, so the desktop simply decodes once and lets the renderer scale.
 * Covers are a few hundred kilobytes and desktop memory is not the constraint it is on a phone.
 */
actual fun decodeImageBytes(bytes: ByteArray, targetWidthPixels: Int): ImageBitmap? =
    decodeImageBytes(bytes)
