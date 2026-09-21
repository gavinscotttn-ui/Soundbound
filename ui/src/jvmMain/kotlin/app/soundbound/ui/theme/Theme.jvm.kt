package app.soundbound.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.soundbound.core.prefs.ReadingFont
import java.io.File

/**
 * The desktop has no wallpaper-derived palette to follow, so Soundbound's own is used
 * throughout.
 */
@Composable
actual fun platformDynamicColourScheme(dark: Boolean): ColorScheme? = null

/**
 * Whether macOS or Windows is in dark mode.
 *
 * Read once at start-up rather than observed. Both systems can report a change at runtime, but
 * doing so needs native listeners on each platform; a user who switches appearance mid-session
 * can pick the theme explicitly in Settings, which is the less surprising behaviour anyway.
 */
@Composable
actual fun platformIsDarkSystemTheme(): Boolean = DesktopAppearance.isDark

object DesktopAppearance {
    val isDark: Boolean by lazy { detectDarkMode() }

    private fun detectDarkMode(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") -> runCatching {
                val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                output.equals("Dark", ignoreCase = true)
            }.getOrDefault(false)

            os.contains("win") -> runCatching {
                val process = ProcessBuilder(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme",
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                // The value is 0 for dark, 1 for light.
                Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x0*(\d+)""")
                    .find(output)?.groupValues?.getOrNull(1) == "0"
            }.getOrDefault(false)

            else -> false
        }
    }
}

@Composable
actual fun platformReadingFont(font: ReadingFont): FontFamily {
    val fileName = when (font) {
        ReadingFont.LITERATA -> "Literata-Regular.ttf"
        ReadingFont.BOOKERLY -> "Bookerly-Regular.ttf"
        ReadingFont.ATKINSON -> "AtkinsonHyperlegible-Regular.ttf"
        ReadingFont.OPEN_DYSLEXIC -> "OpenDyslexic-Regular.otf"
        ReadingFont.SYSTEM_SERIF, ReadingFont.SYSTEM_SANS -> null
    }
    if (fileName != null) {
        val file = File(DesktopFonts.directory, fileName)
        if (file.isFile) {
            runCatching { return FontFamily(Font(file)) }
        }
    }
    return if (font.isSerif) FontFamily.Serif else FontFamily.SansSerif
}

/** Where a user may drop reading faces for the desktop build to pick up. */
object DesktopFonts {
    var directory: File = File(System.getProperty("user.home"), ".soundbound/fonts")
}
