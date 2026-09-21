package app.soundbound.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soundbound.core.prefs.AppTheme
import app.soundbound.core.prefs.HighlightStyle
import app.soundbound.core.prefs.Settings
import app.soundbound.core.prefs.SpeechSettings
import app.soundbound.ui.components.HairlineDivider
import app.soundbound.ui.components.LabelledSlider
import app.soundbound.ui.components.NavigationRow
import app.soundbound.ui.components.SectionHeader
import app.soundbound.ui.components.SoundboundChip
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.components.SwitchRow
import app.soundbound.ui.theme.Spacing

/** What the settings screen can ask for. */
data class SettingsActions(
    val onSettingsChange: (Settings) -> Unit,
    val onSpeechChange: (SpeechSettings) -> Unit,
    val onOpenAppearance: () -> Unit,
    val onOpenVoices: () -> Unit,
    val onOpenPronunciations: () -> Unit,
    val onExportSettings: () -> Unit,
    val onImportSettings: () -> Unit,
    val onResetSpeech: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenStorage: () -> Unit,
    val appVersion: String,
    val storageSummary: String,
)

/**
 * Settings.
 *
 * Ordered by how often each group is actually touched, not by how the code is organised: speech
 * first, because that is what people fiddle with, and the housekeeping at the bottom.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val speech = settings.speech

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.section,
        ),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.default)) {
                Text("Settings", style = MaterialTheme.typography.displaySmall)
            }
        }

        // ---------------------------------------------------------------- speech
        item { SectionHeader("Reading aloud") }
        item {
            NavigationRow(
                title = "Voices",
                subtitle = "Install and choose the voice that reads to you",
                icon = SoundboundIcons.Voices,
                onClick = actions.onOpenVoices,
            )
        }
        item {
            LabelledSlider(
                label = "Default speed",
                value = speech.rate,
                onValueChange = { actions.onSpeechChange(speech.copy(rate = it)) },
                valueRange = 0.5f..3f,
                valueLabel = { "%.2f×".format(it) },
                icon = SoundboundIcons.Speed,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.small),
            )
        }
        item {
            SwitchRow(
                title = "Read chapter headings",
                subtitle = "Announce each chapter title before its text",
                checked = speech.speakHeadings,
                onCheckedChange = { actions.onSpeechChange(speech.copy(speakHeadings = it)) },
            )
        }
        item {
            SwitchRow(
                title = "Read footnotes",
                subtitle = "Read each note after the paragraph that cites it, rather than skipping it",
                checked = speech.announceFootnotes,
                onCheckedChange = { actions.onSpeechChange(speech.copy(announceFootnotes = it)) },
            )
        }
        item {
            SwitchRow(
                title = "Describe images",
                subtitle = "Read the alternative text a publisher supplies with a figure",
                checked = speech.speakImageDescriptions,
                onCheckedChange = { actions.onSpeechChange(speech.copy(speakImageDescriptions = it)) },
            )
        }
        item {
            SwitchRow(
                title = "Continue into the next chapter",
                checked = speech.continueAcrossChapters,
                onCheckedChange = { actions.onSpeechChange(speech.copy(continueAcrossChapters = it)) },
            )
        }
        item {
            SwitchRow(
                title = "Follow along on the page",
                subtitle = "Scroll the reader to keep up with the narration",
                checked = speech.followWithReader,
                onCheckedChange = { actions.onSpeechChange(speech.copy(followWithReader = it)) },
            )
        }

        item { SectionHeader("Highlighting") }
        item {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                HighlightStyle.entries.forEach { style ->
                    SoundboundChip(
                        label = style.displayName,
                        selected = speech.highlight == style,
                        onClick = { actions.onSpeechChange(speech.copy(highlightStyle = style.name)) },
                    )
                }
            }
        }

        // ---------------------------------------------------------------- pronunciation
        item { SectionHeader("How words are said") }
        item {
            SwitchRow(
                title = "Expand numbers and dates",
                subtitle = "£12.50 as “twelve pounds fifty”, 1984 as “nineteen eighty-four”",
                checked = speech.expandNumbers,
                onCheckedChange = { actions.onSpeechChange(speech.copy(expandNumbers = it)) },
            )
        }
        item {
            SwitchRow(
                title = "Expand abbreviations",
                subtitle = "“Mr.” as “Mister”, “e.g.” as “for example”",
                checked = speech.expandAbbreviations,
                onCheckedChange = { actions.onSpeechChange(speech.copy(expandAbbreviations = it)) },
            )
        }
        item {
            SwitchRow(
                title = "Spell out acronyms",
                subtitle = "“BBC” letter by letter, but “NASA” as a word",
                checked = speech.spellOutAcronyms,
                onCheckedChange = { actions.onSpeechChange(speech.copy(spellOutAcronyms = it)) },
            )
        }
        item {
            SwitchRow(
                title = "Read web addresses",
                subtitle = "Off by default: a long URL read aloud is unbearable",
                checked = speech.readWebAddresses,
                onCheckedChange = { actions.onSpeechChange(speech.copy(readWebAddresses = it)) },
            )
        }
        item {
            NavigationRow(
                title = "Pronunciation corrections",
                subtitle = if (speech.pronunciationOverrides.isEmpty()) {
                    "Teach Soundbound a name it gets wrong"
                } else {
                    "${speech.pronunciationOverrides.size} saved"
                },
                icon = SoundboundIcons.Note,
                onClick = actions.onOpenPronunciations,
            )
        }

        item { SectionHeader("Pauses") }
        item {
            LabelledSlider(
                label = "After a sentence",
                value = speech.sentencePauseMillis.toFloat(),
                onValueChange = { actions.onSpeechChange(speech.copy(sentencePauseMillis = it.toInt())) },
                valueRange = 0f..800f,
                valueLabel = { "${it.toInt()} ms" },
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }
        item {
            LabelledSlider(
                label = "After a paragraph",
                value = speech.paragraphPauseMillis.toFloat(),
                onValueChange = { actions.onSpeechChange(speech.copy(paragraphPauseMillis = it.toInt())) },
                valueRange = 0f..2000f,
                valueLabel = { "${it.toInt()} ms" },
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }
        item {
            LabelledSlider(
                label = "After a heading",
                value = speech.chapterPauseMillis.toFloat(),
                onValueChange = { actions.onSpeechChange(speech.copy(chapterPauseMillis = it.toInt())) },
                valueRange = 0f..3000f,
                valueLabel = { "${it.toInt()} ms" },
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }

        // ---------------------------------------------------------------- appearance
        item { SectionHeader("Appearance") }
        item {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                AppTheme.entries.forEach { theme ->
                    SoundboundChip(
                        label = theme.displayName,
                        selected = settings.theme == theme,
                        onClick = { actions.onSettingsChange(settings.copy(appTheme = theme.name)) },
                    )
                }
            }
        }
        item {
            SwitchRow(
                title = "Use the system palette",
                subtitle = "Colour the app from your wallpaper, where the device supports it",
                checked = settings.useDynamicColour,
                onCheckedChange = { actions.onSettingsChange(settings.copy(useDynamicColour = it)) },
            )
        }
        item {
            NavigationRow(
                title = "Reading appearance",
                subtitle = "Type, spacing, margins and page colour",
                icon = SoundboundIcons.Typography,
                onClick = actions.onOpenAppearance,
            )
        }

        // ---------------------------------------------------------------- playback behaviour
        item { SectionHeader("Playback") }
        item {
            SwitchRow(
                title = "Duck for short sounds",
                subtitle = "Lower the volume rather than pausing when another app makes a brief noise",
                checked = speech.duckOnInterruption,
                onCheckedChange = { actions.onSpeechChange(speech.copy(duckOnInterruption = it)) },
            )
        }
        item {
            SwitchRow(
                title = "Resume with headphones",
                subtitle = "Carry on when headphones are plugged back in",
                checked = speech.resumeOnHeadphonesReconnected,
                onCheckedChange = {
                    actions.onSpeechChange(speech.copy(resumeOnHeadphonesReconnected = it))
                },
            )
        }

        // ---------------------------------------------------------------- housekeeping
        item { SectionHeader("Storage and backup") }
        item {
            NavigationRow(
                title = "Storage",
                subtitle = actions.storageSummary,
                icon = SoundboundIcons.Folder,
                onClick = actions.onOpenStorage,
            )
        }
        item {
            NavigationRow(
                title = "Export settings",
                subtitle = "Save your preferences as a file",
                icon = SoundboundIcons.Download,
                onClick = actions.onExportSettings,
            )
        }
        item {
            NavigationRow(
                title = "Import settings",
                icon = SoundboundIcons.Folder,
                onClick = actions.onImportSettings,
            )
        }
        item {
            NavigationRow(
                title = "Reset speech settings",
                subtitle = "Your pronunciation corrections are kept",
                icon = SoundboundIcons.Close,
                onClick = actions.onResetSpeech,
            )
        }

        item { HairlineDivider() }
        item {
            NavigationRow(
                title = "About Soundbound",
                subtitle = "Version ${actions.appVersion}",
                icon = SoundboundIcons.Info,
                onClick = actions.onOpenAbout,
            )
        }
        item {
            Column(modifier = Modifier.padding(Spacing.gutter)) {
                Text(
                    text = "Soundbound works with no network connection. Your books, your position, " +
                        "your notes and your voices stay on this device. Nothing is uploaded, and " +
                        "there is no account to make.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.large))
            }
        }
    }
}
