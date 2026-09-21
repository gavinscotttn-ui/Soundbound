package app.soundbound.ui.app

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.soundbound.core.library.LibraryEntry
import app.soundbound.core.model.VoiceId
import app.soundbound.core.session.SearchHit
import app.soundbound.core.session.SearchProgress
import app.soundbound.core.tts.TtsVoice
import app.soundbound.ui.components.BookCover
import app.soundbound.ui.components.EmptyState
import app.soundbound.ui.components.HairlineDivider
import app.soundbound.ui.components.ProgressRing
import app.soundbound.ui.components.SectionHeader
import app.soundbound.ui.components.SlimProgressBar
import app.soundbound.ui.components.SoundboundChip
import app.soundbound.ui.components.SoundboundIconButton
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.player.SheetHeader
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.SoundboundType
import app.soundbound.ui.theme.Spacing
import app.soundbound.ui.voices.formatSize

/**
 * A book's own page: everything known about it, and what can be done with it.
 *
 * Reached from the author line in the library rather than from a menu, because that is where a
 * reader's finger already is when they want to know more about a book.
 */
@Composable
fun BookDetailsScreen(
    entry: LibraryEntry?,
    onOpen: () -> Unit,
    onBack: () -> Unit,
    onToggleFavourite: (Boolean) -> Unit,
    onToggleFinished: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (entry == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = SoundboundIcons.Warning,
                title = "Not found",
                message = "That book is no longer in your library.",
                action = { SoundboundChip(label = "Back", selected = false, onClick = onBack) },
            )
        }
        return
    }

    val book = entry.book

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + Spacing.section,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.small, vertical = Spacing.small),
        ) {
            SoundboundIconButton(SoundboundIcons.Back, "Back", onBack)
            Spacer(Modifier.weight(1f))
            SoundboundIconButton(
                icon = if (book.isFavourite) SoundboundIcons.FavouriteFilled else SoundboundIcons.Favourite,
                contentDescription = if (book.isFavourite) "Remove from favourites" else "Add to favourites",
                onClick = { onToggleFavourite(!book.isFavourite) },
                tint = if (book.isFavourite) MaterialTheme.colorScheme.primary else null,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BookCover(
                book = book,
                modifier = Modifier.widthIn(max = 180.dp).fillMaxWidth(0.45f),
                shape = SoundboundShapes.small,
            )
            Spacer(Modifier.height(Spacing.comfortable))
            Text(
                text = book.metadata.title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.tiny))
            Text(
                text = book.metadata.authorLine,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            book.metadata.series?.let { series ->
                Text(
                    text = series + (book.metadata.seriesIndex?.let { " · book ${it.toInt()}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Spacing.comfortable))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                ProgressRing(progress = entry.progressFraction.toFloat(), diameter = 44.dp)
                Column {
                    Text(
                        text = "${(entry.progressFraction * 100).toInt()}% read",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Chapter ${entry.position.chapter.value + 1} of ${book.chapterCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.comfortable))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                SoundboundChip(
                    label = if (entry.hasStarted) "Continue" else "Start reading",
                    selected = true,
                    onClick = onOpen,
                    icon = SoundboundIcons.Reader,
                )
                SoundboundChip(
                    label = if (book.isFinished) "Finished" else "Mark finished",
                    selected = book.isFinished,
                    onClick = { onToggleFinished(!book.isFinished) },
                    icon = SoundboundIcons.Check,
                )
            }
        }

        book.metadata.description?.takeIf { it.isNotBlank() }?.let { description ->
            SectionHeader("About")
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }

        SectionHeader("Details")
        DetailRow("Format", book.format.displayName)
        DetailRow("Chapters", book.chapterCount.toString())
        DetailRow("Size", formatSize(book.fileSizeBytes))
        book.metadata.language?.let { DetailRow("Language", it) }
        book.metadata.publisher?.let { DetailRow("Publisher", it) }
        book.metadata.publishedDate?.let { DetailRow("Published", it) }
        DetailRow("Bookmarks", entry.bookmarks.size.toString())
        DetailRow("Highlights", entry.highlights.size.toString())
        if (book.tags.isNotEmpty()) DetailRow("Tags", book.tags.sorted().joinToString(", "))

        SectionHeader("Manage")
        Row(modifier = Modifier.padding(horizontal = Spacing.gutter)) {
            SoundboundChip(
                label = "Remove from library",
                selected = false,
                onClick = onRemove,
                icon = SoundboundIcons.Delete,
            )
        }
        Text(
            text = "The file itself is left where it is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.small),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.small),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A quick voice switch, for changing narrator without leaving the player. */
@Composable
fun VoicePickerSheet(
    voices: List<TtsVoice>,
    selectedId: VoiceId?,
    onSelect: (TtsVoice) -> Unit,
    onOpenStore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader("Voice", "${voices.size} installed", onClose)
        HairlineDivider(inset = 0.dp)

        if (voices.isEmpty()) {
            EmptyState(
                icon = SoundboundIcons.Voices,
                title = "No voices installed",
                message = "Soundbound needs at least one voice to read aloud.",
                action = { SoundboundChip(label = "Open the voice store", selected = true, onClick = onOpenStore) },
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
            items(items = voices, key = { it.id.value }) { voice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(voice) }
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        if (voice.id == selectedId) {
                            Icon(
                                SoundboundIcons.Check,
                                contentDescription = "In use",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.medium))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = voice.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${voice.language} · ${voice.quality.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(modifier = Modifier.padding(Spacing.gutter)) {
                    SoundboundChip(
                        label = "More voices",
                        selected = false,
                        onClick = onOpenStore,
                        icon = SoundboundIcons.Download,
                    )
                }
            }
        }
    }
}

/**
 * Search within the open book.
 *
 * Results appear as each chapter is searched rather than at the end, because a long PDF takes
 * several seconds and a search that shows nothing for five seconds looks broken.
 */
@Composable
fun SearchInBookSheet(
    query: String,
    progress: SearchProgress?,
    onQueryChange: (String) -> Unit,
    onSelect: (SearchHit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader(
            title = "Search this book",
            subtitle = progress?.let { p ->
                if (p.isComplete) {
                    "${p.hits.size} result" + (if (p.hits.size == 1) "" else "s")
                } else {
                    "Searching… ${p.hits.size} so far"
                }
            },
            onClose = onClose,
        )

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.small),
            shape = SoundboundShapes.pill,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.default, vertical = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    SoundboundIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Spacing.medium))
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (query.isEmpty()) {
                        Text(
                            "A word or a phrase",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (progress != null && !progress.isComplete) {
            SlimProgressBar(
                progress = progress.fraction,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }

        val hits = progress?.hits.orEmpty()
        if (hits.isEmpty() && progress?.isComplete == true && query.length >= 2) {
            EmptyState(
                icon = SoundboundIcons.Search,
                title = "Nothing found",
                message = "“$query” does not appear in this book.",
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(items = hits, key = { "${it.chapter.value}-${it.characterOffset}" }) { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(hit) }
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
                ) {
                    Text(
                        text = hit.chapterTitle ?: "Chapter ${hit.chapter.value + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(Spacing.tiny))
                    Text(
                        text = highlightMatch(hit),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HairlineDivider()
            }
        }
    }
}

/** Emboldens the matched text inside a search snippet. */
private fun highlightMatch(hit: SearchHit): AnnotatedString = buildAnnotatedString {
    append(hit.snippet)
    val from = hit.matchInSnippet.first.coerceIn(0, hit.snippet.length)
    val to = (hit.matchInSnippet.last + 1).coerceIn(from, hit.snippet.length)
    if (to > from) addStyle(SpanStyle(fontWeight = FontWeight.Bold), from, to)
}
