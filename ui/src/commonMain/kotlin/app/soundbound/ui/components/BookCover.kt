package app.soundbound.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundbound.core.model.Book
import app.soundbound.ui.theme.LocalAccents
import app.soundbound.ui.theme.Motion
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.SoundboundType
import kotlin.math.absoluteValue

/** The usual trade paperback proportion. Covers that differ are cropped to it, not letterboxed. */
const val COVER_ASPECT_RATIO = 2f / 3f

/**
 * A book's cover.
 *
 * Books without one get a generated cover rather than a grey rectangle: the title and author set
 * on a colour derived from the title itself, so the same book always looks the same and a shelf
 * of them looks deliberate. A library of public-domain EPUBs is mostly books without covers, so
 * this is the common case, not the fallback.
 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier,
    shape: Shape = SoundboundShapes.cover,
    showSpine: Boolean = true,
) {
    val cover by rememberCoverImage(book.coverImageRef)

    Box(
        modifier = modifier
            .aspectRatio(COVER_ASPECT_RATIO)
            .clip(shape)
            .background(generatedCoverBrush(book.metadata.title)),
    ) {
        val image = cover
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GeneratedCover(
                title = book.metadata.title,
                author = book.metadata.authorLine,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showSpine) {
            // A darker band down the binding edge. It is what makes a flat rectangle read as an
            // object rather than an image, and it costs one gradient.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.22f),
                                0.035f to Color.Black.copy(alpha = 0.06f),
                                0.08f to Color.Transparent,
                                0.94f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.10f),
                            ),
                        )
                    },
            )
        }
    }
}

@Composable
private fun GeneratedCover(title: String, author: String, modifier: Modifier = Modifier) {
    val ink = Color.White.copy(alpha = 0.94f)
    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = SoundboundType.Typography.headlineSmall.copy(
                fontSize = coverTitleSize(title),
                lineHeight = coverTitleSize(title) * 1.22f,
                fontWeight = FontWeight.SemiBold,
            ),
            color = ink,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Column {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(ink.copy(alpha = 0.6f))
                    .fillMaxWidth(0.35f)
                    .padding(top = 1.dp),
            )
            Text(
                text = author,
                style = SoundboundType.Typography.labelMedium,
                color = ink.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Long titles get smaller type so they still fit on the cover without truncating. */
private fun coverTitleSize(title: String) = when {
    title.length <= 18 -> 22.sp
    title.length <= 34 -> 18.sp
    title.length <= 56 -> 15.sp
    else -> 13.sp
}

/**
 * A two-stop gradient chosen from a hash of the title.
 *
 * Hues are drawn from a fixed set of muted book-cloth colours rather than the whole wheel: a
 * random hue produces the occasional acid green, and one bad cover spoils the grid.
 */
fun generatedCoverBrush(seed: String): Brush {
    val palette = COVER_PALETTE[(seed.stableHash() % COVER_PALETTE.size.toLong()).toInt().absoluteValue]
    return Brush.linearGradient(listOf(palette.first, palette.second))
}

private fun String.stableHash(): Long {
    // A fixed hash rather than String.hashCode, which is not guaranteed stable across platforms.
    var hash = 1125899906842597L
    forEach { ch -> hash = 31 * hash + ch.code }
    return hash.absoluteValue
}

private val COVER_PALETTE: List<Pair<Color, Color>> = listOf(
    Color(0xFF6B4C3B) to Color(0xFF3E2B21),   // Russet cloth
    Color(0xFF2F4858) to Color(0xFF1B2C36),   // Slate
    Color(0xFF4A5D3A) to Color(0xFF2A3722),   // Moss
    Color(0xFF6E3B4E) to Color(0xFF41222E),   // Burgundy
    Color(0xFF3B4A6B) to Color(0xFF212B41),   // Indigo
    Color(0xFF7A5B2E) to Color(0xFF48351B),   // Tan
    Color(0xFF3F5E5A) to Color(0xFF243836),   // Teal cloth
    Color(0xFF5A3F6B) to Color(0xFF352441),   // Plum
    Color(0xFF6B5330) to Color(0xFF3F311D),   // Olive
    Color(0xFF2E4A3F) to Color(0xFF1B2C25),   // Forest
    Color(0xFF6B3B3B) to Color(0xFF412222),   // Brick
    Color(0xFF3A4A52) to Color(0xFF222C31),   // Storm
)

/**
 * The cover with a progress indicator along its foot.
 *
 * Progress is shown on the cover rather than beside it because at grid sizes the cover is the
 * only thing with room for it, and a one-glance answer to "how far am I through this" is the
 * single most useful thing a library screen can tell you.
 */
@Composable
fun BookCoverWithProgress(
    book: Book,
    progress: Double,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    isSpeaking: Boolean = false,
) {
    val accents = LocalAccents.current
    val animatedProgress by animateFloatAsState(
        targetValue = progress.toFloat().coerceIn(0f, 1f),
        animationSpec = Motion.continuous(),
        label = "coverProgress",
    )

    Box(modifier = modifier) {
        BookCover(book, modifier = Modifier.fillMaxWidth())

        if (showProgress && animatedProgress > 0.001f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SoundboundShapes.pill)
                        .background(Color.Black.copy(alpha = 0.32f))
                        .padding(1.5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .clip(SoundboundShapes.pill)
                            .background(if (isSpeaking) accents.speaking else Color.White.copy(alpha = 0.92f))
                            .padding(vertical = 1.5.dp),
                    )
                }
            }
        }

        if (isSpeaking) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            ) {
                SpeakingBadge()
            }
        }
    }
}

/** A small animated mark showing that this book is the one currently being read aloud. */
@Composable
fun SpeakingBadge(modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    Box(
        modifier = modifier
            .clip(SoundboundShapes.pill)
            .background(accents.speaking)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        EqualiserBars(
            colour = Color.Black.copy(alpha = 0.78f),
            modifier = Modifier.fillMaxSize(),
            barCount = 3,
        )
    }
}

/** Placeholder used while a cover is still being read from disk. */
@Composable
fun CoverPlaceholder(modifier: Modifier = Modifier, shape: Shape = SoundboundShapes.cover) {
    val accents = LocalAccents.current
    Box(
        modifier = modifier
            .aspectRatio(COVER_ASPECT_RATIO)
            .clip(shape)
            .background(accents.coverPlaceholder),
    )
}

/** A title's initials, for the compact list view where there is no room for a cover. */
@Composable
fun BookInitials(title: String, modifier: Modifier = Modifier) {
    val initials = title.split(' ')
        .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

    Box(
        modifier = modifier
            .clip(SoundboundShapes.small)
            .background(generatedCoverBrush(title)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}
