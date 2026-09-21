package app.soundbound.ui.components

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.soundbound.ui.theme.LocalAccents
import app.soundbound.ui.theme.Motion
import app.soundbound.ui.theme.SoundboundShapes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Three bars that rise and fall while something is being spoken.
 *
 * Their phases are deliberately unrelated, using three different periods, because bars moving in
 * step look like a loading spinner rather than a voice.
 */
@Composable
fun EqualiserBars(
    colour: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    animate: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "equaliser")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * kotlin.math.PI).toFloat(),
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "equaliserPhase",
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(barCount) { index ->
            val speed = 1f + index * 0.37f
            val offset = index * 1.1f
            val level = if (animate) {
                0.35f + 0.65f * ((sin(phase * speed + offset) + 1f) / 2f)
            } else {
                0.45f
            }
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(level.coerceIn(0.2f, 1f))
                    .clip(SoundboundShapes.pill)
                    .background(colour),
            )
        }
    }
}

/**
 * A ring showing how far through a book the reader is.
 *
 * Used where a bar would not fit, and where the exact figure matters less than the shape: a ring
 * three-quarters closed is read at a glance, a bar at 74% is not.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 36.dp,
    strokeWidth: Dp = 3.dp,
    colour: Color? = null,
    trackColour: Color? = null,
    content: @Composable (() -> Unit)? = null,
) {
    val accents = LocalAccents.current
    val ringColour = colour ?: accents.speaking
    val track = trackColour ?: accents.progressTrack
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = Motion.continuous(),
        label = "progressRing",
    )

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(diameter)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animated > 0.0005f) {
                drawArc(
                    color = ringColour,
                    // Start at twelve o'clock and run clockwise, as a clock does.
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content?.invoke()
    }
}

/** A thin progress bar, used under the reader and in the mini player. */
@Composable
fun SlimProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    colour: Color? = null,
    trackColour: Color? = null,
) {
    val accents = LocalAccents.current
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = Motion.continuous(),
        label = "slimProgress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(SoundboundShapes.pill)
            .background(trackColour ?: accents.progressTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(SoundboundShapes.pill)
                .background(colour ?: accents.speaking),
        )
    }
}

/**
 * The waveform behind the player's scrubber.
 *
 * It is a shape, not a real spectrogram — computing one for synthesised speech that has not been
 * rendered yet is impossible, and pretending otherwise would be a lie drawn on the screen. The
 * bars are derived deterministically from the chapter so they at least stay still while you drag,
 * and the played portion is filled.
 */
@Composable
fun WaveformTrack(
    progress: Float,
    seed: Int,
    modifier: Modifier = Modifier,
    barCount: Int = 64,
    playedColour: Color? = null,
    remainingColour: Color? = null,
) {
    val accents = LocalAccents.current
    val played = playedColour ?: accents.speaking
    val remaining = remainingColour ?: accents.progressTrack
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = Motion.continuous(),
        label = "waveform",
    )

    Canvas(modifier = modifier) {
        val gap = size.width / (barCount * 2.2f)
        val barWidth = max(1.5f, (size.width - gap * (barCount - 1)) / barCount)
        val centre = size.height / 2f
        val playedBars = (barCount * animated).toInt()

        for (index in 0 until barCount) {
            // A deterministic pseudo-random height, so the shape is stable for a given chapter.
            val noise = pseudoRandom(seed * 131 + index * 7919)
            val envelope = 0.35f + 0.65f * sin(kotlin.math.PI * index / barCount).toFloat()
            val amplitude = (0.22f + 0.78f * noise) * envelope
            val barHeight = max(2f, size.height * amplitude)
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = if (index <= playedBars) played else remaining,
                topLeft = Offset(left, centre - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** A stable value in 0..1 from an integer seed. */
private fun pseudoRandom(seed: Int): Float {
    var x = seed
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return ((x and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat())
}

/**
 * A sweeping shimmer, shown where content is being loaded.
 *
 * Only used where the wait is expected to be visible — parsing a 900-page PDF's first chapter —
 * and never for something that normally completes in a frame, which would only add flicker.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = SoundboundShapes.small,
) {
    val accents = LocalAccents.current
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accents.coverPlaceholder,
                        accents.coverPlaceholder.copy(alpha = 0.45f),
                        accents.coverPlaceholder,
                    ),
                    start = Offset(offset * 400f, 0f),
                    end = Offset(offset * 400f + 400f, 400f),
                ),
            ),
    )
}

/** A determinate circular indicator for downloads, where the exact figure does matter. */
@Composable
fun DownloadRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 28.dp,
) {
    val accents = LocalAccents.current
    ProgressRing(
        progress = fraction,
        modifier = modifier,
        diameter = diameter,
        strokeWidth = 2.5.dp,
        colour = accents.speaking,
    )
}

/** Clamps a value into 0..1 without the noise of two coerce calls at every call site. */
internal fun Float.asFraction(): Float = min(1f, max(0f, this))
