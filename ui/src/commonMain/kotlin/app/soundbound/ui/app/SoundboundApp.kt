package app.soundbound.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soundbound.core.export.ExportProgress
import app.soundbound.core.model.BookId
import app.soundbound.core.player.PlaybackStatus
import app.soundbound.core.session.Soundbound
import app.soundbound.ui.components.SoundboundIcons
import app.soundbound.ui.library.LibraryScreen
import app.soundbound.ui.notebook.NotebookScreen
import app.soundbound.ui.player.BookmarksSheet
import app.soundbound.ui.player.ContentsSheet
import app.soundbound.ui.player.ExportSheet
import app.soundbound.ui.player.MiniPlayer
import app.soundbound.ui.player.PlayerScreen
import app.soundbound.ui.player.SleepTimerSheet
import app.soundbound.ui.player.SpeedSheet
import app.soundbound.ui.player.formatDuration
import app.soundbound.ui.reader.ReaderScreen
import app.soundbound.ui.settings.AppearanceSheet
import app.soundbound.ui.settings.SettingsScreen
import app.soundbound.ui.theme.SoundboundTheme
import app.soundbound.ui.theme.Spacing
import app.soundbound.ui.voices.VoicesScreen
import kotlinx.coroutines.launch

/**
 * The whole interface.
 *
 * One composable for all three platforms. The platform apps supply a [Soundbound] and a
 * [PlatformBridge] and then get out of the way — which is the point of the arrangement, because a
 * reader's behaviour should not quietly differ between a phone and a laptop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundboundApp(
    engine: Soundbound,
    bridge: PlatformBridge,
    modifier: Modifier = Modifier,
) {
    val settings by engine.settings.settings.collectAsState()
    val appState by engine.state.collectAsState()
    val libraryEntries by engine.library.entries.collectAsState()
    val readerState by engine.reader.state.collectAsState()
    val playbackState by engine.player.state.collectAsState()

    val navigator = rememberNavigator()
    val sheets = rememberSheetController()
    val ui = rememberAppUiState()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val controller = remember(engine, bridge, scope) {
        AppController(engine, bridge, navigator, sheets, ui, snackbarHost, scope)
    }

    // Messages from the engine — an import result, an error — surface as a snackbar and are then
    // cleared, so the same message does not reappear on the next recomposition.
    LaunchedEffect(appState.message) {
        appState.message?.let { message ->
            snackbarHost.showSnackbar(message)
            engine.dismissMessage()
        }
    }

    LaunchedEffect(settings.reader.keepScreenOn, navigator.isImmersive) {
        bridge.setKeepScreenOn(settings.reader.keepScreenOn && navigator.isImmersive)
    }

    // Fetch the voice list once, when the user first looks at it.
    LaunchedEffect(navigator.current) {
        if (navigator.current == Destination.Voices && ui.catalogue.isEmpty() && !ui.isLoadingCatalogue) {
            controller.refreshCatalogue()
        }
    }

    SoundboundTheme(settings = settings) {
      Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHost) },
            bottomBar = {
                AnimatedVisibility(
                    visible = !navigator.isImmersive,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                ) {
                    BottomChrome(
                        navigator = navigator,
                        playbackState = playbackState,
                        libraryEntries = libraryEntries,
                        activeBookId = appState.activeBookId,
                        chapterTitle = readerState.content?.title,
                        onExpandPlayer = { navigator.goTo(Destination.Player) },
                        onTogglePlayPause = { controller.togglePlayPause() },
                    )
                }
            },
            contentWindowInsets = WindowInsets.statusBars,
        ) { padding ->
            AnimatedContent(
                targetState = navigator.current,
                transitionSpec = {
                    fadeIn(app.soundbound.ui.theme.Motion.quick()) togetherWith
                        fadeOut(app.soundbound.ui.theme.Motion.quick())
                },
                label = "destination",
            ) { destination ->
                when (destination) {
                    Destination.Library -> LibraryScreen(
                        state = controller.libraryState(libraryEntries, playbackState, appState),
                        actions = controller.libraryActions(),
                        contentPadding = padding,
                    )

                    Destination.Notebook -> NotebookScreen(
                        annotations = engine.library.allAnnotations(),
                        onOpenBook = { id -> controller.openBook(id) },
                        onDeleteHighlight = { bookId, highlightId ->
                            engine.library.removeHighlight(bookId, highlightId)
                        },
                        contentPadding = padding,
                    )

                    Destination.Voices -> VoicesScreen(
                        state = controller.voicesState(appState, settings),
                        actions = controller.voicesActions(),
                        contentPadding = padding,
                    )

                    Destination.Settings -> SettingsScreen(
                        settings = settings,
                        actions = controller.settingsActions(),
                        contentPadding = padding,
                    )

                    is Destination.Reader -> ReaderScreen(
                        state = controller.readerScreenState(readerState, playbackState, settings, libraryEntries),
                        actions = controller.readerActions(),
                        contentPadding = PaddingValues(0.dp),
                    )

                    Destination.Player -> PlayerScreen(
                        state = controller.playerState(playbackState, readerState, appState, libraryEntries),
                        actions = controller.playerActions(),
                        contentPadding = padding,
                    )

                    is Destination.BookDetails -> BookDetailsScreen(
                        entry = libraryEntries.firstOrNull { it.book.id == destination.bookId },
                        onOpen = { controller.openBook(destination.bookId) },
                        onBack = { navigator.back() },
                        onToggleFavourite = { favourite ->
                            engine.library.setFavourite(destination.bookId, favourite)
                        },
                        onToggleFinished = { finished ->
                            engine.library.setFinished(destination.bookId, finished)
                        },
                        onRemove = {
                            engine.library.remove(destination.bookId)
                            navigator.back()
                        },
                        contentPadding = padding,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- sheets
        if (sheets.open != Sheet.NONE) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { sheets.dismiss() },
                sheetState = sheetState,
                shape = app.soundbound.ui.theme.SoundboundShapes.sheet,
            ) {
                when (sheets.open) {
                    Sheet.CONTENTS -> ContentsSheet(
                        toc = readerState.toc,
                        currentChapter = readerState.chapter,
                        onSelect = { entry -> controller.goToTocEntry(entry) },
                        onClose = { sheets.dismiss() },
                    )

                    Sheet.READER_APPEARANCE -> AppearanceSheet(
                        typography = settings.typography,
                        reader = settings.reader,
                        onTypographyChange = { engine.settings.updateTypography { _ -> it } },
                        onReaderChange = { engine.settings.updateReader { _ -> it } },
                        onReset = { engine.settings.resetTypography() },
                        onClose = { sheets.dismiss() },
                    )

                    Sheet.SPEED -> SpeedSheet(
                        rate = playbackState.params.rate,
                        pitchSemitones = playbackState.params.pitchSemitones,
                        expressiveness = playbackState.params.expressiveness,
                        onRateChange = { controller.setRate(it) },
                        onPitchChange = { controller.setPitch(it) },
                        onExpressivenessChange = { controller.setExpressiveness(it) },
                        onReset = { controller.resetSpeechParams() },
                        onClose = { sheets.dismiss() },
                    )

                    Sheet.SLEEP_TIMER -> SleepTimerSheet(
                        activeMillisRemaining = playbackState.sleepTimerMillisRemaining,
                        onSet = { engine.player.setSleepTimer(it) },
                        onExtend = { engine.player.extendSleepTimer(it) },
                        onClose = { sheets.dismiss() },
                    )

                    Sheet.BOOKMARKS -> BookmarksSheet(
                        bookmarks = appState.activeBookId
                            ?.let { engine.library.entry(it)?.bookmarks }
                            .orEmpty(),
                        onSelect = { bookmark -> controller.goToBookmark(bookmark) },
                        onDelete = { bookmark ->
                            appState.activeBookId?.let { engine.library.removeBookmark(it, bookmark.id) }
                        },
                        onAdd = { controller.addBookmark() },
                        onClose = { sheets.dismiss() },
                    )

                    Sheet.EXPORT -> ExportSheet(
                        state = controller.exportState(readerState, playbackState),
                        onToggleChapter = { ui.toggleChapter(it) },
                        onSelectAll = { ui.selectAllChapters(readerState.chapterCount) },
                        onSelectNone = { ui.clearChapterSelection() },
                        onFormatChange = { ui.exportFormat = it },
                        onGroupingChange = { ui.exportGrouping = it },
                        onBitrateChange = { ui.exportMp3 = ui.exportMp3.copy(bitrateKbps = it) },
                        onChooseDestination = { controller.chooseExportDestination() },
                        onStart = { controller.startExport() },
                        onCancel = { controller.cancelExport() },
                        onClose = { sheets.dismiss() },
                    )

                    Sheet.VOICE_PICKER -> VoicePickerSheet(
                        voices = appState.voices,
                        selectedId = playbackState.voice?.id,
                        onSelect = { controller.selectVoice(it) },
                        onOpenStore = {
                            sheets.dismiss()
                            navigator.selectTopLevel(TopLevel.VOICES)
                        },
                        onClose = { sheets.dismiss() },
                    )

                    Sheet.SEARCH_IN_BOOK -> SearchInBookSheet(
                        query = ui.searchQuery,
                        progress = ui.searchProgress,
                        onQueryChange = { controller.searchInBook(it) },
                        onSelect = { hit -> controller.goToSearchHit(hit) },
                        onClose = { sheets.dismiss() },
                    )

                    else -> Box(modifier = Modifier.padding(Spacing.large))
                }
            }
        }
      }
    }
}

@Composable
private fun BottomChrome(
    navigator: Navigator,
    playbackState: app.soundbound.core.player.ReadAloudState,
    libraryEntries: List<app.soundbound.core.library.LibraryEntry>,
    activeBookId: BookId?,
    chapterTitle: String?,
    onExpandPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    val book = libraryEntries.firstOrNull { it.book.id == activeBookId }?.book
    val showMiniPlayer = book != null && playbackState.status != PlaybackStatus.IDLE

    Surface(color = MaterialTheme.colorScheme.surface) {
        androidx.compose.foundation.layout.Column {
            if (showMiniPlayer) {
                MiniPlayer(
                    state = app.soundbound.ui.player.PlayerScreenState(
                        playback = playbackState,
                        book = book,
                        chapterTitle = chapterTitle,
                        voiceName = playbackState.voice?.displayName,
                    ),
                    onExpand = onExpandPlayer,
                    onTogglePlayPause = onTogglePlayPause,
                )
            }
            NavigationBar(
                windowInsets = WindowInsets.navigationBars,
            ) {
                TopLevel.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = navigator.currentTopLevel == tab,
                        onClick = { navigator.selectTopLevel(tab) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        alwaysShowLabel = false,
                    )
                }
            }
        }
    }
}
