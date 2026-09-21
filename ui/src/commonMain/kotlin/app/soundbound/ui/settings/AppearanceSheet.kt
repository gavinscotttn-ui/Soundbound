package app.soundbound.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundbound.core.prefs.PageTurn
import app.soundbound.core.prefs.ReaderSettings
import app.soundbound.core.prefs.ReaderTheme
import app.soundbound.core.prefs.ReadingFont
import app.soundbound.core.prefs.TypographySettings
import app.soundbound.ui.components.LabelledSlider
import app.soundbound.ui.components.SectionHeader
import app.soundbound.ui.components.SoundboundChip
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.components.SwitchRow
import app.soundbound.ui.player.SheetHeader
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.Spacing
import app.soundbound.ui.theme.platformReadingFont
import app.soundbound.ui.theme.readerThemeOptions

/**
 * The reader's appearance.
 *
 * Every control changes the page behind the sheet immediately, which is why there is no preview
 * pane: the book itself is the preview, and a separate sample of Lorem Ipsum would be a worse one.
 */
@Composable
fun AppearanceSheet(
    typography: TypographySettings,
    reader: ReaderSettings,
    onTypographyChange: (TypographySettings) -> Unit,
    onReaderChange: (ReaderSettings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetHeader("Appearance", "Changes apply to the page behind this sheet", onClose)

        SectionHeader("Page")
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            readerThemeOptions.forEach { (theme, palette) ->
                ThemeSwatch(
                    label = theme.displayName,
                    background = palette.background,
                    text = palette.text,
                    selected = reader.readerTheme == theme,
                    onClick = { onReaderChange(reader.copy(theme = theme.name)) },
                )
            }
            ThemeSwatch(
                label = "Match app",
                background = MaterialTheme.colorScheme.surface,
                text = MaterialTheme.colorScheme.onSurface,
                selected = reader.readerTheme == ReaderTheme.FOLLOW_APP,
                onClick = { onReaderChange(reader.copy(theme = ReaderTheme.FOLLOW_APP.name)) },
            )
        }

        SectionHeader("Type")
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            ReadingFont.entries.forEach { font ->
                FontChip(
                    font = font,
                    selected = typography.readingFont == font,
                    onClick = { onTypographyChange(typography.copy(font = font.name)) },
                )
            }
        }

        Spacer(Modifier.height(Spacing.medium))
        LabelledSlider(
            label = "Text size",
            value = typography.fontSizeSp,
            onValueChange = { onTypographyChange(typography.copy(fontSizeSp = it)) },
            valueRange = 12f..40f,
            valueLabel = { "${it.toInt()} pt" },
            icon = SoundboundIcons.Typography,
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        LabelledSlider(
            label = "Line spacing",
            value = typography.lineHeightMultiplier,
            onValueChange = { onTypographyChange(typography.copy(lineHeightMultiplier = it)) },
            valueRange = 1.1f..2.6f,
            valueLabel = { "%.2f".format(it) },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        LabelledSlider(
            label = "Space between paragraphs",
            value = typography.paragraphSpacingMultiplier,
            onValueChange = { onTypographyChange(typography.copy(paragraphSpacingMultiplier = it)) },
            valueRange = 0f..2.5f,
            valueLabel = { "%.1f".format(it) },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        LabelledSlider(
            label = "Side margins",
            value = typography.pageMarginDp,
            onValueChange = { onTypographyChange(typography.copy(pageMarginDp = it)) },
            valueRange = 0f..72f,
            valueLabel = { "${it.toInt()}" },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        LabelledSlider(
            label = "Line length",
            value = typography.maxLineLengthCharacters.toFloat(),
            onValueChange = { onTypographyChange(typography.copy(maxLineLengthCharacters = it.toInt())) },
            valueRange = 40f..120f,
            valueLabel = { "${it.toInt()} characters" },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        LabelledSlider(
            label = "Letter spacing",
            value = typography.letterSpacingEm,
            onValueChange = { onTypographyChange(typography.copy(letterSpacingEm = it)) },
            valueRange = -0.02f..0.2f,
            valueLabel = { "%.2f".format(it) },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )

        SectionHeader("Layout")
        SwitchRow(
            title = "Justify text",
            subtitle = "Straight right edge, as in print. Can open up gaps on narrow screens.",
            checked = typography.justifyText,
            onCheckedChange = { onTypographyChange(typography.copy(justifyText = it)) },
        )
        SwitchRow(
            title = "Indent paragraphs",
            subtitle = "First-line indents instead of spacing between paragraphs",
            checked = typography.indentParagraphs,
            onCheckedChange = { onTypographyChange(typography.copy(indentParagraphs = it)) },
        )
        SwitchRow(
            title = "Heavier text",
            subtitle = "A slightly bolder weight, which helps on a bright screen",
            checked = typography.boldText,
            onCheckedChange = { onTypographyChange(typography.copy(boldText = it)) },
        )

        SectionHeader("While reading")
        SwitchRow(
            title = "Keep the screen on",
            checked = reader.keepScreenOn,
            onCheckedChange = { onReaderChange(reader.copy(keepScreenOn = it)) },
        )
        SwitchRow(
            title = "Show the progress bar",
            checked = reader.showProgressBar,
            onCheckedChange = { onReaderChange(reader.copy(showProgressBar = it)) },
        )
        SwitchRow(
            title = "Show the chapter title",
            checked = reader.showChapterTitle,
            onCheckedChange = { onReaderChange(reader.copy(showChapterTitle = it)) },
        )
        SwitchRow(
            title = "Volume keys turn the page",
            checked = reader.volumeKeysTurnPages,
            onCheckedChange = { onReaderChange(reader.copy(volumeKeysTurnPages = it)) },
        )

        Spacer(Modifier.height(Spacing.default))
        Row(modifier = Modifier.padding(horizontal = Spacing.gutter)) {
            SoundboundChip(label = "Reset appearance", selected = false, onClick = onReset)
        }
        Spacer(Modifier.height(Spacing.large))
    }
}

@Composable
private fun ThemeSwatch(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    text: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 54.dp, height = 68.dp)
                .clip(SoundboundShapes.small)
                .background(background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = SoundboundShapes.small,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Three short rules standing in for a page of text: a swatch of the background alone
            // tells you nothing about contrast, which is the thing actually being chosen.
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .height(3.dp)
                            .width(if (index == 3) 18.dp else 30.dp)
                            .clip(SoundboundShapes.pill)
                            .background(text.copy(alpha = 0.75f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.tiny))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FontChip(font: ReadingFont, selected: Boolean, onClick: () -> Unit) {
    val family = platformReadingFont(font)
    Box(
        modifier = Modifier
            .clip(SoundboundShapes.chip)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                shape = SoundboundShapes.chip,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Text(
            text = font.displayName,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = family,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
