package app.soundbound.ui.components

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = runCatching {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/**
 * Two passes: measure the image with `inJustDecodeBounds`, then decode it at the smallest
 * power-of-two scale that still covers the target width. Android's decoder only accepts powers
 * of two for `inSampleSize`, and decoding a 4000 px cover at full size to draw it 150 px wide is
 * how a library grid ends up stuttering.
 */
actual fun decodeImageBytes(bytes: ByteArray, targetWidthPixels: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return@runCatching null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidthPixels) sampleSize *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()
