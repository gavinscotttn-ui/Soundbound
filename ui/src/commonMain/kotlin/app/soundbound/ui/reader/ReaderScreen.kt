package app.soundbound.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundbound.core.book.BlockKind
import app.soundbound.core.book.ContentBlock
import app.soundbound.core.model.Highlight
import app.soundbound.core.prefs.HighlightStyle
import app.soundbound.core.prefs.ReadingFont
import app.soundbound.core.prefs.TypographySettings
import app.soundbound.core.session.ReaderState
import app.soundbound.ui.components.ShimmerBox
import app.soundbound.ui.components.SlimProgressBar
import app.soundbound.ui.components.SoundboundIconButton
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.components.decodeImageBytes
import app.soundbound.ui.theme.LocalReaderPalette
import app.soundbound.ui.theme.SoundboundShapes
import app.soundbound.ui.theme.LocalHaptics
import app.soundbound.ui.theme.Spacing
import app.soundbound.ui.theme.platformReadingFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** What the reader is being asked to show. */
data class ReaderScreenState(
    val reader: ReaderState,
    val bookTitle: String,
    val typography: TypographySettings,
    val highlightStyle: HighlightStyle,
    val showProgressBar: Boolean,
    val showChapterTitle: Boolean,
    /** Chapter-relative range currently being spoken, if read-aloud is running. */
    val speakingRange: IntRange? = null,
    val speakingWordRange: IntRange? = null,
    val isSpeaking: Boolean = false,
    val followNarration: Boolean = true,
    val bookProgress: Float = 0f,
)

/** What the reader can ask for. */
data class ReaderActions(
    val onBack: () -> Unit,
    val onShowContents: () -> Unit,
    val onShowAppearance: () -> Unit,
    val onShowSearch: () -> Unit,
    val onShowBookmarks: () -> Unit,
    val onAddBookmark: () -> Unit,
    val onOpenPlayer: () -> Unit,
    val onTogglePlayback: () -> Unit,
    val onPositionChanged: (Int) -> Unit,
    val onScrollHandled: () -> Unit,
    val onFollowLink: (String) -> Unit,
    val onShowNote: (String) -> Unit,
    val onHighlightBlock: (ContentBlock) -> Unit,
    val onLoadImage: (String) -> ByteArray?,
    val onNextChapter: () -> Unit,
    val onPreviousChapter: () -> Unit,
)

/**
 * The reading surface.
 *
 * Deliberately almost nothing but the text. The chrome hides on a tap and stays hidden; the page
 * uses its own palette rather than the app's; and everything measurable — margin, leading,
 * measure, type size — comes from the user's settings rather than being baked in.
 */
@Composable
fun ReaderScreen(
    state: ReaderScreenState,
    actions: ReaderActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val palette = LocalReaderPalette.current
    val listState = rememberLazyListState()
    var chromeVisible by remember { mutableStateOf(false) }
    val fontFamily = platformReadingFont(state.typography.readingFont)

    val content = state.reader.content

    // Honour a jump: a table-of-contents tap, a search result, or resuming where the voice got to.
    LaunchedEffect(state.reader.pendingScrollOffset, content) {
        val offset = state.reader.pendingScrollOffset ?: return@LaunchedEffect
        val blocks = content?.blocks ?: return@LaunchedEffect
        val index = blocks.indexOfFirst { it.textEnd > offset }.coerceAtLeast(0)
        listState.scrollToItem(index)
        actions.onScrollHandled()
    }

    // Keep the page with the narration, but only when it has drifted off screen. Scrolling on
    // every sentence would fight the reader's own thumb.
    LaunchedEffect(state.speakingRange, state.followNarration) {
        if (!state.followNarration || !state.isSpeaking) return@LaunchedEffect
        val range = state.speakingRange ?: return@LaunchedEffect
        val blocks = content?.blocks ?: return@LaunchedEffect
        val target = blocks.indexOfFirst { it.textEnd > range.first }.coerceAtLeast(0)
        val visible = listState.layoutInfo.visibleItemsInfo
        val isVisible = visible.any { it.index == target }
        if (!isVisible) {
            listState.animateScrollToItem(target.coerceAtLeast(0))
        }
    }

    // Save where the reader has got to, throttled: this fires on every frame of a scroll.
    LaunchedEffect(listState, content) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(400)
            .collect { index ->
                val blocks = content?.blocks ?: return@collect
                blocks.getOrNull(index)?.let { actions.onPositionChanged(it.textStart) }
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        when {
            state.reader.isLoading && content == null -> LoadingChapter()
            state.reader.errorMessage != null && content == null -> ChapterError(
                message = state.reader.errorMessage!!,
                onBack = actions.onBack,
            )

            content != null -> ReaderPages(
                blocks = content.blocks,
                highlights = state.reader.highlights,
                listState = listState,
                state = state,
                actions = actions,
                fontFamily = fontFamily,
                contentPadding = contentPadding,
                onTap = { chromeVisible = !chromeVisible },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(state = state, actions = actions, topPadding = contentPadding.calculateTopPadding())
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                state = state,
                actions = actions,
                bottomPadding = contentPadding.calculateBottomPadding(),
            )
        }

        if (state.showProgressBar && !chromeVisible) {
            SlimProgressBar(
                progress = state.bookProgress,
                height = 2.dp,
                colour = palette.muted.copy(alpha = 0.6f),
                trackColour = Color.Transparent,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

@Composable
private fun ReaderPages(
    blocks: List<ContentBlock>,
    highlights: List<Highlight>,
    listState: LazyListState,
    state: ReaderScreenState,
    actions: ReaderActions,
    fontFamily: FontFamily,
    contentPadding: PaddingValues,
    onTap: () -> Unit,
) {
    val typography = state.typography

    // A measure limit in characters rather than a fixed width: 60–75 characters per line is the
    // classic typographic range, and honouring it is what stops a desktop window from producing
    // unreadably long lines.
    val maxWidth = remember(typography.maxLineLengthCharacters, typography.fontSizeSp) {
        // About 0.5 em per character for a typical serif at reading sizes.
        (typography.maxLineLengthCharacters * typography.fontSizeSp * 0.52f).dp
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + Spacing.section,
            bottom = contentPadding.calculateBottomPadding() + Spacing.generous,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(items = blocks, key = { index, block -> "${block.textStart}-$index" }) { index, block ->
            val previous = blocks.getOrNull(index - 1)
            val spacing = blockSpacingBefore(block, previous) *
                typography.paragraphSpacingMultiplier *
                typography.fontSizeSp

            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = typography.pageMarginDp.dp),
            ) {
                if (spacing > 0f) Spacer(Modifier.height(spacing.dp))
                BlockView(
                    block = block,
                    highlights = highlightsFor(block, highlights),
                    state = state,
                    actions = actions,
                    fontFamily = fontFamily,
                )
            }
        }

        item {
            ChapterFooter(
                state = state,
                onNext = actions.onNextChapter,
                onPrevious = actions.onPreviousChapter,
                maxWidth = maxWidth,
            )
        }
    }
}

@Composable
private fun BlockView(
    block: ContentBlock,
    highlights: List<Highlight>,
    state: ReaderScreenState,
    actions: ReaderActions,
    fontFamily: FontFamily,
) {
    val palette = LocalReaderPalette.current

    when (block.kind) {
        BlockKind.SEPARATOR -> SceneBreak()
        BlockKind.PAGE_BREAK -> Spacer(Modifier.height(Spacing.small))
        BlockKind.IMAGE -> BlockImage(block = block, actions = actions)
        BlockKind.TABLE -> BlockTable(block = block, fontFamily = fontFamily, state = state)

        else -> {
            val text = buildBlockText(
                block = block,
                palette = palette,
                highlights = highlights,
                speakingRange = state.speakingRange,
                speakingWordRange = state.speakingWordRange,
                highlightStyle = state.highlightStyle,
            )
            val style = blockTextStyle(block, state.typography, palette, fontFamily)

            Row(modifier = Modifier.fillMaxWidth()) {
                if (block.indentLevel > 0) {
                    Spacer(Modifier.width((block.indentLevel * 16).dp))
                }
                if (block.kind == BlockKind.BLOCKQUOTE) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height((state.typography.fontSizeSp * state.typography.lineHeightMultiplier).dp)
                            .background(palette.rule),
                    )
                    Spacer(Modifier.width(Spacing.medium))
                }
                if (block.listMarker != null) {
                    Text(
                        text = block.listMarker!!,
                        style = style.copy(color = palette.muted),
                        modifier = Modifier.padding(end = Spacing.small),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    SelectableBlockText(
                        text = text,
                        style = style,
                        block = block,
                        actions = actions,
                    )
                }
            }
        }
    }
}

/**
 * The text itself, with taps routed to links and footnotes and a long press offering to highlight.
 *
 * Character-accurate text selection needs platform text toolbars that differ between Android and
 * the desktop; long-press-to-highlight works identically on both and is what people reach for
 * anyway while listening.
 */
@Composable
private fun SelectableBlockText(
    text: androidx.compose.ui.text.AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    block: ContentBlock,
    actions: ReaderActions,
) {
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    Text(
        text = text,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(text) {
                detectTapGestures(
                    onTap = { position -> handleTap(position, layout, text, actions) },
                    onLongPress = { actions.onHighlightBlock(block) },
                )
            },
    )
}

private fun handleTap(
    position: Offset,
    layout: androidx.compose.ui.text.TextLayoutResult?,
    text: androidx.compose.ui.text.AnnotatedString,
    actions: ReaderActions,
) {
    val result = layout ?: return
    val offset = result.getOffsetForPosition(position)
    text.getStringAnnotations(ANNOTATION_NOTE, offset, offset).firstOrNull()?.let {
        actions.onShowNote(it.item)
        return
    }
    text.getStringAnnotations(ANNOTATION_LINK, offset, offset).firstOrNull()?.let {
        actions.onFollowLink(it.item)
    }
}

/** The typographic break publishers use between scenes: three spaced marks, centred. */
@Composable
private fun SceneBreak() {
    val palette = LocalReaderPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.comfortable),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "•   •   •",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BlockImage(block: ContentBlock, actions: ReaderActions) {
    val palette = LocalReaderPalette.current
    val ref = block.imageRef ?: return
    var bitmap by remember(ref) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(ref) { mutableStateOf(false) }

    LaunchedEffect(ref) {
        // Both the read and the decode are off the main thread: a full-page scan inside an EPUB
        // can be several megabytes, and doing this inline drops frames on every image.
        val decoded = withContext(Dispatchers.IO) {
            val bytes = actions.onLoadImage(ref) ?: return@withContext null
            decodeImageBytes(bytes, targetWidthPixels = 1_400)
        }
        bitmap = decoded
        failed = decoded == null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = block.imageAlt,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SoundboundShapes.small),
            )

            failed -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(SoundboundShapes.small)
                    .background(palette.rule.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = block.imageAlt ?: "Image unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.default),
                )
            }

            else -> ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
        }
        block.imageAlt?.takeIf { it.isNotBlank() && bitmap != null }?.let { alt ->
            Spacer(Modifier.height(Spacing.small))
            Text(
                text = alt,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BlockTable(block: ContentBlock, fontFamily: FontFamily, state: ReaderScreenState) {
    val palette = LocalReaderPalette.current
    Surface(
        shape = SoundboundShapes.small,
        color = palette.rule.copy(alpha = 0.22f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            block.tableRows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.tiny),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    row.forEach { cell ->
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = fontFamily,
                                fontSize = (state.typography.fontSizeSp * 0.86f).sp,
                                color = if (rowIndex == 0) palette.heading else palette.text,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (rowIndex == 0 && block.tableRows.size > 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(palette.rule),
                    )
                }
            }
        }
    }
}

/** End-of-chapter navigation, so the reader never hits a dead stop. */
@Composable
private fun ChapterFooter(
    state: ReaderScreenState,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp,
) {
    val palette = LocalReaderPalette.current
    val chapter = state.reader.chapter.value
    val total = state.reader.chapterCount

    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .padding(horizontal = state.typography.pageMarginDp.dp)
            .padding(top = Spacing.generous, bottom = Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
        Spacer(Modifier.height(Spacing.comfortable))
        Text(
            text = if (total > 0) "Chapter ${chapter + 1} of $total" else "End of chapter",
            style = MaterialTheme.typography.labelMedium,
            color = palette.muted,
        )
        Spacer(Modifier.height(Spacing.default))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.comfortable)) {
            SoundboundIconButton(
                icon = SoundboundIcons.PreviousParagraph,
                contentDescription = "Previous chapter",
                onClick = onPrevious,
                enabled = chapter > 0,
                tint = palette.text,
            )
            SoundboundIconButton(
                icon = SoundboundIcons.NextParagraph,
                contentDescription = "Next chapter",
                onClick = onNext,
                enabled = chapter + 1 < total,
                tint = palette.text,
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderScreenState,
    actions: ReaderActions,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPadding)
                .padding(horizontal = Spacing.small, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SoundboundIconButton(SoundboundIcons.Back, "Back to the library", actions.onBack)
            Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.small)) {
                Text(
                    text = state.bookTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.showChapterTitle) {
                    state.reader.content?.title?.let { chapterTitle ->
                        Text(
                            text = chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            SoundboundIconButton(SoundboundIcons.Search, "Search this book", actions.onShowSearch)
            SoundboundIconButton(SoundboundIcons.Bookmark, "Bookmarks", actions.onShowBookmarks)
            SoundboundIconButton(SoundboundIcons.Contents, "Contents", actions.onShowContents)
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderScreenState,
    actions: ReaderActions,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val haptics = LocalHaptics.current
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            SlimProgressBar(progress = state.bookProgress, height = 2.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.small, vertical = Spacing.small)
                    .padding(bottom = bottomPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SoundboundIconButton(SoundboundIcons.Typography, "Appearance", actions.onShowAppearance)
                SoundboundIconButton(
                    SoundboundIcons.Bookmark,
                    "Add a bookmark",
                    onClick = {
                        haptics.confirm()
                        actions.onAddBookmark()
                    },
                )
                SoundboundIconButton(
                    icon = if (state.isSpeaking) SoundboundIcons.Pause else SoundboundIcons.Play,
                    contentDescription = if (state.isSpeaking) "Pause reading aloud" else "Read aloud",
                    onClick = actions.onTogglePlayback,
                    size = 52.dp,
                    iconSize = 26.dp,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    background = MaterialTheme.colorScheme.primary,
                )
                SoundboundIconButton(SoundboundIcons.Voices, "Open the player", actions.onOpenPlayer)
                SoundboundIconButton(SoundboundIcons.Contents, "Contents", actions.onShowContents)
            }
        }
    }
}

@Composable
private fun LoadingChapter() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.large, vertical = Spacing.generous),
        verticalArrangement = Arrangement.spacedBy(Spacing.default),
    ) {
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.55f).height(30.dp))
        Spacer(Modifier.height(Spacing.default))
        repeat(9) { index ->
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(if (index % 4 == 3) 0.68f else 1f)
                    .height(15.dp),
            )
        }
    }
}

@Composable
private fun ChapterError(message: String, onBack: () -> Unit) {
    val palette = LocalReaderPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            SoundboundIcons.Warning,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = palette.muted,
        )
        Spacer(Modifier.height(Spacing.default))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.large))
        SoundboundIconButton(SoundboundIcons.Back, "Back to the library", onBack, tint = palette.text)
    }
}
