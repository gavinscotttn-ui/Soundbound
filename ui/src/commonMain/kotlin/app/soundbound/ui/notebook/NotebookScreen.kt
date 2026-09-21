package app.soundbound.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.soundbound.core.model.Book
import app.soundbound.core.model.BookId
import app.soundbound.core.model.Highlight
import app.soundbound.ui.components.EmptyState
import app.soundbound.ui.components.HairlineDivider
import app.soundbound.ui.components.SoundboundIconButton
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.reader.toColour
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.Spacing

/**
 * Everything the reader has marked, across the whole library.
 *
 * Grouped by book and newest first, because the usual reason for coming here is "what was that
 * line I marked last night". Tapping a highlight opens the book at it.
 */
@Composable
fun NotebookScreen(
    annotations: List<Pair<Book, Highlight>>,
    onOpenBook: (BookId) -> Unit,
    onDeleteHighlight: (BookId, String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (annotations.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = SoundboundIcons.Note,
                title = "Nothing marked yet",
                message = "Press and hold a paragraph while reading to highlight it. " +
                    "Everything you mark shows up here.",
            )
        }
        return
    }

    val grouped = annotations.groupBy { it.first.id }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.section,
        ),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.default)) {
                Text("Notes", style = MaterialTheme.typography.displaySmall)
                Text(
                    text = "${annotations.size} highlight" + (if (annotations.size == 1) "" else "s") +
                        " across ${grouped.size} book" + (if (grouped.size == 1) "" else "s"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        grouped.forEach { (bookId, entries) ->
            val book = entries.first().first
            item(key = "header-${bookId.value}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBook(bookId) }
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.metadata.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = book.metadata.authorLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${entries.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(items = entries, key = { it.second.id }) { (_, highlight) ->
                HighlightCard(
                    highlight = highlight,
                    onOpen = { onOpenBook(bookId) },
                    onDelete = { onDeleteHighlight(bookId, highlight.id) },
                )
            }

            item(key = "divider-${bookId.value}") { HairlineDivider() }
        }
    }
}

@Composable
private fun HighlightCard(
    highlight: Highlight,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.tiny),
        shape = SoundboundShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onOpen).padding(Spacing.medium),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .clip(SoundboundShapes.pill)
                    .background(highlight.colour.toColour()),
            )
            Spacer(Modifier.width(Spacing.medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlight.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                highlight.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.height(Spacing.small))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Spacing.tiny))
                Text(
                    text = "Chapter ${highlight.chapter.value + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SoundboundIconButton(
                icon = SoundboundIcons.Delete,
                contentDescription = "Delete this highlight",
                onClick = onDelete,
                size = 34.dp,
                iconSize = 16.dp,
            )
        }
    }
}
