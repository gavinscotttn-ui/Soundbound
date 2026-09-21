package app.soundbound.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.soundbound.core.library.LibraryEntry
import app.soundbound.core.library.LibraryFilter
import app.soundbound.core.library.LibrarySort
import app.soundbound.core.model.BookId
import app.soundbound.ui.components.BookCoverWithProgress
import app.soundbound.ui.components.BookInitials
import app.soundbound.ui.components.EmptyState
import app.soundbound.ui.components.HairlineDivider
import app.soundbound.ui.components.ProgressRing
import app.soundbound.ui.components.SoundboundChip
import app.soundbound.ui.components.SoundboundIconButton
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.theme.LocalAccents
import app.soundbound.ui.theme.Motion
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.Spacing

/** How the library is laid out. */
enum class LibraryLayout { GRID, LIST }

/** Everything the library screen needs, gathered by the caller. */
data class LibraryScreenState(
    val entries: List<LibraryEntry>,
    val allTags: List<String>,
    val sort: LibrarySort,
    val filter: LibraryFilter,
    val query: String,
    val activeTag: String?,
    val layout: LibraryLayout,
    val speakingBookId: BookId? = null,
    val isImporting: Boolean = false,
    val totalBookCount: Int = entries.size,
)

/** What the library screen can ask for. */
data class LibraryActions(
    val onOpenBook: (BookId) -> Unit,
    val onShowDetails: (BookId) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSortChange: (LibrarySort) -> Unit,
    val onFilterChange: (LibraryFilter) -> Unit,
    val onTagChange: (String?) -> Unit,
    val onLayoutChange: (LibraryLayout) -> Unit,
    val onImport: () -> Unit,
    val onToggleFavourite: (BookId, Boolean) -> Unit,
)

/**
 * The library.
 *
 * Covers first, and large. A library is a visual memory — people find a book by recognising its
 * spine, not by reading a list — so the grid is the default and the list is there for the
 * hundred-book case where titles matter more.
 */
@Composable
fun LibraryScreen(
    state: LibraryScreenState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var showFilters by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        LibraryHeader(
            state = state,
            actions = actions,
            filtersExpanded = showFilters,
            onToggleFilters = { showFilters = !showFilters },
            topPadding = contentPadding.calculateTopPadding(),
        )

        AnimatedVisibility(
            visible = showFilters,
            enter = fadeIn(Motion.quick()),
            exit = fadeOut(Motion.quick()),
        ) {
            LibraryFilterBar(state = state, actions = actions)
        }

        HairlineDivider(inset = 0.dp)

        when {
            state.entries.isEmpty() && state.totalBookCount == 0 -> EmptyLibrary(
                isImporting = state.isImporting,
                onImport = actions.onImport,
                modifier = Modifier.weight(1f),
            )

            state.entries.isEmpty() -> EmptyState(
                icon = SoundboundIcons.Search,
                title = "Nothing matches",
                message = if (state.query.isNotBlank()) {
                    "No book in your library matches “${state.query}”."
                } else {
                    "No book matches this filter."
                },
                modifier = Modifier.weight(1f),
                action = {
                    SoundboundChip(
                        label = "Clear filters",
                        selected = false,
                        onClick = {
                            actions.onQueryChange("")
                            actions.onFilterChange(LibraryFilter.ALL)
                            actions.onTagChange(null)
                        },
                    )
                },
            )

            state.layout == LibraryLayout.GRID -> BookGrid(
                state = state,
                actions = actions,
                bottomPadding = contentPadding.calculateBottomPadding(),
                modifier = Modifier.weight(1f),
            )

            else -> BookList(
                state = state,
                actions = actions,
                bottomPadding = contentPadding.calculateBottomPadding(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryHeader(
    state: LibraryScreenState,
    actions: LibraryActions,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.gutter, end = Spacing.small, top = Spacing.default),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = libraryCountLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SoundboundIconButton(
                icon = if (state.layout == LibraryLayout.GRID) SoundboundIcons.List else SoundboundIcons.Grid,
                contentDescription = if (state.layout == LibraryLayout.GRID) "Show as a list" else "Show as a grid",
                onClick = {
                    actions.onLayoutChange(
                        if (state.layout == LibraryLayout.GRID) LibraryLayout.LIST else LibraryLayout.GRID,
                    )
                },
            )
            SoundboundIconButton(
                icon = SoundboundIcons.Filter,
                contentDescription = if (filtersExpanded) "Hide filters" else "Show filters",
                onClick = onToggleFilters,
                tint = if (filtersExpanded) MaterialTheme.colorScheme.primary else null,
            )
            SoundboundIconButton(
                icon = SoundboundIcons.Add,
                contentDescription = "Add books",
                onClick = actions.onImport,
            )
        }

        SearchField(
            query = state.query,
            onQueryChange = actions.onQueryChange,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
        )
    }
}

private fun libraryCountLabel(state: LibraryScreenState): String {
    val shown = state.entries.size
    val total = state.totalBookCount
    return when {
        total == 0 -> "Nothing here yet"
        shown == total && total == 1 -> "One book"
        shown == total -> "$total books"
        else -> "$shown of $total books"
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text(
                        "Search titles, authors, series, tags",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(visible = query.isNotEmpty()) {
                SoundboundIconButton(
                    icon = SoundboundIcons.Close,
                    contentDescription = "Clear search",
                    onClick = { onQueryChange("") },
                    size = 28.dp,
                    iconSize = 15.dp,
                )
            }
        }
    }
}

@Composable
private fun LibraryFilterBar(state: LibraryScreenState, actions: LibraryActions) {
    Column(modifier = Modifier.padding(bottom = Spacing.small)) {
        ChipRow(label = "Show") {
            LibraryFilter.entries.forEach { filter ->
                SoundboundChip(
                    label = filter.displayName,
                    selected = state.filter == filter,
                    onClick = { actions.onFilterChange(filter) },
                )
            }
        }
        ChipRow(label = "Sort by") {
            LibrarySort.entries.forEach { sort ->
                SoundboundChip(
                    label = sort.displayName,
                    selected = state.sort == sort,
                    onClick = { actions.onSortChange(sort) },
                )
            }
        }
        if (state.allTags.isNotEmpty()) {
            ChipRow(label = "Tags") {
                SoundboundChip(
                    label = "Any",
                    selected = state.activeTag == null,
                    onClick = { actions.onTagChange(null) },
                )
                state.allTags.forEach { tag ->
                    SoundboundChip(
                        label = tag,
                        selected = state.activeTag == tag,
                        onClick = { actions.onTagChange(if (state.activeTag == tag) null else tag) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = Spacing.small)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.gutter, bottom = Spacing.tiny),
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            content()
        }
    }
}

@Composable
private fun BookGrid(
    state: LibraryScreenState,
    actions: LibraryActions,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        // A minimum cover width rather than a fixed column count, so the same code gives three
        // columns on a phone and seven on a desktop window without a breakpoint table.
        columns = GridCells.Adaptive(minSize = 116.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.gutter,
            end = Spacing.gutter,
            top = Spacing.default,
            bottom = bottomPadding + Spacing.section,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.default),
        verticalArrangement = Arrangement.spacedBy(Spacing.comfortable),
    ) {
        items(items = state.entries, key = { it.book.id.value }) { entry ->
            GridBookCell(
                entry = entry,
                isSpeaking = entry.book.id == state.speakingBookId,
                onOpen = { actions.onOpenBook(entry.book.id) },
                onDetails = { actions.onShowDetails(entry.book.id) },
            )
        }
    }
}

@Composable
private fun GridBookCell(
    entry: LibraryEntry,
    isSpeaking: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSpeaking) 1.02f else 1f,
        animationSpec = Motion.responsive(),
        label = "speakingLift",
    )

    Column(
        modifier = Modifier
            .clip(SoundboundShapes.small)
            .clickable(onClick = onOpen),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            BookCoverWithProgress(
                book = entry.book,
                progress = entry.progressFraction,
                isSpeaking = isSpeaking,
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(scale),
            )
        }
        Spacer(Modifier.height(Spacing.small))
        Text(
            text = entry.book.metadata.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.book.metadata.authorLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onDetails),
        )
    }
}

@Composable
private fun BookList(
    state: LibraryScreenState,
    actions: LibraryActions,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + Spacing.section),
    ) {
        items(items = state.entries, key = { it.book.id.value }) { entry ->
            ListBookRow(
                entry = entry,
                isSpeaking = entry.book.id == state.speakingBookId,
                onOpen = { actions.onOpenBook(entry.book.id) },
                onToggleFavourite = {
                    actions.onToggleFavourite(entry.book.id, !entry.book.isFavourite)
                },
            )
            HairlineDivider()
        }
    }
}

@Composable
private fun ListBookRow(
    entry: LibraryEntry,
    isSpeaking: Boolean,
    onOpen: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val accents = LocalAccents.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookInitials(
            title = entry.book.metadata.title,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(Spacing.default))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.book.metadata.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.book.metadata.authorLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                entry.book.metadata.series?.let { series ->
                    Text(
                        text = " · $series",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.small))
        ProgressRing(
            progress = entry.progressFraction.toFloat(),
            diameter = 30.dp,
            strokeWidth = 2.5.dp,
            colour = if (isSpeaking) accents.speaking else MaterialTheme.colorScheme.secondary,
        )
        SoundboundIconButton(
            icon = if (entry.book.isFavourite) SoundboundIcons.FavouriteFilled else SoundboundIcons.Favourite,
            contentDescription = if (entry.book.isFavourite) "Remove from favourites" else "Add to favourites",
            onClick = onToggleFavourite,
            size = 36.dp,
            iconSize = 18.dp,
            tint = if (entry.book.isFavourite) MaterialTheme.colorScheme.primary else null,
        )
    }
}

@Composable
private fun EmptyLibrary(isImporting: Boolean, onImport: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = SoundboundIcons.Library,
            title = "Your shelf is empty",
            message = "Add an EPUB, a PDF or a text file and Soundbound will read it to you — " +
                "with no connection, no account and nothing leaving the device.",
            action = {
                SoundboundChip(
                    label = if (isImporting) "Adding…" else "Add books",
                    selected = true,
                    onClick = onImport,
                    icon = SoundboundIcons.Add,
                    enabled = !isImporting,
                )
            },
        )
    }
}
