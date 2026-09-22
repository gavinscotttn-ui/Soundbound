package app.soundbound.ui.voices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.soundbound.core.model.VoiceId
import app.soundbound.core.tts.EngineKind
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.tts.VoiceGender
import app.soundbound.core.tts.VoiceQuality
import app.soundbound.core.voices.CatalogVoice
import app.soundbound.core.voices.InstallProgress
import app.soundbound.ui.components.DownloadRing
import app.soundbound.ui.components.EmptyState
import app.soundbound.ui.components.HairlineDivider
import app.soundbound.ui.components.InlineMessage
import app.soundbound.ui.components.SectionHeader
import app.soundbound.ui.components.SoundboundChip
import app.soundbound.ui.components.SoundboundIconButton
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.theme.LocalAccents
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.SoundboundType
import app.soundbound.ui.theme.Spacing

/** What the voices screen is showing. */
data class VoicesScreenState(
    val installed: List<TtsVoice>,
    val selectedVoiceId: VoiceId?,
    val catalogue: List<CatalogVoice>,
    val installedKeys: Set<String>,
    val installing: Map<String, InstallProgress>,
    val isLoadingCatalogue: Boolean = false,
    val catalogueError: String? = null,
    val languageFilter: String? = null,
    val availableLanguages: List<Pair<String, Int>> = emptyList(),
    val previewingVoiceId: VoiceId? = null,
)

/** What the voices screen can ask for. */
data class VoicesActions(
    val onSelectVoice: (TtsVoice) -> Unit,
    val onPreviewVoice: (TtsVoice) -> Unit,
    val onInstall: (CatalogVoice) -> Unit,
    val onCancelInstall: (CatalogVoice) -> Unit,
    /** Removing an installed voice, which each engine stores differently. */
    val onUninstallVoice: (TtsVoice) -> Unit,
    /** Removing a voice-store entry, which is always a Piper folder. */
    val onUninstallCatalogue: (String) -> Unit,
    val onRefreshCatalogue: () -> Unit,
    val onLanguageFilter: (String?) -> Unit,
    val onImportVoiceFile: () -> Unit,
)

/**
 * Voices.
 *
 * Two lists, in this order: the voices on the device, then the ones that can be fetched. The
 * distinction matters more here than in most stores, because everything above the fold works with
 * the aeroplane mode on and everything below it needs a connection exactly once.
 */
@Composable
fun VoicesScreen(
    state: VoicesScreenState,
    actions: VoicesActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.section,
        ),
    ) {
        item {
            Column(modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.default)) {
                Text("Voices", style = MaterialTheme.typography.displaySmall)
                Text(
                    text = "Everything here runs on the device. Nothing you read is ever sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionHeader(
                title = "On this device",
                subtitle = "${state.installed.size} ready to use",
                action = {
                    SoundboundIconButton(
                        icon = SoundboundIcons.Folder,
                        contentDescription = "Add a voice from a file",
                        onClick = actions.onImportVoiceFile,
                    )
                },
            )
        }

        if (state.installed.isEmpty()) {
            item {
                EmptyState(
                    icon = SoundboundIcons.Voices,
                    title = "No voices yet",
                    message = "Install one below, or add a Piper model you already have.",
                )
            }
        } else {
            items(items = state.installed, key = { it.id.value }) { voice ->
                InstalledVoiceRow(
                    voice = voice,
                    isSelected = voice.id == state.selectedVoiceId,
                    isPreviewing = voice.id == state.previewingVoiceId,
                    onSelect = { actions.onSelectVoice(voice) },
                    onPreview = { actions.onPreviewVoice(voice) },
                    onUninstall = { actions.onUninstallVoice(voice) },
                )
                HairlineDivider()
            }
        }

        item {
            SectionHeader(
                title = "Voice store",
                subtitle = if (state.catalogue.isEmpty()) {
                    "Hundreds of voices across more than forty languages"
                } else {
                    "${state.catalogue.size} voices available"
                },
                action = {
                    SoundboundIconButton(
                        icon = SoundboundIcons.Download,
                        contentDescription = "Refresh the voice list",
                        onClick = actions.onRefreshCatalogue,
                    )
                },
            )
        }

        state.catalogueError?.let { error ->
            item {
                Box(modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.small)) {
                    InlineMessage(
                        message = error,
                        isError = true,
                        icon = SoundboundIcons.Offline,
                        actionLabel = "Try again",
                        onAction = actions.onRefreshCatalogue,
                    )
                }
            }
        }

        if (state.availableLanguages.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    SoundboundChip(
                        label = "All languages",
                        selected = state.languageFilter == null,
                        onClick = { actions.onLanguageFilter(null) },
                    )
                    state.availableLanguages.forEach { (language, count) ->
                        SoundboundChip(
                            label = "$language ($count)",
                            selected = state.languageFilter == language,
                            onClick = {
                                actions.onLanguageFilter(if (state.languageFilter == language) null else language)
                            },
                        )
                    }
                }
            }
        }

        if (state.isLoadingCatalogue) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.section),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Fetching the voice list…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(items = state.catalogue, key = { it.key }) { entry ->
            CatalogueVoiceRow(
                entry = entry,
                isInstalled = entry.key in state.installedKeys,
                progress = state.installing[entry.key],
                onInstall = { actions.onInstall(entry) },
                onCancel = { actions.onCancelInstall(entry) },
                onUninstall = { actions.onUninstallCatalogue(entry.key) },
            )
            HairlineDivider()
        }
    }
}

@Composable
private fun InstalledVoiceRow(
    voice: TtsVoice,
    isSelected: Boolean,
    isPreviewing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onUninstall: () -> Unit,
) {
    val accents = LocalAccents.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    SoundboundIcons.Check,
                    contentDescription = "In use",
                    tint = accents.speaking,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    SoundboundIcons.Voices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(Spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = voice.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = voiceSubtitle(voice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SoundboundIconButton(
            icon = if (isPreviewing) SoundboundIcons.Pause else SoundboundIcons.Play,
            contentDescription = "Hear this voice",
            onClick = onPreview,
            size = 38.dp,
            iconSize = 17.dp,
        )
        if (voice.engine != EngineKind.SYSTEM) {
            SoundboundIconButton(
                icon = SoundboundIcons.Delete,
                contentDescription = "Remove this voice",
                onClick = onUninstall,
                size = 38.dp,
                iconSize = 17.dp,
            )
        }
    }
}

private fun voiceSubtitle(voice: TtsVoice): String {
    val parts = mutableListOf<String>()
    parts.add(voice.language)
    if (voice.gender != VoiceGender.UNSPECIFIED) {
        parts.add(
            when (voice.gender) {
                VoiceGender.FEMININE -> "Female"
                VoiceGender.MASCULINE -> "Male"
                VoiceGender.NEUTRAL -> "Neutral"
                VoiceGender.UNSPECIFIED -> ""
            },
        )
    }
    parts.add(voice.quality.displayName)
    parts.add(voice.engine.displayName)
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun CatalogueVoiceRow(
    entry: CatalogVoice,
    isInstalled: Boolean,
    progress: InstallProgress?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.quality == VoiceQuality.HIGH) {
                    Spacer(Modifier.width(Spacing.small))
                    QualityBadge()
                }
            }
            Text(
                text = buildString {
                    append(entry.localeDescription)
                    append(" · ")
                    append(entry.quality.displayName)
                    if (entry.numSpeakers > 1) append(" · ${entry.numSpeakers} speakers")
                    append(" · ")
                    append(formatSize(entry.totalBytes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.medium))

        when {
            progress is InstallProgress.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadRing(fraction = progress.fraction)
                Spacer(Modifier.width(Spacing.small))
                SoundboundIconButton(
                    icon = SoundboundIcons.Close,
                    contentDescription = "Cancel the download",
                    onClick = onCancel,
                    size = 34.dp,
                    iconSize = 15.dp,
                )
            }

            progress is InstallProgress.Verifying -> Text(
                text = "Checking…",
                style = SoundboundType.monoNumerals,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            isInstalled -> SoundboundIconButton(
                icon = SoundboundIcons.Delete,
                contentDescription = "Remove this voice",
                onClick = onUninstall,
                size = 38.dp,
                iconSize = 17.dp,
            )

            else -> SoundboundIconButton(
                icon = SoundboundIcons.Download,
                contentDescription = "Install this voice",
                onClick = onInstall,
                size = 38.dp,
                iconSize = 18.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QualityBadge() {
    Surface(
        shape = SoundboundShapes.pill,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = "High",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.small, vertical = 2.dp),
        )
    }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
