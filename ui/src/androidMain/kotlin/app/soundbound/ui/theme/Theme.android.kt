package app.soundbound.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.soundbound.core.prefs.ReadingFont
import java.io.File

/**
 * Material You, where the device offers it. A user who has set a wallpaper-derived palette
 * expects their apps to follow it, and Soundbound's own amber is kept for the voice controls
 * either way.
 */
@Composable
actual fun platformDynamicColourScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
actual fun platformIsDarkSystemTheme(): Boolean = isSystemInDarkTheme()

/**
 * Reading faces.
 *
 * Soundbound does not ship licensed fonts. The three platform families are always available,
 * and a user who wants Literata, Atkinson Hyperlegible or OpenDyslexic can drop the `.ttf`
 * into the app's `fonts` folder, where this looks for it. Anything missing falls back to the
 * closest platform family rather than failing.
 */
@Composable
actual fun platformReadingFont(font: ReadingFont): FontFamily {
    val context = LocalContext.current
    val fileName = when (font) {
        ReadingFont.LITERATA -> "Literata-Regular.ttf"
        ReadingFont.BOOKERLY -> "Bookerly-Regular.ttf"
        ReadingFont.ATKINSON -> "AtkinsonHyperlegible-Regular.ttf"
        ReadingFont.OPEN_DYSLEXIC -> "OpenDyslexic-Regular.otf"
        ReadingFont.SYSTEM_SERIF -> null
        ReadingFont.SYSTEM_SANS -> null
    }
    if (fileName != null) {
        val file = File(File(context.filesDir, "fonts"), fileName)
        if (file.isFile) {
            runCatching { return FontFamily(Font(file)) }
        }
    }
    return if (font.isSerif) FontFamily.Serif else FontFamily.SansSerif
}
