package app.soundbound.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import app.soundbound.core.prefs.AppTheme
import app.soundbound.core.prefs.ReaderTheme
import app.soundbound.core.prefs.ReadingFont
import app.soundbound.core.prefs.Settings

/**
 * Platform hooks.
 *
 * Android can colour the interface from the wallpaper on API 31 and above, which is worth
 * honouring; the desktop has no equivalent and returns null. Reading faces are likewise
 * resolved per platform.
 */
@Composable
expect fun platformDynamicColourScheme(dark: Boolean): ColorScheme?

@Composable
expect fun platformReadingFont(font: ReadingFont): FontFamily

@Composable
expect fun platformIsDarkSystemTheme(): Boolean

/** Whether the app is currently drawn dark, for code that needs to know outside a ColorScheme. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** The reading surface's palette. Available anywhere, so a sheet can match the page behind it. */
val LocalReaderPalette = staticCompositionLocalOf { ReaderPalette.Paper }

/** The user's settings, so leaf components can read typography without a parameter chain. */
val LocalSettings = staticCompositionLocalOf { Settings() }

/** Extra colours Material's scheme has no slot for. */
@Immutable
data class SoundboundAccents(
    val speaking: androidx.compose.ui.graphics.Color,
    val speakingContainer: androidx.compose.ui.graphics.Color,
    val progressTrack: androidx.compose.ui.graphics.Color,
    val coverPlaceholder: androidx.compose.ui.graphics.Color,
    val success: androidx.compose.ui.graphics.Color,
)

val LocalAccents = staticCompositionLocalOf {
    SoundboundAccents(
        speaking = SoundboundColours.Magenta600,
        speakingContainer = SoundboundColours.Magenta100,
        progressTrack = SoundboundColours.Paper300,
        coverPlaceholder = SoundboundColours.Ink100,
        success = SoundboundColours.Success,
    )
}

@Composable
fun SoundboundTheme(
    settings: Settings = Settings(),
    /** Overrides the settings, for previews and for the onboarding flow. */
    forceDark: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = platformIsDarkSystemTheme()
    val dark = forceDark ?: when (settings.theme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK, AppTheme.BLACK -> true
        AppTheme.FOLLOW_SYSTEM -> systemDark
    }
    val trueBlack = settings.theme == AppTheme.BLACK

    val dynamic = if (settings.useDynamicColour) platformDynamicColourScheme(dark) else null
    val scheme = dynamic ?: remember(dark, trueBlack) {
        when {
            trueBlack -> SoundboundColours.Black
            dark -> SoundboundColours.Dark
            else -> SoundboundColours.Light
        }
    }

    val readerPalette = remember(settings.reader.readerTheme, dark, trueBlack) {
        readerPaletteFor(settings.reader.readerTheme, dark, trueBlack)
    }

    val accents = remember(scheme, dark) {
        SoundboundAccents(
            speaking = if (dark) SoundboundColours.Magenta300 else SoundboundColours.Magenta600,
            speakingContainer =
                if (dark) SoundboundColours.Magenta800 else SoundboundColours.Magenta100,
            progressTrack = scheme.surfaceVariant,
            coverPlaceholder = if (dark) SoundboundColours.Ink700 else SoundboundColours.Ink100,
            success = SoundboundColours.Success,
        )
    }

    CompositionLocalProvider(
        LocalIsDarkTheme provides dark,
        LocalReaderPalette provides readerPalette,
        LocalSettings provides settings,
        LocalAccents provides accents,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SoundboundType.Typography,
            shapes = SoundboundShapes.Shapes,
            content = content,
        )
    }
}

/** Resolves a [ReaderTheme] to a concrete palette, following the app where asked. */
fun readerPaletteFor(theme: ReaderTheme, appIsDark: Boolean, trueBlack: Boolean): ReaderPalette =
    when (theme) {
        ReaderTheme.PAPER -> ReaderPalette.Paper
        ReaderTheme.CREAM -> ReaderPalette.Cream
        ReaderTheme.SEPIA -> ReaderPalette.Sepia
        ReaderTheme.GREY -> ReaderPalette.Grey
        ReaderTheme.NIGHT -> ReaderPalette.Night
        ReaderTheme.MIDNIGHT -> ReaderPalette.Midnight
        ReaderTheme.CONTRAST -> ReaderPalette.Contrast
        ReaderTheme.FOLLOW_APP -> when {
            appIsDark && trueBlack -> ReaderPalette.Midnight
            appIsDark -> ReaderPalette.Night
            else -> ReaderPalette.Paper
        }
    }

/** Every reading palette with its label, for the theme picker. */
val readerThemeOptions: List<Pair<ReaderTheme, ReaderPalette>> = listOf(
    ReaderTheme.PAPER to ReaderPalette.Paper,
    ReaderTheme.CREAM to ReaderPalette.Cream,
    ReaderTheme.SEPIA to ReaderPalette.Sepia,
    ReaderTheme.GREY to ReaderPalette.Grey,
    ReaderTheme.NIGHT to ReaderPalette.Night,
    ReaderTheme.MIDNIGHT to ReaderPalette.Midnight,
    ReaderTheme.CONTRAST to ReaderPalette.Contrast,
)
