package app.soundbound.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Soundbound's palette.
 *
 * The interface is built around ink on warm paper, with a single magenta accent — the brand
 * colour, taken from the app's own mark — for anything to do with the voice. The point is that
 * the app should feel like a reading surface, not a media player that happens to contain text,
 * so the chrome stays quiet and the page gets the contrast.
 *
 * The page itself is never tinted. A magenta interface around a warm white page is the right way
 * round; a magenta page would be unreadable for six hours at a stretch.
 */
object SoundboundColours {

    // Ink: the near-black used for text and the dark interface. Slightly blue rather than
    // pure black, which reads as "printed" instead of "switched off".
    val Ink900 = Color(0xFF12100E)
    val Ink800 = Color(0xFF1B1815)
    val Ink700 = Color(0xFF26221E)
    val Ink600 = Color(0xFF343029)
    val Ink500 = Color(0xFF4A443C)
    val Ink400 = Color(0xFF6B6459)
    val Ink300 = Color(0xFF938B7E)
    val Ink200 = Color(0xFFBCB4A6)
    val Ink100 = Color(0xFFDCD6CB)

    // Paper: the warm off-whites of the reading surface.
    val Paper50 = Color(0xFFFDFBF7)
    val Paper100 = Color(0xFFF7F3EB)
    val Paper200 = Color(0xFFEFE9DD)
    val Paper300 = Color(0xFFE4DCCB)

    // Magenta: the brand, and the voice. Playback, the active sentence, anything speaking.
    // Sampled from the app's mark: the pale tint of the headphones through to the deep magenta
    // at the bottom of the tile.
    val Magenta50 = Color(0xFFFFF0F7)
    val Magenta100 = Color(0xFFFFD9EC)
    val Magenta200 = Color(0xFFFDA6CD)
    val Magenta300 = Color(0xFFFD8ABE)
    val Magenta400 = Color(0xFFF74E97)
    val Magenta500 = Color(0xFFEE1179)
    val Magenta600 = Color(0xFFD6006E)
    val Magenta700 = Color(0xFFAE0160)
    val Magenta800 = Color(0xFF7D0044)

    // Amber is kept for the reading-warmth filter and the sepia page, which want a warm light
    // rather than the brand colour.
    val Amber300 = Color(0xFFFFD08A)
    val Amber500 = Color(0xFFE89B2C)

    // Teal: the quiet secondary, used for selection and progress.
    val Teal200 = Color(0xFF9CD3CC)
    val Teal400 = Color(0xFF4FA89E)
    val Teal600 = Color(0xFF2E7A72)
    val Teal800 = Color(0xFF1C4F4A)

    // Highlight colours offered for annotations.
    val HighlightYellow = Color(0xFFFFE08A)
    val HighlightGreen = Color(0xFFB7E7B0)
    val HighlightBlue = Color(0xFFA8D4F2)
    val HighlightPink = Color(0xFFF7B8CE)
    val HighlightPurple = Color(0xFFD3BCF0)

    val Danger = Color(0xFFBA1A1A)
    val DangerContainer = Color(0xFFFFDAD6)
    val Success = Color(0xFF2E7D32)

    val Light: ColorScheme = lightColorScheme(
        primary = Magenta600,
        onPrimary = Color.White,
        primaryContainer = Magenta100,
        onPrimaryContainer = Magenta800,
        secondary = Teal600,
        onSecondary = Color.White,
        secondaryContainer = Teal200,
        onSecondaryContainer = Color(0xFF00201C),
        tertiary = Ink500,
        onTertiary = Color.White,
        tertiaryContainer = Ink100,
        onTertiaryContainer = Ink900,
        background = Paper50,
        onBackground = Ink900,
        surface = Paper50,
        onSurface = Ink900,
        surfaceVariant = Paper200,
        onSurfaceVariant = Ink500,
        surfaceTint = Magenta600,
        inverseSurface = Ink800,
        inverseOnSurface = Paper100,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerContainer,
        onErrorContainer = Color(0xFF410002),
        outline = Ink300,
        outlineVariant = Paper300,
        scrim = Color(0x99000000),
    )

    val Dark: ColorScheme = darkColorScheme(
        primary = Magenta300,
        onPrimary = Magenta800,
        primaryContainer = Magenta700,
        onPrimaryContainer = Magenta100,
        secondary = Teal200,
        onSecondary = Color(0xFF00352F),
        secondaryContainer = Teal800,
        onSecondaryContainer = Teal200,
        tertiary = Ink200,
        onTertiary = Ink900,
        tertiaryContainer = Ink600,
        onTertiaryContainer = Ink100,
        background = Ink900,
        onBackground = Paper100,
        surface = Ink900,
        onSurface = Paper100,
        surfaceVariant = Ink700,
        onSurfaceVariant = Ink200,
        surfaceTint = Magenta300,
        inverseSurface = Paper100,
        inverseOnSurface = Ink900,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = DangerContainer,
        outline = Ink400,
        outlineVariant = Ink600,
        scrim = Color(0xB3000000),
    )

    /** True black, for OLED screens where it saves real power at night. */
    val Black: ColorScheme = Dark.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = Color(0xFF141414),
        onSurfaceVariant = Ink200,
        outlineVariant = Color(0xFF242424),
    )
}

/**
 * The reading surface's own colours, kept apart from the interface palette.
 *
 * A reader needs to offer sepia and night modes that have nothing to do with whether the rest
 * of the app is light or dark, so the page carries its own small colour set.
 */
@Immutable
data class ReaderPalette(
    val background: Color,
    val text: Color,
    val muted: Color,
    val heading: Color,
    val link: Color,
    val selection: Color,
    /** Behind the sentence currently being spoken. */
    val speakingBackground: Color,
    /** Behind the word currently being spoken. */
    val speakingWord: Color,
    val rule: Color,
    val isDark: Boolean,
) {
    companion object {
        val Paper = ReaderPalette(
            background = SoundboundColours.Paper50,
            text = Color(0xFF1A1714),
            muted = Color(0xFF6B6459),
            heading = Color(0xFF0E0C0A),
            link = SoundboundColours.Teal600,
            selection = Color(0x2EEE1179),
            speakingBackground = Color(0x1AEE1179),
            speakingWord = Color(0x47EE1179),
            rule = SoundboundColours.Paper300,
            isDark = false,
        )

        val Cream = Paper.copy(
            background = Color(0xFFFBF3E2),
            text = Color(0xFF2A2318),
            muted = Color(0xFF7A6B52),
            heading = Color(0xFF191309),
            rule = Color(0xFFE8DCC2),
        )

        val Sepia = Paper.copy(
            background = Color(0xFFF2E4CE),
            text = Color(0xFF3A2E1C),
            muted = Color(0xFF7D6A4C),
            heading = Color(0xFF261D10),
            link = Color(0xFF8A5A1E),
            rule = Color(0xFFDCC9A6),
        )

        val Grey = Paper.copy(
            background = Color(0xFFE6E4E1),
            text = Color(0xFF23211E),
            muted = Color(0xFF605D57),
            heading = Color(0xFF14120F),
            rule = Color(0xFFCFCCC7),
        )

        val Night = ReaderPalette(
            background = Color(0xFF15161A),
            text = Color(0xFFD5D2CC),
            muted = Color(0xFF8B8781),
            heading = Color(0xFFEDEAE4),
            link = SoundboundColours.Teal200,
            selection = Color(0x40FD8ABE),
            speakingBackground = Color(0x26FD8ABE),
            speakingWord = Color(0x5CFD8ABE),
            rule = Color(0xFF2A2C31),
            isDark = true,
        )

        val Midnight = Night.copy(
            background = Color(0xFF000000),
            text = Color(0xFFBFBCB6),
            heading = Color(0xFFE2DFD9),
            rule = Color(0xFF1C1C1C),
        )

        val Contrast = ReaderPalette(
            background = Color(0xFF000000),
            text = Color(0xFFFFFFFF),
            muted = Color(0xFFCFCFCF),
            heading = Color(0xFFFFFFFF),
            link = Color(0xFF7FD4FF),
            selection = Color(0x66FFFFFF),
            speakingBackground = Color(0x38FD8ABE),
            speakingWord = Color(0x8AFD8ABE),
            rule = Color(0xFF4A4A4A),
            isDark = true,
        )
    }
}
