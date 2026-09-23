package app.soundbound.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Touch feedback.
 *
 * The difference between an interface that feels expensive and one that feels merely tidy is
 * usually not visual at all — it is whether the phone answers when you press something. A tick
 * under the thumb when a chapter changes tells you it worked without your having to look, which
 * matters most in exactly the situation this app is for: a book playing in a pocket.
 *
 * Used sparingly and on purpose. Feedback on everything is worse than feedback on nothing: it
 * stops meaning anything, and it is the first thing people turn off.
 */
@Immutable
class SoundboundHaptics(
    private val feedback: HapticFeedback?,
    private val enabled: Boolean,
) {
    /**
     * A light tick, for something that moved: a chapter, a page, play or pause.
     *
     * TextHandleMove is the lightest feedback every Android version offers, and is the one the
     * system itself uses for a value stepping past a notch.
     */
    fun tick() {
        if (enabled) feedback?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** A firmer press, for something that was created or settled: a bookmark, a sleep timer. */
    fun confirm() {
        if (enabled) feedback?.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/** No feedback at all, which is what a preview and the desktop get. */
val LocalHaptics = staticCompositionLocalOf { SoundboundHaptics(null, enabled = false) }

/** Builds the haptics for the current setting. */
@Composable
fun rememberHaptics(enabled: Boolean): SoundboundHaptics {
    val feedback = LocalHapticFeedback.current
    return SoundboundHaptics(feedback, enabled)
}
