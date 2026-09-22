package app.soundbound.core.session

import app.soundbound.core.book.BookFileHandle
import app.soundbound.core.book.synchronised
import app.soundbound.core.book.BookSource
import app.soundbound.core.library.BookImporter
import app.soundbound.core.library.BookOpener
import app.soundbound.core.library.ImportResult
import app.soundbound.core.library.LibraryEntry
import app.soundbound.core.library.LibraryRepository
import app.soundbound.core.model.BookId
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.model.HighlightColour
import app.soundbound.core.model.ReadingPosition
import app.soundbound.core.export.AudiobookExporter
import app.soundbound.core.export.ExportGrouping
import app.soundbound.core.export.ExportFormat
import app.soundbound.core.export.ExportProgress
import app.soundbound.core.export.ExportRequest
import app.soundbound.core.export.Mp3Settings
import app.soundbound.core.player.AudioSink
import app.soundbound.core.player.ReadAloudController
import app.soundbound.core.prefs.SettingsRepository
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.tts.VoiceRegistry
import app.soundbound.core.voices.VoiceInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Source
import okio.source
import java.io.File

/** Where everything lives on this device. */
data class SoundboundPaths(
    val root: File,
) {
    val libraryFile: File get() = File(root, "library.json")
    val settingsFile: File get() = File(root, "settings.json")
    val coversDirectory: File get() = File(root, "covers")
    val voicesDirectory: File get() = File(root, "voices")
    val booksDirectory: File get() = File(root, "books")
    val lexiconFile: File get() = File(root, "lexicon.txt.gz")
    val espeakDataDirectory: File get() = File(root, "espeak-ng-data")
    val fontsDirectory: File get() = File(root, "fonts")

    fun ensure() {
        listOf(root, coversDirectory, voicesDirectory, booksDirectory, fontsDirectory)
            .forEach { if (!it.isDirectory) it.mkdirs() }
    }
}

/** What the whole app is doing, beyond any one screen. */
data class SoundboundState(
    val voices: List<TtsVoice> = emptyList(),
    val isLoadingVoices: Boolean = false,
    val activeBookId: BookId? = null,
    val message: String? = null,
    val isImporting: Boolean = false,
    val importedThisRun: Int = 0,
)

/**
 * The application, minus its interface.
 *
 * Every platform assembles one of these and then draws it. Keeping the wiring here rather than in
 * the interface means the coordination — which voice is loaded, where the position is saved, what
 * happens when a book is opened while another is being spoken — is the same on all three
 * platforms and can be tested without a screen.
 */
class Soundbound(
    val paths: SoundboundPaths,
    val library: LibraryRepository,
    val settings: SettingsRepository,
    val voiceRegistry: VoiceRegistry,
    val voiceInstaller: VoiceInstaller,
    val opener: BookOpener,
    val importer: BookImporter,
    private val audioSink: AudioSink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** The device's language, used to pick a first voice. */
    private val deviceLanguageTag: String = "en-GB",
) : AutoCloseable {

    private val _state = MutableStateFlow(SoundboundState())
    val state: StateFlow<SoundboundState> = _state.asStateFlow()

    val reader = ReaderSession()

    val player = ReadAloudController(
        registry = voiceRegistry,
        sink = audioSink,
        scope = scope,
        planOptions = settings.current.speech.toPlanOptions(),
        onPositionChanged = { position -> onListeningPositionChanged(position) },
    )

    private val openLock = Mutex()
    private val previewLock = Mutex()

    /**
     * Preview clips are given negative identifiers so they can never be mistaken for a clip of
     * the book by the highlight watcher.
     */
    private val previewClipIds = java.util.concurrent.atomic.AtomicLong(0)

    /** Renders books to MP3 or WAV. Needs no network and no permission beyond a folder to write to. */
    val exporter = AudiobookExporter(voiceRegistry)

    init {
        refreshVoices()
    }

    // ---------------------------------------------------------------- voices

    fun refreshVoices() {
        scope.launch {
            _state.value = _state.value.copy(isLoadingVoices = true)
            val voices = runCatching { voiceRegistry.voices() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(voices = voices, isLoadingVoices = false)

            // Having just installed a first voice, the user expects to be able to press play.
            // Making them then go and choose it from a list would be a pointless extra step.
            if (voices.isNotEmpty() && player.state.value.voice == null) {
                val entry = _state.value.activeBookId?.let { library.entry(it) }
                voiceFor(entry)?.let { chosen ->
                    if (settings.current.speech.voiceId == null) {
                        settings.updateSpeech { it.copy(voiceId = chosen.id.value) }
                    }
                    player.setVoice(chosen)
                }
            }
        }
    }

    /**
     * Chooses the voice for a book: the one set for that book, else the app default, else the best
     * match for the book's own language. Reading a French novel in an English voice is technically
     * possible and completely unbearable, so language is weighted heavily.
     */
    suspend fun voiceFor(entry: LibraryEntry?): TtsVoice? {
        val voices = voiceRegistry.voices()
        if (voices.isEmpty()) return null

        entry?.voiceId?.let { id -> voices.firstOrNull { it.id == id }?.let { return it } }
        settings.current.speech.voiceId?.let { id ->
            voices.firstOrNull { it.id.value == id }?.let { chosen ->
                val bookLanguage = entry?.book?.metadata?.language
                // Keep the user's default unless the book is plainly in another language.
                if (bookLanguage == null || chosen.languageCode == bookLanguage.take(2).lowercase()) {
                    return chosen
                }
            }
        }
        return voiceRegistry.bestVoiceFor(entry?.book?.metadata?.language, deviceLanguageTag)
    }

    suspend fun selectVoice(voice: TtsVoice, forBookId: BookId? = null) {
        if (forBookId != null) {
            library.setBookVoice(forBookId, voice.id, library.entry(forBookId)?.speechRate)
        } else {
            settings.updateSpeech { it.copy(voiceId = voice.id.value) }
        }
        player.setVoice(voice)
    }

    /**
     * Speaks a short sample so the user can hear a voice before committing to it.
     *
     * Playback is paused first and the queue flushed: the registry keeps exactly one model
     * resident, so loading the preview voice would otherwise pull the narrating model out from
     * under a chapter that is still playing.
     */
    suspend fun previewVoice(voice: TtsVoice, text: String = DEFAULT_PREVIEW): Result<Unit> =
        previewLock.withLock {
            runCatching {
                player.pause()
                audioSink.flushAndStop()

                val loaded = voiceRegistry.load(voice)
                val clip = loaded.synthesise(text, settings.current.speech.toSpeechParams())
                if (clip.isEmpty) return@runCatching

                audioSink.start(clip.sampleRate)
                audioSink.resume()
                audioSink.enqueue(previewClipIds.decrementAndGet(), clip)
                audioSink.drain()
            }
        }

    // ---------------------------------------------------------------- books

    /** Opens a book for reading, and prepares read-aloud for it without starting playback. */
    suspend fun openBook(id: BookId): Result<Unit> = openLock.withLock {
        val entry = library.entry(id)
            ?: return@withLock Result.failure(IllegalArgumentException("That book is no longer in your library."))

        val handle = fileHandleFor(entry)
            ?: return@withLock Result.failure(
                IllegalStateException("\"${entry.book.metadata.title}\" could not be found where it was imported from."),
            )

        val source = try {
            // Wrapped because the reader and read-aloud both parse chapters from this one
            // instance, on different dispatchers, and a PDF parser walked by two threads at once
            // corrupts its own state.
            withContext(Dispatchers.IO) { opener.open(handle).synchronised() }
        } catch (e: Exception) {
            return@withLock Result.failure(e)
        }

        library.markOpened(id)
        settings.update { it.copy(lastOpenedBookId = id.value) }

        reader.open(id, source, entry.position, entry.highlights)
        _state.value = _state.value.copy(activeBookId = id)

        // Read-aloud is set up but left paused: opening a book should never start talking.
        val voice = voiceFor(entry)
        player.open(source, entry.position, voice)
        applySpeechSettings(entry)

        scope.launch { runCatching { reader.prefetchNeighbours() } }
        Result.success(Unit)
    }

    suspend fun closeBook() {
        player.stop()
        reader.closeBook()
        _state.value = _state.value.copy(activeBookId = null)
    }

    /** Re-reads the speech settings, including any per-book overrides. */
    fun applySpeechSettings(entry: LibraryEntry?) {
        scope.launch {
            val speech = settings.current.speech
            val rate = entry?.speechRate ?: speech.rate
            player.setParams(speech.toSpeechParams().copy(rate = rate).coerced())
        }
    }

    private fun fileHandleFor(entry: LibraryEntry): BookFileHandle? {
        val file = File(entry.book.sourceUri)
        if (!file.isFile) return null
        return LocalFileHandle(file)
    }

    // ---------------------------------------------------------------- importing

    suspend fun import(handles: List<BookFileHandle>): List<ImportResult> {
        _state.value = _state.value.copy(isImporting = true, message = null)
        val results = importer.importAll(handles)
        val added = results.count { it is ImportResult.Added }
        val failed = results.filterIsInstance<ImportResult.Failed>()
        _state.value = _state.value.copy(
            isImporting = false,
            importedThisRun = _state.value.importedThisRun + added,
            message = when {
                results.isEmpty() -> null
                failed.size == results.size && failed.size == 1 -> failed.single().reason
                added == 0 && failed.isEmpty() -> "Already in your library."
                failed.isEmpty() -> if (added == 1) "Added one book." else "Added $added books."
                else -> "Added $added; ${failed.size} could not be read."
            },
        )
        return results
    }

    fun dismissMessage() {
        if (_state.value.message != null) _state.value = _state.value.copy(message = null)
    }

    // ---------------------------------------------------------------- export

    /**
     * Renders the open book — or a selection of its chapters — to audio files.
     *
     * Deliberately takes the already-open [BookSource] rather than re-opening the file: a long PDF
     * costs real time to parse, and the reader has already paid for it.
     *
     * @param chapters the chapters to export, or empty for the whole book.
     */
    fun exportOpenBook(
        chapters: List<ChapterIndex> = emptyList(),
        outputDirectory: File,
        format: ExportFormat = ExportFormat.MP3,
        grouping: ExportGrouping = ExportGrouping.PER_CHAPTER,
        mp3Settings: Mp3Settings = Mp3Settings(),
        voice: TtsVoice? = null,
    ): kotlinx.coroutines.flow.Flow<ExportProgress> {
        val id = _state.value.activeBookId
        val entry = id?.let { library.entry(it) }
        val source = reader.openSource
        val chosenVoice = voice
            ?: entry?.voiceId?.let { voiceId -> _state.value.voices.firstOrNull { it.id == voiceId } }
            ?: _state.value.voices.firstOrNull()

        if (entry == null || source == null) {
            return kotlinx.coroutines.flow.flowOf(
                ExportProgress.Failed("Open a book before exporting it."),
            )
        }
        if (chosenVoice == null) {
            return kotlinx.coroutines.flow.flowOf(
                ExportProgress.Failed("Install a voice before exporting. Open Voices to choose one."),
            )
        }

        val artwork = entry.book.coverImageRef
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }

        return exporter.export(
            source = source,
            request = ExportRequest(
                book = entry.book,
                chapters = chapters,
                outputDirectory = outputDirectory,
                format = format,
                grouping = grouping,
                mp3Settings = mp3Settings,
                voice = chosenVoice,
                speechParams = settings.current.speech.toSpeechParams()
                    .copy(rate = entry.speechRate ?: settings.current.speech.rate)
                    .coerced(),
                planOptions = settings.current.speech.toPlanOptions(),
                artwork = artwork,
                artworkMimeType = guessImageMimeType(artwork),
            ),
        )
    }

    /** Sniffs the cover's own bytes: a PNG labelled as a JPEG is shown as a broken image. */
    private fun guessImageMimeType(bytes: ByteArray?): String = when {
        bytes == null || bytes.size < 4 -> "image/jpeg"
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }

    // ---------------------------------------------------------------- annotations

    fun addBookmarkHere(): Boolean {
        val id = _state.value.activeBookId ?: return false
        val readerState = reader.state.value
        val content = readerState.content ?: return false
        val offset = readerState.position.characterOffset.coerceIn(0, content.plainText.length)
        val excerpt = content.plainText
            .substring(offset, (offset + 160).coerceAtMost(content.plainText.length))
            .replace('\n', ' ')
            .trim()
        return library.addBookmark(id, readerState.position, excerpt) != null
    }

    fun addHighlight(
        chapter: ChapterIndex,
        startOffset: Int,
        endOffset: Int,
        text: String,
        colour: HighlightColour = HighlightColour.YELLOW,
        note: String? = null,
    ): Boolean {
        val id = _state.value.activeBookId ?: return false
        val created = library.addHighlight(id, chapter, startOffset, endOffset, text, colour, note)
        if (created != null) {
            reader.setHighlights(library.entry(id)?.highlights.orEmpty())
        }
        return created != null
    }

    fun removeHighlight(highlightId: String) {
        val id = _state.value.activeBookId ?: return
        library.removeHighlight(id, highlightId)
        reader.setHighlights(library.entry(id)?.highlights.orEmpty())
    }

    // ---------------------------------------------------------------- position

    /**
     * Saves where the voice has reached, and moves the reader to match when the user has asked
     * the page to follow the narration.
     */
    private fun onListeningPositionChanged(position: ReadingPosition) {
        val id = _state.value.activeBookId ?: return
        library.savePosition(id, position)
        if (settings.current.speech.followWithReader) {
            scope.launch {
                val readerState = reader.state.value
                if (readerState.chapter != position.chapter) {
                    reader.goToChapter(position.chapter, position.characterOffset)
                }
            }
        }
    }

    /** Saves where the reader has scrolled to. Called as the user reads, so it stays cheap. */
    fun saveReadingPosition() {
        val id = _state.value.activeBookId ?: return
        library.savePosition(id, reader.state.value.position)
    }

    override fun close() {
        runCatching { player.close() }
        runCatching { reader.close() }
        runCatching { voiceRegistry.close() }
    }

    companion object {
        const val DEFAULT_PREVIEW =
            "It is a truth universally acknowledged, that a single man in possession of a good " +
                "fortune, must be in want of a wife."
    }
}

/** A [BookFileHandle] over an ordinary file. Used by the desktop, and by Android once imported. */
class LocalFileHandle(private val file: File) : BookFileHandle {
    override val displayName: String get() = file.name
    override val sizeBytes: Long get() = file.length()
    override fun openSource(): Source = file.inputStream().source()
    override fun localPath(): String = file.absolutePath
}
