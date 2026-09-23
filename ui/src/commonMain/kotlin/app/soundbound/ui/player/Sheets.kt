package app.soundbound.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.soundbound.core.export.ExportFormat
import app.soundbound.core.export.ExportGrouping
import app.soundbound.core.export.ExportProgress
import app.soundbound.core.export.Mp3Settings
import app.soundbound.core.model.Bookmark
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.TocEntry
import app.soundbound.ui.components.DownloadRing
import app.soundbound.ui.components.EmptyState
import app.soundbound.ui.components.HairlineDivider
import app.soundbound.ui.components.InlineMessage
import app.soundbound.ui.components.LabelledSlider
import app.soundbound.ui.components.SectionHeader
import app.soundbound.ui.components.SoundboundChip
import app.soundbound.ui.components.SoundboundIconButton
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.components.SwitchRow
import app.soundbound.ui.theme.LocalAccents
import app.soundbound.ui.theme.LocalHaptics
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.SoundboundType
import app.soundbound.ui.theme.Spacing

/** A title bar for a sheet, with a close button. */
@Composable
fun SheetHeader(title: String, subtitle: String? = null, onClose: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter, end = Spacing.small, top = Spacing.small, bottom = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClose != null) {
            SoundboundIconButton(SoundboundIcons.Close, "Close", onClose)
        }
    }
}

/**
 * The table of contents.
 *
 * Nesting is shown by indentation rather than by collapsible groups: a reader looking for chapter
 * nineteen wants to see it, not to expand a part first.
 */
@Composable
fun ContentsSheet(
    toc: List<TocEntry>,
    currentChapter: ChapterIndex,
    onSelect: (TocEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAccents.current
    val flattened = remember(toc) { toc.flatMap { it.flatten() } }

    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader(
            title = "Contents",
            subtitle = if (flattened.isEmpty()) null else "${flattened.size} entries",
            onClose = onClose,
        )
        HairlineDivider(inset = 0.dp)

        if (flattened.isEmpty()) {
            EmptyState(
                icon = SoundboundIcons.Contents,
                title = "No contents",
                message = "This book does not provide a table of contents.",
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            items(items = flattened, key = { "${it.chapter.value}-${it.fragment}-${it.title}" }) { entry ->
                val isCurrent = entry.chapter == currentChapter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry) }
                        .padding(
                            start = Spacing.gutter + (entry.depth * 16).dp,
                            end = Spacing.gutter,
                            top = Spacing.medium,
                            bottom = Spacing.medium,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isCurrent) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(SoundboundShapes.pill)
                                .background(accents.speaking),
                        )
                        Spacer(Modifier.width(Spacing.small))
                    }
                    Text(
                        text = entry.title,
                        style = if (entry.depth == 0) {
                            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${entry.chapter.value + 1}",
                        style = SoundboundType.monoNumerals,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Reading speed, with the presets people actually use and a slider for the rest. */
@Composable
fun SpeedSheet(
    rate: Float,
    pitchSemitones: Float,
    expressiveness: Float,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onExpressivenessChange: (Float) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetHeader("Voice", "Speed, pitch and delivery", onClose)

        SectionHeader("Speed")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { preset ->
                SoundboundChip(
                    label = "${trimRate(preset)}×",
                    selected = kotlin.math.abs(rate - preset) < 0.01f,
                    onClick = { onRateChange(preset) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(Spacing.medium))
        LabelledSlider(
            label = "Fine adjustment",
            value = rate,
            onValueChange = onRateChange,
            valueRange = 0.5f..3f,
            valueLabel = { "${trimRate(it)}×" },
            icon = SoundboundIcons.Speed,
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )

        SectionHeader("Delivery")
        LabelledSlider(
            label = "Pitch",
            value = pitchSemitones,
            onValueChange = onPitchChange,
            valueRange = -6f..6f,
            steps = 23,
            valueLabel = { semitones -> if (semitones == 0f) "Normal" else "%+.1f".format(semitones) },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        Spacer(Modifier.height(Spacing.small))
        LabelledSlider(
            label = "Expressiveness",
            value = expressiveness,
            onValueChange = onExpressivenessChange,
            valueRange = 0.2f..1.2f,
            valueLabel = { value ->
                when {
                    value < 0.45f -> "Measured"
                    value < 0.75f -> "Natural"
                    value < 1.0f -> "Animated"
                    else -> "Theatrical"
                }
            },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        Text(
            text = "More expression gives a livelier reading, and occasionally an odd one. " +
                "Natural suits most books.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.small),
        )

        Spacer(Modifier.height(Spacing.default))
        Row(modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.small)) {
            SoundboundChip(label = "Reset to normal", selected = false, onClick = onReset)
        }
        Spacer(Modifier.height(Spacing.large))
    }
}

/** The sleep timer, including the option everyone actually wants: end of chapter. */
@Composable
fun SleepTimerSheet(
    activeMillisRemaining: Long?,
    onSet: (Long?) -> Unit,
    onExtend: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current

    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader(
            title = "Sleep timer",
            subtitle = activeMillisRemaining?.let { "Stopping in ${formatDuration(it)}" }
                ?: "Soundbound will stop reading after the time you choose",
            onClose = onClose,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                SoundboundChip(
                    label = "$minutes m",
                    selected = false,
                    onClick = {
                        haptics.confirm()
                        onSet(minutes * 60_000L)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (activeMillisRemaining != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                SoundboundChip(
                    label = "Add 15 minutes",
                    selected = false,
                    onClick = { onExtend(15 * 60_000L) },
                )
                SoundboundChip(
                    label = "Cancel timer",
                    selected = false,
                    onClick = { onSet(null) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.large))
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
        else -> "%d:%02d".format(minutes, seconds)
    }
}

/** Bookmarks in the open book. */
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    onSelect: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader(
            title = "Bookmarks",
            subtitle = if (bookmarks.isEmpty()) null else "${bookmarks.size} in this book",
            onClose = onClose,
        )
        HairlineDivider(inset = 0.dp)

        if (bookmarks.isEmpty()) {
            EmptyState(
                icon = SoundboundIcons.Bookmark,
                title = "No bookmarks yet",
                message = "Mark a place and it will appear here, on every device you copy your library to.",
                action = {
                    SoundboundChip(label = "Bookmark this spot", selected = true, onClick = onAdd)
                },
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
            items(items = bookmarks, key = { it.id }) { bookmark ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(bookmark) }
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        SoundboundIcons.BookmarkFilled,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(Spacing.medium))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bookmark.label ?: "Chapter ${bookmark.position.chapter.value + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = bookmark.excerpt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SoundboundIconButton(
                        icon = SoundboundIcons.Delete,
                        contentDescription = "Delete this bookmark",
                        onClick = { onDelete(bookmark) },
                        size = 36.dp,
                        iconSize = 17.dp,
                    )
                }
                HairlineDivider()
            }
        }
    }
}

/** What the export sheet is showing. */
data class ExportSheetState(
    val chapterTitles: List<String>,
    val selectedChapters: Set<Int>,
    val format: ExportFormat,
    val grouping: ExportGrouping,
    val mp3Settings: Mp3Settings,
    val voiceName: String?,
    val destinationLabel: String,
    val progress: ExportProgress? = null,
    val isRunning: Boolean = false,
)

/**
 * Export to MP3.
 *
 * The estimate is given in megabytes rather than in minutes, because at a constant bit rate the
 * size is arithmetic while the duration depends on the voice, the speed and the length of every
 * sentence — and a wrong time estimate is more annoying than none.
 */
@Composable
fun ExportSheet(
    state: ExportSheetState,
    onToggleChapter: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onFormatChange: (ExportFormat) -> Unit,
    onGroupingChange: (ExportGrouping) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onChooseDestination: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader(
            title = "Export audio",
            subtitle = state.voiceName?.let { "In the voice of $it" } ?: "Choose a voice first",
            onClose = onClose,
        )
        HairlineDivider(inset = 0.dp)

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 560.dp)) {
            when (val progress = state.progress) {
                is ExportProgress.Failed -> {
                    Box(modifier = Modifier.padding(Spacing.gutter)) {
                        InlineMessage(message = progress.reason, isError = true, icon = SoundboundIcons.Warning)
                    }
                }

                is ExportProgress.Finished -> {
                    Box(modifier = Modifier.padding(Spacing.gutter)) {
                        InlineMessage(
                            message = "Done. ${progress.files.size} file" +
                                (if (progress.files.size == 1) "" else "s") +
                                " written to ${state.destinationLabel}.",
                            icon = SoundboundIcons.Check,
                        )
                    }
                }

                else -> Unit
            }

            if (state.isRunning) {
                ExportRunning(progress = state.progress, onCancel = onCancel)
            } else {
                ExportOptions(
                    state = state,
                    onToggleChapter = onToggleChapter,
                    onSelectAll = onSelectAll,
                    onSelectNone = onSelectNone,
                    onFormatChange = onFormatChange,
                    onGroupingChange = onGroupingChange,
                    onBitrateChange = onBitrateChange,
                    onChooseDestination = onChooseDestination,
                    onStart = onStart,
                )
            }
        }
    }
}

@Composable
private fun ExportRunning(progress: ExportProgress?, onCancel: () -> Unit) {
    val rendering = progress as? ExportProgress.Rendering
    Column(
        modifier = Modifier.fillMaxWidth().padding(Spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DownloadRing(fraction = rendering?.fraction ?: 0f, diameter = 56.dp)
        Spacer(Modifier.height(Spacing.default))
        Text(
            text = rendering?.chapterTitle ?: "Preparing…",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = rendering?.let {
                "Chapter ${it.chapterIndex + 1} of ${it.chapterCount} · " +
                    "${(it.fraction * 100).toInt()}%"
            } ?: "Getting ready",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.default))
        Text(
            text = "Soundbound is synthesising as fast as the device allows — far quicker than " +
                "listening to it. You can leave this screen open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.default),
        )
        Spacer(Modifier.height(Spacing.large))
        SoundboundChip(label = "Stop", selected = false, onClick = onCancel)
        Spacer(Modifier.height(Spacing.large))
    }
}

@Composable
private fun ExportOptions(
    state: ExportSheetState,
    onToggleChapter: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onFormatChange: (ExportFormat) -> Unit,
    onGroupingChange: (ExportGrouping) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onChooseDestination: () -> Unit,
    onStart: () -> Unit,
) {
    SectionHeader("Format")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        ExportFormat.entries.forEach { format ->
            SoundboundChip(
                label = format.displayName,
                selected = state.format == format,
                onClick = { onFormatChange(format) },
            )
        }
    }

    if (state.format == ExportFormat.MP3) {
        Spacer(Modifier.height(Spacing.medium))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            listOf(48, 64, 96, 128).forEach { bitrate ->
                SoundboundChip(
                    label = "$bitrate kbps",
                    selected = state.mp3Settings.bitrateKbps == bitrate,
                    onClick = { onBitrateChange(bitrate) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = "64 kbps is ample for a single voice — about 28 MB an hour.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.small),
        )
    }

    SectionHeader("Files")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        ExportGrouping.entries.forEach { grouping ->
            SoundboundChip(
                label = grouping.displayName,
                selected = state.grouping == grouping,
                onClick = { onGroupingChange(grouping) },
            )
        }
    }

    SectionHeader(
        title = "Chapters",
        subtitle = "${state.selectedChapters.size} of ${state.chapterTitles.size} selected",
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                SoundboundChip(label = "All", selected = false, onClick = onSelectAll)
                SoundboundChip(label = "None", selected = false, onClick = onSelectNone)
            }
        },
    )

    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        state.chapterTitles.forEachIndexed { index, title ->
            SwitchRow(
                title = title.ifBlank { "Chapter ${index + 1}" },
                checked = index in state.selectedChapters,
                onCheckedChange = { onToggleChapter(index) },
            )
        }
    }

    SectionHeader("Destination")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChooseDestination)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SoundboundIcons.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.medium))
        Text(
            text = state.destinationLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(SoundboundIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }

    Spacer(Modifier.height(Spacing.large))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.Center,
    ) {
        SoundboundChip(
            label = if (state.selectedChapters.isEmpty()) "Choose chapters first" else "Export",
            selected = state.selectedChapters.isNotEmpty(),
            onClick = onStart,
            icon = SoundboundIcons.Download,
            enabled = state.selectedChapters.isNotEmpty() && state.voiceName != null,
        )
    }
    Spacer(Modifier.height(Spacing.large))
}
