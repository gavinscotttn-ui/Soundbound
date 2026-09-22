package app.soundbound.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * Generous, and consistent between a sheet, a card and a button of the same size. Book covers
 * keep a small radius on purpose — a real book has square corners, and rounding one off too
 * much makes the grid look like an app-store listing rather than a shelf.
 */
object SoundboundShapes {
    val Shapes: Shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

    /**
     * The same radii again, reachable directly. Material's [Shapes] holder is only consultable
     * from a composable via `MaterialTheme.shapes`; plenty of call sites here want a radius while
     * building a modifier outside that scope, so each one is named here too.
     */
    val extraSmall: CornerBasedShape = RoundedCornerShape(6.dp)
    val small: CornerBasedShape = RoundedCornerShape(10.dp)
    val medium: CornerBasedShape = RoundedCornerShape(16.dp)
    val large: CornerBasedShape = RoundedCornerShape(24.dp)
    val extraLarge: CornerBasedShape = RoundedCornerShape(32.dp)

    val cover: CornerBasedShape = RoundedCornerShape(4.dp)
    val sheet: CornerBasedShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill: CornerBasedShape = RoundedCornerShape(percent = 50)
    val chip: CornerBasedShape = RoundedCornerShape(10.dp)
}

/** Spacing, on a four-point grid. Named by role so that layouts read as intentions. */
@Immutable
object Spacing {
    val hairline = 1.dp
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val default = 16.dp
    val comfortable = 20.dp
    val large = 24.dp
    val section = 32.dp
    val generous = 48.dp

    /** Side gutter for the main screens. */
    val gutter = 20.dp

    /** Height reserved for the mini player so scrollable content clears it. */
    val miniPlayerHeight = 72.dp
}

/** Elevations, kept low: this is paper, not perspex. */
object Elevation {
    val flat = 0.dp
    val raised = 1.dp
    val card = 2.dp
    val floating = 6.dp
    val sheet = 8.dp
    val dialog = 12.dp
}
