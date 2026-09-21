package app.soundbound.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Soundbound's icons, drawn here rather than pulled from an icon pack.
 *
 * Three reasons, in order of how much they matter. The set is consistent — one 1.8 dp stroke,
 * one 24 dp grid, one set of end caps — which is most of what makes an interface look finished.
 * Several of these do not exist in any standard pack ("skip one sentence", "voice pack"). And
 * it removes a dependency that JetBrains has already deprecated once for Compose Multiplatform.
 */
object SoundboundIcons {

    private const val VIEWPORT = 24f
    private const val STROKE = 1.8f

    /** Builds a stroked icon on the 24 dp grid. */
    private fun stroked(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply(block).build()

    /** A stroked path with the house style applied. */
    private fun ImageVector.Builder.line(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    /** A filled path, for the few icons that read better solid (play, pause, record). */
    private fun ImageVector.Builder.solid(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
            pathBuilder = block,
        )
    }

    // ---------------------------------------------------------------- navigation

    val Library: ImageVector by lazy {
        stroked("Library") {
            line {
                // Three books leaning on a shelf.
                moveTo(4f, 5f); lineTo(4f, 19f); moveTo(4f, 5f)
                moveTo(4f, 19f); lineTo(20f, 19f)
                moveTo(6.5f, 16.5f); lineTo(6.5f, 7f); lineTo(9.5f, 7f); lineTo(9.5f, 16.5f); close()
                moveTo(11f, 16.5f); lineTo(11f, 9f); lineTo(14f, 9f); lineTo(14f, 16.5f); close()
                moveTo(15.6f, 16.6f); lineTo(17.4f, 8.2f); lineTo(20.2f, 8.8f); lineTo(18.4f, 17.2f); close()
            }
        }
    }

    val Reader: ImageVector by lazy {
        stroked("Reader") {
            line {
                moveTo(12f, 6.5f); curveTo(10f, 5f, 7.5f, 4.6f, 4.5f, 5.2f)
                lineTo(4.5f, 18.2f); curveTo(7.5f, 17.6f, 10f, 18f, 12f, 19.5f)
                curveTo(14f, 18f, 16.5f, 17.6f, 19.5f, 18.2f); lineTo(19.5f, 5.2f)
                curveTo(16.5f, 4.6f, 14f, 5f, 12f, 6.5f); close()
                moveTo(12f, 6.5f); lineTo(12f, 19.5f)
            }
        }
    }

    val Voices: ImageVector by lazy {
        stroked("Voices") {
            line {
                // A waveform: the app's signature mark.
                moveTo(3f, 12f); lineTo(3f, 12f)
                moveTo(6f, 8.5f); lineTo(6f, 15.5f)
                moveTo(9.5f, 5f); lineTo(9.5f, 19f)
                moveTo(13f, 7.5f); lineTo(13f, 16.5f)
                moveTo(16.5f, 4f); lineTo(16.5f, 20f)
                moveTo(20f, 9.5f); lineTo(20f, 14.5f)
            }
        }
    }

    val Settings: ImageVector by lazy {
        stroked("Settings") {
            line {
                moveTo(5f, 7f); lineTo(19f, 7f)
                moveTo(5f, 12f); lineTo(19f, 12f)
                moveTo(5f, 17f); lineTo(19f, 17f)
            }
            line {
                moveTo(9f, 7f); arcToRelative(1.9f, 1.9f, 0f, true, true, 0.01f, 0f); close()
                moveTo(16f, 12f); arcToRelative(1.9f, 1.9f, 0f, true, true, 0.01f, 0f); close()
                moveTo(11f, 17f); arcToRelative(1.9f, 1.9f, 0f, true, true, 0.01f, 0f); close()
            }
        }
    }

    // ---------------------------------------------------------------- transport

    val Play: ImageVector by lazy {
        stroked("Play") {
            solid {
                moveTo(8f, 5.5f)
                lineTo(19f, 11.15f)
                curveTo(19.6f, 11.5f, 19.6f, 12.5f, 19f, 12.85f)
                lineTo(8f, 18.5f)
                curveTo(7.4f, 18.8f, 6.8f, 18.4f, 6.8f, 17.8f)
                lineTo(6.8f, 6.2f)
                curveTo(6.8f, 5.6f, 7.4f, 5.2f, 8f, 5.5f)
                close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        stroked("Pause") {
            solid {
                moveTo(8.2f, 5f); lineTo(10.2f, 5f)
                curveTo(10.7f, 5f, 11f, 5.3f, 11f, 5.8f)
                lineTo(11f, 18.2f)
                curveTo(11f, 18.7f, 10.7f, 19f, 10.2f, 19f)
                lineTo(8.2f, 19f)
                curveTo(7.7f, 19f, 7.4f, 18.7f, 7.4f, 18.2f)
                lineTo(7.4f, 5.8f)
                curveTo(7.4f, 5.3f, 7.7f, 5f, 8.2f, 5f); close()
                moveTo(13.8f, 5f); lineTo(15.8f, 5f)
                curveTo(16.3f, 5f, 16.6f, 5.3f, 16.6f, 5.8f)
                lineTo(16.6f, 18.2f)
                curveTo(16.6f, 18.7f, 16.3f, 19f, 15.8f, 19f)
                lineTo(13.8f, 19f)
                curveTo(13.3f, 19f, 13f, 18.7f, 13f, 18.2f)
                lineTo(13f, 5.8f)
                curveTo(13f, 5.3f, 13.3f, 5f, 13.8f, 5f); close()
            }
        }
    }

    val Stop: ImageVector by lazy {
        stroked("Stop") {
            solid {
                moveTo(7f, 6.6f)
                curveTo(7f, 5.7f, 7.7f, 5f, 8.6f, 5f)
                lineTo(15.4f, 5f)
                curveTo(16.3f, 5f, 17f, 5.7f, 17f, 6.6f)
                lineTo(17f, 17.4f)
                curveTo(17f, 18.3f, 16.3f, 19f, 15.4f, 19f)
                lineTo(8.6f, 19f)
                curveTo(7.7f, 19f, 7f, 18.3f, 7f, 17.4f)
                close()
            }
        }
    }

    /** Back one sentence: a bar with a triangle pointing into it. */
    val PreviousSentence: ImageVector by lazy {
        stroked("PreviousSentence") {
            line { moveTo(6f, 6f); lineTo(6f, 18f) }
            solid {
                moveTo(18f, 6.4f); lineTo(18f, 17.6f)
                curveTo(18f, 18.3f, 17.2f, 18.7f, 16.6f, 18.3f)
                lineTo(8.6f, 12.7f)
                curveTo(8.1f, 12.3f, 8.1f, 11.7f, 8.6f, 11.3f)
                lineTo(16.6f, 5.7f)
                curveTo(17.2f, 5.3f, 18f, 5.7f, 18f, 6.4f); close()
            }
        }
    }

    val NextSentence: ImageVector by lazy {
        stroked("NextSentence") {
            line { moveTo(18f, 6f); lineTo(18f, 18f) }
            solid {
                moveTo(6f, 6.4f); lineTo(6f, 17.6f)
                curveTo(6f, 18.3f, 6.8f, 18.7f, 7.4f, 18.3f)
                lineTo(15.4f, 12.7f)
                curveTo(15.9f, 12.3f, 15.9f, 11.7f, 15.4f, 11.3f)
                lineTo(7.4f, 5.7f)
                curveTo(6.8f, 5.3f, 6f, 5.7f, 6f, 6.4f); close()
            }
        }
    }

    /** Back one paragraph: a double chevron. */
    val PreviousParagraph: ImageVector by lazy {
        stroked("PreviousParagraph") {
            line {
                moveTo(13.5f, 6.5f); lineTo(8f, 12f); lineTo(13.5f, 17.5f)
                moveTo(19f, 6.5f); lineTo(13.5f, 12f); lineTo(19f, 17.5f)
                moveTo(5f, 6.5f); lineTo(5f, 17.5f)
            }
        }
    }

    val NextParagraph: ImageVector by lazy {
        stroked("NextParagraph") {
            line {
                moveTo(10.5f, 6.5f); lineTo(16f, 12f); lineTo(10.5f, 17.5f)
                moveTo(5f, 6.5f); lineTo(10.5f, 12f); lineTo(5f, 17.5f)
                moveTo(19f, 6.5f); lineTo(19f, 17.5f)
            }
        }
    }

    val Speed: ImageVector by lazy {
        stroked("Speed") {
            line {
                // A dial with the needle past the middle, which is what the control does.
                moveTo(4f, 17f); arcTo(8.5f, 8.5f, 0f, false, true, 20f, 17f)
                moveTo(12f, 12.5f); lineTo(16.2f, 8.6f)
            }
            solid {
                moveTo(12f, 11.3f); arcToRelative(1.3f, 1.3f, 0f, true, true, 0.01f, 0f); close()
            }
        }
    }

    val SleepTimer: ImageVector by lazy {
        stroked("SleepTimer") {
            line {
                moveTo(12f, 4.2f); arcTo(7.8f, 7.8f, 0f, true, true, 11.9f, 4.2f); close()
                moveTo(12f, 8.4f); lineTo(12f, 12.4f); lineTo(15f, 14.2f)
            }
            line {
                // A small crescent, so it reads as "sleep" rather than merely "clock".
                moveTo(17.4f, 3.2f); curveTo(19.4f, 3.6f, 20.8f, 5.4f, 20.6f, 7.4f)
                curveTo(19.4f, 5.4f, 18.6f, 4.2f, 17.4f, 3.2f); close()
            }
        }
    }

    val Waveform: ImageVector by lazy { Voices }

    // ---------------------------------------------------------------- reader

    val Contents: ImageVector by lazy {
        stroked("Contents") {
            line {
                moveTo(4f, 6.5f); lineTo(4.01f, 6.5f)
                moveTo(8f, 6.5f); lineTo(20f, 6.5f)
                moveTo(4f, 12f); lineTo(4.01f, 12f)
                moveTo(8f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17.5f); lineTo(4.01f, 17.5f)
                moveTo(8f, 17.5f); lineTo(20f, 17.5f)
            }
        }
    }

    val Bookmark: ImageVector by lazy {
        stroked("Bookmark") {
            line {
                moveTo(7f, 4.8f); lineTo(17f, 4.8f)
                curveTo(17.4f, 4.8f, 17.7f, 5.1f, 17.7f, 5.5f)
                lineTo(17.7f, 19.4f)
                curveTo(17.7f, 19.9f, 17.2f, 20.2f, 16.8f, 19.9f)
                lineTo(12f, 16.6f); lineTo(7.2f, 19.9f)
                curveTo(6.8f, 20.2f, 6.3f, 19.9f, 6.3f, 19.4f)
                lineTo(6.3f, 5.5f)
                curveTo(6.3f, 5.1f, 6.6f, 4.8f, 7f, 4.8f); close()
            }
        }
    }

    val BookmarkFilled: ImageVector by lazy {
        stroked("BookmarkFilled") {
            solid {
                moveTo(7f, 4.8f); lineTo(17f, 4.8f)
                curveTo(17.4f, 4.8f, 17.7f, 5.1f, 17.7f, 5.5f)
                lineTo(17.7f, 19.4f)
                curveTo(17.7f, 19.9f, 17.2f, 20.2f, 16.8f, 19.9f)
                lineTo(12f, 16.6f); lineTo(7.2f, 19.9f)
                curveTo(6.8f, 20.2f, 6.3f, 19.9f, 6.3f, 19.4f)
                lineTo(6.3f, 5.5f)
                curveTo(6.3f, 5.1f, 6.6f, 4.8f, 7f, 4.8f); close()
            }
        }
    }

    val Highlighter: ImageVector by lazy {
        stroked("Highlighter") {
            line {
                moveTo(9f, 15.5f); lineTo(4.8f, 19.7f)
                moveTo(15.2f, 4.6f); lineTo(19.4f, 8.8f)
                lineTo(11.4f, 16.8f); lineTo(7.2f, 12.6f); close()
                moveTo(4f, 21.5f); lineTo(11f, 21.5f)
            }
        }
    }

    val Typography: ImageVector by lazy {
        stroked("Typography") {
            line {
                moveTo(4f, 18f); lineTo(9f, 6f); lineTo(14f, 18f)
                moveTo(5.8f, 14f); lineTo(12.2f, 14f)
                moveTo(16f, 18f); lineTo(20f, 8f)
                moveTo(17.2f, 15f); lineTo(20.8f, 15f)
            }
        }
    }

    val Brightness: ImageVector by lazy {
        stroked("Brightness") {
            line {
                moveTo(12f, 8.2f); arcTo(3.8f, 3.8f, 0f, true, true, 11.9f, 8.2f); close()
                moveTo(12f, 2.8f); lineTo(12f, 4.6f)
                moveTo(12f, 19.4f); lineTo(12f, 21.2f)
                moveTo(2.8f, 12f); lineTo(4.6f, 12f)
                moveTo(19.4f, 12f); lineTo(21.2f, 12f)
                moveTo(5.5f, 5.5f); lineTo(6.8f, 6.8f)
                moveTo(17.2f, 17.2f); lineTo(18.5f, 18.5f)
                moveTo(18.5f, 5.5f); lineTo(17.2f, 6.8f)
                moveTo(6.8f, 17.2f); lineTo(5.5f, 18.5f)
            }
        }
    }

    // ---------------------------------------------------------------- general

    val Search: ImageVector by lazy {
        stroked("Search") {
            line {
                moveTo(10.8f, 4.5f); arcTo(6.3f, 6.3f, 0f, true, true, 10.7f, 4.5f); close()
                moveTo(15.4f, 15.4f); lineTo(19.8f, 19.8f)
            }
        }
    }

    val Add: ImageVector by lazy {
        stroked("Add") {
            line {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }
    }

    val Close: ImageVector by lazy {
        stroked("Close") {
            line {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }

    val Back: ImageVector by lazy {
        stroked("Back") {
            line {
                moveTo(19f, 12f); lineTo(5f, 12f)
                moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        stroked("ChevronDown") { line { moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f) } }
    }

    val ChevronUp: ImageVector by lazy {
        stroked("ChevronUp") { line { moveTo(6f, 14.5f); lineTo(12f, 8.5f); lineTo(18f, 14.5f) } }
    }

    val ChevronRight: ImageVector by lazy {
        stroked("ChevronRight") { line { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) } }
    }

    val Check: ImageVector by lazy {
        stroked("Check") { line { moveTo(5f, 12.8f); lineTo(9.6f, 17.4f); lineTo(19f, 7f) } }
    }

    val More: ImageVector by lazy {
        stroked("More") {
            line {
                moveTo(12f, 5.4f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0.01f, 0f); close()
                moveTo(12f, 10.6f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0.01f, 0f); close()
                moveTo(12f, 15.8f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0.01f, 0f); close()
            }
        }
    }

    val Sort: ImageVector by lazy {
        stroked("Sort") {
            line {
                moveTo(5f, 7f); lineTo(15f, 7f)
                moveTo(5f, 12f); lineTo(12f, 12f)
                moveTo(5f, 17f); lineTo(9f, 17f)
                moveTo(17.5f, 6f); lineTo(17.5f, 18f)
                moveTo(14.5f, 15f); lineTo(17.5f, 18f); lineTo(20.5f, 15f)
            }
        }
    }

    val Filter: ImageVector by lazy {
        stroked("Filter") {
            line {
                moveTo(4f, 6.5f); lineTo(20f, 6.5f)
                moveTo(7f, 12f); lineTo(17f, 12f)
                moveTo(10f, 17.5f); lineTo(14f, 17.5f)
            }
        }
    }

    val Delete: ImageVector by lazy {
        stroked("Delete") {
            line {
                moveTo(5f, 7.5f); lineTo(19f, 7.5f)
                moveTo(9.5f, 7.5f); lineTo(9.5f, 5.6f)
                curveTo(9.5f, 5.3f, 9.8f, 5f, 10.1f, 5f)
                lineTo(13.9f, 5f); curveTo(14.2f, 5f, 14.5f, 5.3f, 14.5f, 5.6f)
                lineTo(14.5f, 7.5f)
                moveTo(6.8f, 7.5f); lineTo(7.6f, 18.6f)
                curveTo(7.6f, 19.3f, 8.2f, 19.8f, 8.9f, 19.8f)
                lineTo(15.1f, 19.8f)
                curveTo(15.8f, 19.8f, 16.4f, 19.3f, 16.4f, 18.6f)
                lineTo(17.2f, 7.5f)
                moveTo(10.5f, 11f); lineTo(10.5f, 16.5f)
                moveTo(13.5f, 11f); lineTo(13.5f, 16.5f)
            }
        }
    }

    val Download: ImageVector by lazy {
        stroked("Download") {
            line {
                moveTo(12f, 4f); lineTo(12f, 14.5f)
                moveTo(7.5f, 10f); lineTo(12f, 14.5f); lineTo(16.5f, 10f)
                moveTo(4.5f, 18.5f); lineTo(19.5f, 18.5f)
            }
        }
    }

    val Favourite: ImageVector by lazy {
        stroked("Favourite") {
            line {
                moveTo(12f, 20f)
                curveTo(12f, 20f, 3.6f, 14.8f, 3.6f, 9.4f)
                curveTo(3.6f, 6.6f, 5.8f, 4.5f, 8.5f, 4.5f)
                curveTo(10.2f, 4.5f, 11.4f, 5.4f, 12f, 6.3f)
                curveTo(12.6f, 5.4f, 13.8f, 4.5f, 15.5f, 4.5f)
                curveTo(18.2f, 4.5f, 20.4f, 6.6f, 20.4f, 9.4f)
                curveTo(20.4f, 14.8f, 12f, 20f, 12f, 20f)
                close()
            }
        }
    }

    val FavouriteFilled: ImageVector by lazy {
        stroked("FavouriteFilled") {
            solid {
                moveTo(12f, 20f)
                curveTo(12f, 20f, 3.6f, 14.8f, 3.6f, 9.4f)
                curveTo(3.6f, 6.6f, 5.8f, 4.5f, 8.5f, 4.5f)
                curveTo(10.2f, 4.5f, 11.4f, 5.4f, 12f, 6.3f)
                curveTo(12.6f, 5.4f, 13.8f, 4.5f, 15.5f, 4.5f)
                curveTo(18.2f, 4.5f, 20.4f, 6.6f, 20.4f, 9.4f)
                curveTo(20.4f, 14.8f, 12f, 20f, 12f, 20f)
                close()
            }
        }
    }

    val Folder: ImageVector by lazy {
        stroked("Folder") {
            line {
                moveTo(4f, 7.4f)
                curveTo(4f, 6.6f, 4.6f, 6f, 5.4f, 6f)
                lineTo(9.2f, 6f); lineTo(11f, 8.2f); lineTo(18.6f, 8.2f)
                curveTo(19.4f, 8.2f, 20f, 8.8f, 20f, 9.6f)
                lineTo(20f, 17.6f)
                curveTo(20f, 18.4f, 19.4f, 19f, 18.6f, 19f)
                lineTo(5.4f, 19f)
                curveTo(4.6f, 19f, 4f, 18.4f, 4f, 17.6f)
                close()
            }
        }
    }

    val Note: ImageVector by lazy {
        stroked("Note") {
            line {
                moveTo(6f, 4.8f); lineTo(15f, 4.8f); lineTo(19f, 8.8f); lineTo(19f, 19.2f)
                lineTo(6f, 19.2f); close()
                moveTo(14.6f, 4.8f); lineTo(14.6f, 9.2f); lineTo(19f, 9.2f)
                moveTo(9f, 13f); lineTo(16f, 13f)
                moveTo(9f, 16.2f); lineTo(14f, 16.2f)
            }
        }
    }

    val Warning: ImageVector by lazy {
        stroked("Warning") {
            line {
                moveTo(12f, 4.5f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
                moveTo(12f, 10f); lineTo(12f, 14.6f)
                moveTo(12f, 17f); lineTo(12.01f, 17f)
            }
        }
    }

    val Info: ImageVector by lazy {
        stroked("Info") {
            line {
                moveTo(12f, 3.8f); arcTo(8.2f, 8.2f, 0f, true, true, 11.9f, 3.8f); close()
                moveTo(12f, 11f); lineTo(12f, 16.4f)
                moveTo(12f, 7.8f); lineTo(12.01f, 7.8f)
            }
        }
    }

    val Offline: ImageVector by lazy {
        stroked("Offline") {
            line {
                moveTo(4.5f, 9.5f); curveTo(8.5f, 6f, 15.5f, 6f, 19.5f, 9.5f)
                moveTo(7.5f, 13f); curveTo(10f, 11f, 14f, 11f, 16.5f, 13f)
                moveTo(12f, 17.5f); lineTo(12.01f, 17.5f)
                moveTo(4f, 20f); lineTo(20f, 4f)
            }
        }
    }

    val Grid: ImageVector by lazy {
        stroked("Grid") {
            line {
                moveTo(4.5f, 4.5f); lineTo(10.5f, 4.5f); lineTo(10.5f, 10.5f); lineTo(4.5f, 10.5f); close()
                moveTo(13.5f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 10.5f); lineTo(13.5f, 10.5f); close()
                moveTo(4.5f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 19.5f); lineTo(4.5f, 19.5f); close()
                moveTo(13.5f, 13.5f); lineTo(19.5f, 13.5f); lineTo(19.5f, 19.5f); lineTo(13.5f, 19.5f); close()
            }
        }
    }

    val List: ImageVector by lazy { Contents }
}
