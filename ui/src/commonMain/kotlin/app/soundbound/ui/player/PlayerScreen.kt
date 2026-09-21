package app.soundbound.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.soundbound.core.model.Book
import app.soundbound.core.player.PlaybackStatus
import app.soundbound.core.player.ReadAloudState
import app.soundbound.ui.components.BookCover
import app.soundbound.ui.components.EqualiserBars
import app.soundbound.ui.components.InlineMessage
import app.soundbound.ui.components.PlayPauseButton
import app.soundbound.ui.components.SlimProgressBar
import app.soundbound.ui.components.SoundboundIconButton
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.components.WaveformTrack
import app.soundbound.ui.components.generatedCoverBrush
import app.soundbound.ui.theme.LocalAccents
import app.soundbound.ui.theme.Motion
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.SoundboundType
import app.soundbound.ui.theme.Spacing

/** What the player is showing. */
data class PlayerScreenState(
    val playback: ReadAloudState,
    val book: Book?,
    val chapterTitle: String?,
    val voiceName: String?,
    val isVoiceInstalled: Boolean = true,
    val sleepTimerLabel: String? = null,
)

/** What the player can ask for. */
data class PlayerActions(
    val onClose: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onSkipSentence: (Int) -> Unit,
    val onSkipParagraph: (Int) -> Unit,
    val onSkipChapter: (Int) -> Unit,
    val onShowSpeed: () -> Unit,
    val onShowSleepTimer: () -> Unit,
    val onShowVoicePicker: () -> Unit,
    val onShowContents: () -> Unit,
    val onShowExport: () -> Unit,
    val onOpenReader: () -> Unit,
    val onGetVoices: () -> Unit,
)

/**
 * The player.
 *
 * Built around the sentence being spoken rather than around a timeline, because a synthesised book
 * has no fixed duration to scrub along — change the speed or the voice and every timestamp moves.
 * What does not move is the text, so the text is the interface: the current sentence is shown
 * large, the transport steps by sentence and paragraph, and progress is a fraction of the book.
 */
@Composable
fun PlayerScreen(
    state: PlayerScreenState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val isPlaying = state.playback.status == PlaybackStatus.PLAYING

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // A wash of the cover's own colour behind everything, so the player feels like part of
        // this book rather than a generic media screen.
        state.book?.let { book ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(generatedCoverBrush(book.metadata.title))
                    .alpha(0.20f),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background,
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
        ) {
            PlayerTopBar(state = state, actions = actions)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(Spacing.default))

                state.book?.let { book ->
                    BookCover(
                        book = book,
                        modifier = Modifier
                            .widthIn(max = 200.dp)
                            .fillMaxWidth(0.5f),
                        shape = SoundboundShapes.small,
                    )
                }

                Spacer(Modifier.height(Spacing.large))

                Text(
                    text = state.book?.metadata?.title ?: "Nothing open",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                state.chapterTitle?.let { chapter ->
                    Spacer(Modifier.height(Spacing.tiny))
                    Text(
                        text = chapter,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(Spacing.large))

                SpokenSentence(
                    sentence = state.playback.currentSentence,
                    isPlaying = isPlaying,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (!state.isVoiceInstalled || state.playback.voice == null) {
                    Spacer(Modifier.height(Spacing.default))
                    InlineMessage(
                        message = "No voice is installed yet. Soundbound needs one to read aloud.",
                        icon = SoundboundIcons.Voices,
                        actionLabel = "Get voices",
                        onAction = actions.onGetVoices,
                    )
                }

                state.playback.errorMessage?.let { error ->
                    Spacer(Modifier.height(Spacing.medium))
                    InlineMessage(message = error, isError = true, icon = SoundboundIcons.Warning)
                }
            }

            PlayerProgress(state = state)
            PlayerTransport(state = state, actions = actions, isPlaying = isPlaying)
            PlayerFooter(state = state, actions = actions)
        }
    }
}

@Composable
private fun PlayerTopBar(state: PlayerScreenState, actions: PlayerActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.small, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoundboundIconButton(SoundboundIcons.ChevronDown, "Close the player", actions.onClose)
        Spacer(Modifier.weight(1f))
        Text(
            text = "Reading aloud",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        SoundboundIconButton(SoundboundIcons.Reader, "Back to the page", actions.onOpenReader)
    }
}

/**
 * The sentence being spoken, set large.
 *
 * This is the part people actually watch while listening — it is how you follow a name you did not
 * catch, or check a figure — so it gets the space, and a quiet equaliser beside it to make clear
 * that it is live rather than merely the last thing said.
 */
@Composable
private fun SpokenSentence(sentence: String, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SoundboundShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.default),
            verticalAlignment = Alignment.Top,
        ) {
            AnimatedVisibility(visible = isPlaying, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp, end = Spacing.medium)
                        .height(18.dp)
                        .width(16.dp),
                ) {
                    EqualiserBars(colour = accents.speaking, barCount = 4)
                }
            }
            Text(
                text = sentence.ifBlank { "Press play to begin." },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Normal,
                    lineHeight = MaterialTheme.typography.titleLarge.fontSize * 1.45f,
                ),
                color = if (sentence.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlayerProgress(state: PlayerScreenState) {
    val progress = state.playback.progress
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large)) {
        WaveformTrack(
            progress = progress.chapterFraction.toFloat(),
            seed = state.playback.chapter.value,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(vertical = Spacing.tiny),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sentenceCounter(state.playback),
                style = SoundboundType.monoNumerals,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(progress.fraction * 100).toInt()}% of the book",
                style = SoundboundType.monoNumerals,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun sentenceCounter(playback: ReadAloudState): String {
    if (playback.unitCount <= 0) return "—"
    val current = (playback.unitIndex + 1).coerceIn(1, playback.unitCount)
    return "Sentence $current of ${playback.unitCount}"
}

@Composable
private fun PlayerTransport(
    state: PlayerScreenState,
    actions: PlayerActions,
    isPlaying: Boolean,
) {
    val enabled = state.playback.voice != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.default, vertical = Spacing.comfortable),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SoundboundIconButton(
            icon = SoundboundIcons.PreviousParagraph,
            contentDescription = "Back a paragraph",
            onClick = { actions.onSkipParagraph(-1) },
            enabled = enabled,
            size = 48.dp,
            iconSize = 24.dp,
        )
        SoundboundIconButton(
            icon = SoundboundIcons.PreviousSentence,
            contentDescription = "Back a sentence",
            onClick = { actions.onSkipSentence(-1) },
            enabled = enabled,
            size = 52.dp,
            iconSize = 26.dp,
        )
        PlayPauseButton(
            isPlaying = isPlaying,
            onToggle = actions.onTogglePlayPause,
            enabled = enabled,
            diameter = 76.dp,
        )
        SoundboundIconButton(
            icon = SoundboundIcons.NextSentence,
            contentDescription = "On a sentence",
            onClick = { actions.onSkipSentence(1) },
            enabled = enabled,
            size = 52.dp,
            iconSize = 26.dp,
        )
        SoundboundIconButton(
            icon = SoundboundIcons.NextParagraph,
            contentDescription = "On a paragraph",
            onClick = { actions.onSkipParagraph(1) },
            enabled = enabled,
            size = 48.dp,
            iconSize = 24.dp,
        )
    }
}

@Composable
private fun PlayerFooter(state: PlayerScreenState, actions: PlayerActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.default, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        FooterAction(
            icon = SoundboundIcons.Speed,
            label = "${trimRate(state.playback.params.rate)}×",
            contentDescription = "Reading speed",
            onClick = actions.onShowSpeed,
        )
        FooterAction(
            icon = SoundboundIcons.Voices,
            label = state.voiceName?.take(14) ?: "Voice",
            contentDescription = "Choose a voice",
            onClick = actions.onShowVoicePicker,
        )
        FooterAction(
            icon = SoundboundIcons.SleepTimer,
            label = state.sleepTimerLabel ?: "Sleep",
            contentDescription = "Sleep timer",
            onClick = actions.onShowSleepTimer,
            highlighted = state.sleepTimerLabel != null,
        )
        FooterAction(
            icon = SoundboundIcons.Download,
            label = "MP3",
            contentDescription = "Export as MP3",
            onClick = actions.onShowExport,
        )
        FooterAction(
            icon = SoundboundIcons.Contents,
            label = "Contents",
            contentDescription = "Contents",
            onClick = actions.onShowContents,
        )
    }
}

@Composable
private fun FooterAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val accents = LocalAccents.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(SoundboundShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.small, vertical = Spacing.small),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = if (highlighted) accents.speaking else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.tiny))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) accents.speaking else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** "1.0" reads better on a control than "1.00", and "1.25" better than "1.2". */
fun trimRate(rate: Float): String {
    val rounded = (rate * 100).toInt()
    return when {
        rounded % 100 == 0 -> "${rounded / 100}.0"
        rounded % 10 == 0 -> "${rounded / 100}.${(rounded % 100) / 10}"
        else -> "${rounded / 100}.${(rounded % 100).toString().padStart(2, '0')}"
    }
}

/**
 * The bar above the bottom navigation while something is being read aloud.
 *
 * Kept to one line: the title, the sentence, and a play button. Anyone who wants more taps it and
 * gets the full player.
 */
@Composable
fun MiniPlayer(
    state: PlayerScreenState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAccents.current
    val isPlaying = state.playback.status == PlaybackStatus.PLAYING
    val book = state.book ?: return

    val elevation by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = Motion.standard(),
        label = "miniPlayerLift",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = (2 + elevation * 3).dp,
    ) {
        Column {
            SlimProgressBar(
                progress = state.playback.progress.fraction.toFloat(),
                height = 2.dp,
                colour = accents.speaking,
                trackColour = Color.Transparent,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(
                    book = book,
                    modifier = Modifier.width(34.dp),
                    shape = SoundboundShapes.cover,
                    showSpine = false,
                )
                Spacer(Modifier.width(Spacing.medium))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.metadata.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.playback.currentSentence.ifBlank {
                            state.chapterTitle ?: "Ready"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(Spacing.small))
                SoundboundIconButton(
                    icon = if (isPlaying) SoundboundIcons.Pause else SoundboundIcons.Play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    onClick = onTogglePlayPause,
                    size = 40.dp,
                    iconSize = 20.dp,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    background = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
