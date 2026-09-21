package app.soundbound.ui.app

import androidx.compose.material3.SnackbarHostState
import app.soundbound.core.export.ExportProgress
import app.soundbound.core.library.LibraryEntry
import app.soundbound.core.model.Bookmark
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.BookId
import app.soundbound.core.model.TocEntry
import app.soundbound.core.player.PlaybackStatus
import app.soundbound.core.player.ReadAloudState
import app.soundbound.core.prefs.Settings
import app.soundbound.core.session.ReaderState
import app.soundbound.core.session.SearchHit
import app.soundbound.core.session.Soundbound
import app.soundbound.core.session.SoundboundState
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.voices.CatalogVoice
import app.soundbound.core.voices.InstallProgress
import app.soundbound.core.voices.languageSummary
import app.soundbound.ui.library.LibraryActions
import app.soundbound.ui.library.LibraryScreenState
import app.soundbound.ui.player.ExportSheetState
import app.soundbound.ui.player.PlayerActions
import app.soundbound.ui.player.PlayerScreenState
import app.soundbound.ui.player.formatDuration
import app.soundbound.ui.reader.ReaderActions
import app.soundbound.ui.reader.ReaderScreenState
import app.soundbound.ui.settings.SettingsActions
import app.soundbound.ui.voices.VoicesActions
import app.soundbound.ui.voices.VoicesScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okio.buffer
import java.io.File

/**
 * Turns interface events into engine calls, and engine state into screen state.
 *
 * Everything here is glue on purpose: no decisions about how a book is read or how a voice is
 * chosen live in this class, only which screen is showing and which coroutine is running. That
 * keeps the interesting behaviour in :core where it is tested.
 */
class AppController(
    private val engine: Soundbound,
    private val bridge: PlatformBridge,
    private val navigator: Navigator,
    private val sheets: SheetController,
    private val ui: AppUiState,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
) {

    private var searchJob: Job? = null
    private var exportJob: Job? = null
    private val installJobs = HashMap<String, Job>()

    // ---------------------------------------------------------------- library

    fun libraryState(
        entries: List<LibraryEntry>,
        playback: ReadAloudState,
        app: SoundboundState,
    ): LibraryScreenState {
        val visible = engine.library.view(ui.sort, ui.filter, ui.query, ui.activeTag)
        return LibraryScreenState(
            entries = visible,
            allTags = engine.library.tags(),
            sort = ui.sort,
            filter = ui.filter,
            query = ui.query,
            activeTag = ui.activeTag,
            layout = ui.layout,
            speakingBookId = app.activeBookId.takeIf { playback.status == PlaybackStatus.PLAYING },
            isImporting = app.isImporting,
            totalBookCount = entries.size,
        )
    }

    fun libraryActions() = LibraryActions(
        onOpenBook = ::openBook,
        onShowDetails = { navigator.goTo(Destination.BookDetails(it)) },
        onQueryChange = { ui.query = it },
        onSortChange = { ui.sort = it },
        onFilterChange = { ui.filter = it },
        onTagChange = { ui.activeTag = it },
        onLayoutChange = { ui.layout = it },
        onImport = ::importBooks,
        onToggleFavourite = { id, favourite -> engine.library.setFavourite(id, favourite) },
    )

    fun importBooks() {
        scope.launch {
            val handles = bridge.pickBooks()
            if (handles.isEmpty()) return@launch
            engine.import(handles)
        }
    }

    fun openBook(id: BookId) {
        scope.launch {
            engine.openBook(id).fold(
                onSuccess = { navigator.goTo(Destination.Reader(id)) },
                onFailure = { error ->
                    snackbar.showSnackbar(error.message ?: "That book could not be opened.")
                },
            )
        }
    }

    // ---------------------------------------------------------------- reader

    fun readerScreenState(
        reader: ReaderState,
        playback: ReadAloudState,
        settings: Settings,
        entries: List<LibraryEntry>,
    ): ReaderScreenState {
        val book = entries.firstOrNull { it.book.id == reader.bookId }?.book
        val speaking = playback.status == PlaybackStatus.PLAYING
        return ReaderScreenState(
            reader = reader,
            bookTitle = book?.metadata?.title.orEmpty(),
            typography = settings.typography,
            highlightStyle = settings.speech.highlight,
            showProgressBar = settings.reader.showProgressBar,
            showChapterTitle = settings.reader.showChapterTitle,
            // The highlight only applies to the chapter on screen; the voice may have moved on.
            speakingRange = playback.highlightRange.takeIf { playback.chapter == reader.chapter },
            speakingWordRange = playback.wordRange.takeIf { playback.chapter == reader.chapter },
            isSpeaking = speaking,
            followNarration = settings.speech.followWithReader,
            bookProgress = playback.progress.fraction.toFloat(),
        )
    }

    fun readerActions() = ReaderActions(
        onBack = {
            engine.saveReadingPosition()
            navigator.back()
        },
        onShowContents = { sheets.show(Sheet.CONTENTS) },
        onShowAppearance = { sheets.show(Sheet.READER_APPEARANCE) },
        onShowSearch = { sheets.show(Sheet.SEARCH_IN_BOOK) },
        onShowBookmarks = { sheets.show(Sheet.BOOKMARKS) },
        onAddBookmark = ::addBookmark,
        onOpenPlayer = { navigator.goTo(Destination.Player) },
        onTogglePlayback = ::togglePlayPause,
        onPositionChanged = { offset ->
            engine.reader.updatePosition(offset)
            engine.saveReadingPosition()
        },
        onScrollHandled = { engine.reader.consumePendingScroll() },
        onFollowLink = { href ->
            scope.launch {
                if (!engine.reader.followLink(href)) {
                    snackbar.showSnackbar("That link points outside the book.")
                }
            }
        },
        onShowNote = { ref ->
            val note = engine.reader.state.value.content?.notes?.get(ref)
            scope.launch { snackbar.showSnackbar(note ?: "That note is missing from the book.") }
        },
        onHighlightBlock = { block ->
            val chapter = engine.reader.state.value.chapter
            if (engine.addHighlight(chapter, block.textStart, block.textEnd, block.text)) {
                scope.launch { snackbar.showSnackbar("Highlighted.") }
            }
        },
        onLoadImage = { ref ->
            engine.reader.openSource?.readResource(ref)?.buffer()?.use { it.readByteArray() }
        },
        onNextChapter = { scope.launch { engine.reader.nextChapter() } },
        onPreviousChapter = { scope.launch { engine.reader.previousChapter() } },
    )

    fun goToTocEntry(entry: TocEntry) {
        sheets.dismiss()
        scope.launch { engine.reader.goToTocEntry(entry) }
    }

    fun goToBookmark(bookmark: Bookmark) {
        sheets.dismiss()
        scope.launch { engine.reader.goToPosition(bookmark.position) }
    }

    fun addBookmark() {
        if (engine.addBookmarkHere()) {
            scope.launch { snackbar.showSnackbar("Bookmark added.") }
        }
    }

    // ---------------------------------------------------------------- search in book

    fun searchInBook(query: String) {
        ui.searchQuery = query
        searchJob?.cancel()
        if (query.trim().length < 2) {
            ui.searchProgress = null
            return
        }
        searchJob = scope.launch {
            engine.reader.search(query) { progress -> ui.searchProgress = progress }
        }
    }

    fun goToSearchHit(hit: SearchHit) {
        sheets.dismiss()
        scope.launch { engine.reader.goToChapter(hit.chapter, hit.characterOffset) }
    }

    // ---------------------------------------------------------------- player

    fun playerState(
        playback: ReadAloudState,
        reader: ReaderState,
        app: SoundboundState,
        entries: List<LibraryEntry>,
    ): PlayerScreenState = PlayerScreenState(
        playback = playback,
        book = entries.firstOrNull { it.book.id == app.activeBookId }?.book,
        chapterTitle = playback.chapterTitle ?: reader.content?.title,
        voiceName = playback.voice?.displayName,
        isVoiceInstalled = app.voices.isNotEmpty(),
        sleepTimerLabel = playback.sleepTimerMillisRemaining?.let(::formatDuration),
    )

    fun playerActions() = PlayerActions(
        onClose = { navigator.back() },
        onTogglePlayPause = ::togglePlayPause,
        onSkipSentence = { delta -> scope.launch { engine.player.skipSentences(delta) } },
        onSkipParagraph = { delta -> scope.launch { engine.player.skipParagraph(delta) } },
        onSkipChapter = { delta -> scope.launch { engine.player.skipChapter(delta) } },
        onShowSpeed = { sheets.show(Sheet.SPEED) },
        onShowSleepTimer = { sheets.show(Sheet.SLEEP_TIMER) },
        onShowVoicePicker = { sheets.show(Sheet.VOICE_PICKER) },
        onShowContents = { sheets.show(Sheet.CONTENTS) },
        onShowExport = ::openExportSheet,
        onOpenReader = {
            val id = engine.state.value.activeBookId
            if (id != null) navigator.replace(Destination.Reader(id)) else navigator.back()
        },
        onGetVoices = { navigator.selectTopLevel(TopLevel.VOICES) },
    )

    fun togglePlayPause() {
        scope.launch { engine.player.togglePlayPause() }
    }

    fun setRate(rate: Float) {
        scope.launch {
            engine.settings.updateSpeech { it.copy(rate = rate) }
            engine.player.setParams(engine.player.state.value.params.copy(rate = rate).coerced())
        }
    }

    fun setPitch(semitones: Float) {
        scope.launch {
            engine.settings.updateSpeech { it.copy(pitchSemitones = semitones) }
            engine.player.setParams(
                engine.player.state.value.params.copy(pitchSemitones = semitones).coerced(),
            )
        }
    }

    fun setExpressiveness(value: Float) {
        scope.launch {
            engine.settings.updateSpeech { it.copy(expressiveness = value) }
            engine.player.setParams(
                engine.player.state.value.params.copy(expressiveness = value).coerced(),
            )
        }
    }

    fun resetSpeechParams() {
        scope.launch {
            engine.settings.updateSpeech {
                it.copy(rate = 1f, pitchSemitones = 0f, expressiveness = 0.667f)
            }
            engine.player.setParams(engine.settings.current.speech.toSpeechParams())
        }
    }

    fun selectVoice(voice: TtsVoice) {
        sheets.dismiss()
        scope.launch { engine.selectVoice(voice, engine.state.value.activeBookId) }
    }

    // ---------------------------------------------------------------- voices

    fun voicesState(app: SoundboundState, settings: Settings): VoicesScreenState = VoicesScreenState(
        installed = app.voices,
        selectedVoiceId = engine.player.state.value.voice?.id
            ?: settings.speech.voiceId?.let { id -> app.voices.firstOrNull { it.id.value == id }?.id },
        catalogue = ui.visibleCatalogue(),
        installedKeys = ui.installedKeys,
        installing = ui.installing,
        isLoadingCatalogue = ui.isLoadingCatalogue,
        catalogueError = ui.catalogueError,
        languageFilter = ui.languageFilter,
        availableLanguages = ui.catalogue.languageSummary(),
        previewingVoiceId = ui.previewingVoiceId,
    )

    fun voicesActions() = VoicesActions(
        onSelectVoice = ::selectVoice,
        onPreviewVoice = ::previewVoice,
        onInstall = ::installVoice,
        onCancelInstall = { entry ->
            installJobs.remove(entry.key)?.cancel()
            ui.installing.remove(entry.key)
        },
        onUninstall = { key ->
            if (engine.voiceInstaller.uninstall(key)) {
                ui.installedKeys = ui.installedKeys - key
                engine.refreshVoices()
                scope.launch { snackbar.showSnackbar("Voice removed.") }
            }
        },
        onRefreshCatalogue = ::refreshCatalogue,
        onLanguageFilter = { ui.languageFilter = it },
        onImportVoiceFile = ::importVoiceFiles,
    )

    fun refreshCatalogue() {
        if (ui.isLoadingCatalogue) return
        ui.isLoadingCatalogue = true
        ui.catalogueError = null
        scope.launch {
            runCatching { engine.voiceInstaller.fetchCatalogue() }.fold(
                onSuccess = { list ->
                    ui.catalogue = list
                    ui.installedKeys = engine.voiceInstaller.installedKeys()
                },
                onFailure = { error ->
                    ui.catalogueError = if (!bridge.isOnline()) {
                        "You are offline. Voices already installed still work; connect once to fetch more."
                    } else {
                        error.message ?: "The voice list could not be fetched."
                    }
                },
            )
            ui.isLoadingCatalogue = false
        }
    }

    fun installVoice(entry: CatalogVoice) {
        if (installJobs.containsKey(entry.key)) return
        installJobs[entry.key] = scope.launch {
            engine.voiceInstaller.install(entry).collectLatest { progress ->
                ui.installing[entry.key] = progress
                when (progress) {
                    is InstallProgress.Installed -> {
                        ui.installing.remove(entry.key)
                        ui.installedKeys = ui.installedKeys + entry.key
                        engine.refreshVoices()
                        snackbar.showSnackbar("${entry.displayName} is ready.")
                    }

                    is InstallProgress.Failed -> {
                        ui.installing.remove(entry.key)
                        snackbar.showSnackbar(progress.reason)
                    }

                    else -> Unit
                }
            }
            installJobs.remove(entry.key)
        }
    }

    fun previewVoice(voice: TtsVoice) {
        ui.previewingVoiceId = voice.id
        scope.launch {
            engine.previewVoice(voice).onFailure { error ->
                snackbar.showSnackbar(error.message ?: "That voice could not be previewed.")
            }
            ui.previewingVoiceId = null
        }
    }

    fun importVoiceFiles() {
        scope.launch {
            val files = bridge.pickVoiceFiles()
            if (files.isEmpty()) return@launch
            val installed = copyVoiceFilesIntoStore(files)
            engine.refreshVoices()
            snackbar.showSnackbar(
                if (installed) "Voice added." else "Pick both the .onnx model and its .onnx.json file.",
            )
        }
    }

    /**
     * A Piper voice is two files that must sit together. Users routinely pick only the model, so
     * this checks for the pair rather than installing something that cannot load.
     */
    private fun copyVoiceFilesIntoStore(files: List<File>): Boolean {
        val model = files.firstOrNull { it.name.endsWith(".onnx") } ?: return false
        val config = files.firstOrNull { it.name.endsWith(".json") } ?: return false
        val key = model.name.removeSuffix(".onnx")
        val directory = File(engine.paths.voicesDirectory, key)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        return runCatching {
            model.copyTo(File(directory, model.name), overwrite = true)
            config.copyTo(File(directory, model.name + ".json"), overwrite = true)
            true
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- export

    fun openExportSheet() {
        val chapterCount = engine.reader.state.value.chapterCount
        if (ui.selectedChapters.isEmpty()) ui.selectAllChapters(chapterCount)
        if (ui.exportDestination == null) ui.exportDestination = bridge.defaultExportDirectory()
        ui.exportProgress = null
        sheets.show(Sheet.EXPORT)
    }

    fun exportState(reader: ReaderState, playback: ReadAloudState): ExportSheetState {
        val titles = (0 until reader.chapterCount).map { index ->
            reader.toc.flatMap { it.flatten() }
                .firstOrNull { it.chapter.value == index && it.fragment == null }
                ?.title
                ?: "Chapter ${index + 1}"
        }
        return ExportSheetState(
            chapterTitles = titles,
            selectedChapters = ui.selectedChapters.toSet(),
            format = ui.exportFormat,
            grouping = ui.exportGrouping,
            mp3Settings = ui.exportMp3,
            voiceName = playback.voice?.displayName,
            destinationLabel = ui.exportDestination?.absolutePath ?: "Choose a folder",
            progress = ui.exportProgress,
            isRunning = ui.isExporting,
        )
    }

    fun chooseExportDestination() {
        scope.launch {
            bridge.pickFolder("Where should the audio go?")?.let { ui.exportDestination = it }
        }
    }

    fun startExport() {
        val destination = ui.exportDestination ?: bridge.defaultExportDirectory()
        val chapters = ui.selectedChapters.sorted().map { ChapterIndex(it) }
        if (chapters.isEmpty()) return

        ui.isExporting = true
        ui.exportProgress = null
        exportJob = scope.launch {
            engine.exportOpenBook(
                chapters = chapters,
                outputDirectory = destination,
                format = ui.exportFormat,
                grouping = ui.exportGrouping,
                mp3Settings = ui.exportMp3,
            ).collectLatest { progress ->
                ui.exportProgress = progress
                when (progress) {
                    is ExportProgress.Finished -> {
                        ui.isExporting = false
                        snackbar.showSnackbar(
                            "Saved ${progress.files.size} file" +
                                (if (progress.files.size == 1) "" else "s") + ".",
                        )
                        bridge.share(progress.files)
                    }

                    is ExportProgress.Failed -> {
                        ui.isExporting = false
                        snackbar.showSnackbar(progress.reason)
                    }

                    else -> Unit
                }
            }
            ui.isExporting = false
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        ui.isExporting = false
        ui.exportProgress = null
    }

    // ---------------------------------------------------------------- settings

    fun settingsActions() = SettingsActions(
        onSettingsChange = { updated -> engine.settings.update { _ -> updated } },
        onSpeechChange = { speech ->
            engine.settings.updateSpeech { _ -> speech }
            engine.applySpeechSettings(engine.state.value.activeBookId?.let { engine.library.entry(it) })
        },
        onOpenAppearance = { sheets.show(Sheet.READER_APPEARANCE) },
        onOpenVoices = { navigator.selectTopLevel(TopLevel.VOICES) },
        onOpenPronunciations = { sheets.show(Sheet.SPEECH_SETTINGS) },
        onExportSettings = {
            scope.launch {
                val saved = bridge.saveTextFile("soundbound-settings.json", engine.settings.exportJson())
                snackbar.showSnackbar(if (saved) "Settings saved." else "Nothing was saved.")
            }
        },
        onImportSettings = {
            scope.launch {
                val text = bridge.pickTextFile()
                val imported = text != null && engine.settings.importJson(text)
                snackbar.showSnackbar(
                    if (imported) "Settings imported." else "That file is not a Soundbound settings file.",
                )
            }
        },
        onResetSpeech = {
            engine.settings.resetSpeech()
            scope.launch { snackbar.showSnackbar("Speech settings reset.") }
        },
        onOpenAbout = { scope.launch { snackbar.showSnackbar("Soundbound ${bridge.appVersion}") } },
        onOpenStorage = { bridge.revealInFileManager(engine.paths.root) },
        appVersion = bridge.appVersion,
        storageSummary = storageSummary(),
    )

    private fun storageSummary(): String {
        val voices = engine.paths.voicesDirectory.listFiles().orEmpty().size
        val books = engine.library.entries.value.size
        return "$books book" + (if (books == 1) "" else "s") +
            " · $voices voice" + (if (voices == 1) "" else "s")
    }
}
