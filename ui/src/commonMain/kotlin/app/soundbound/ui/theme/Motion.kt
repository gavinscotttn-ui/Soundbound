package app.soundbound.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion.
 *
 * Two rules, which between them account for how the app feels. Anything the user is dragging
 * follows a spring, because a spring can be interrupted and redirected without a visible jump.
 * Anything the app decides on its own uses a short, decelerating tween, because predictability
 * matters more than character for something you did not ask for.
 */
object Motion {

    /** Fast out, slow in. Things arriving. */
    val enterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Slow out, fast in. Things leaving. */
    val exitEasing: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    val standardEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    const val DURATION_INSTANT = 90
    const val DURATION_QUICK = 180
    const val DURATION_STANDARD = 280
    const val DURATION_SLOW = 420
    const val DURATION_PAGE = 340

    fun <T> quick(): FiniteAnimationSpec<T> = tween(DURATION_QUICK, easing = standardEasing)

    fun <T> standard(): FiniteAnimationSpec<T> = tween(DURATION_STANDARD, easing = enterEasing)

    fun <T> slow(): FiniteAnimationSpec<T> = tween(DURATION_SLOW, easing = enterEasing)

    /** For anything that follows a finger. */
    fun <T> responsive(): AnimationSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** A little overshoot, for a control that has just been pressed. */
    fun <T> bouncy(): AnimationSpec<T> = spring(
        dampingRatio = 0.55f,
        stiffness = Spring.StiffnessMedium,
    )

    /** Barely-there movement, for a value that updates continuously such as a progress bar. */
    fun <T> continuous(): AnimationSpec<T> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessLow,
    )
}
