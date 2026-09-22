package app.soundbound.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.soundbound.core.export.ExportFormat
import app.soundbound.core.export.ExportGrouping
import app.soundbound.core.export.ExportProgress
import app.soundbound.core.export.Mp3Settings
import app.soundbound.core.library.LibraryFilter
import app.soundbound.core.library.LibrarySort
import app.soundbound.core.model.BookId
import app.soundbound.core.session.SearchProgress
import app.soundbound.core.voices.CatalogVoice
import app.soundbound.core.voices.InstallProgress
import app.soundbound.ui.library.LibraryLayout
import java.io.File

/**
 * The interface's own state: everything that is about *looking at* the app rather than about the
 * books themselves. Anything worth keeping between launches lives in settings instead.
 */
class AppUiState {

    // ---------------------------------------------------------------- library
    var query by mutableStateOf("")
    var sort by mutableStateOf(LibrarySort.RECENTLY_OPENED)
    var filter by mutableStateOf(LibraryFilter.ALL)
    var activeTag by mutableStateOf<String?>(null)
    var layout by mutableStateOf(LibraryLayout.GRID)

    // ---------------------------------------------------------------- voices
    var catalogue by mutableStateOf<List<CatalogVoice>>(emptyList())
    var isLoadingCatalogue by mutableStateOf(false)
    var catalogueError by mutableStateOf<String?>(null)
    var languageFilter by mutableStateOf<String?>(null)
    var previewingVoiceId by mutableStateOf<app.soundbound.core.model.VoiceId?>(null)
    val installing = mutableStateMapOf<String, InstallProgress>()
    var installedKeys by mutableStateOf<Set<String>>(emptySet())

    // ---------------------------------------------------------------- in-book search
    var searchQuery by mutableStateOf("")
    var searchProgress by mutableStateOf<SearchProgress?>(null)

    // ---------------------------------------------------------------- export
    var exportFormat by mutableStateOf(ExportFormat.MP3)
    var exportGrouping by mutableStateOf(ExportGrouping.PER_CHAPTER)
    var exportMp3 by mutableStateOf(Mp3Settings())
    var exportDestination by mutableStateOf<File?>(null)
    var exportProgress by mutableStateOf<ExportProgress?>(null)
    var isExporting by mutableStateOf(false)
    val selectedChapters = mutableStateListOf<Int>()

    /** Which book the chapter selection belongs to, so it is not carried over to another. */
    var selectionBookId by mutableStateOf<BookId?>(null)

    // ---------------------------------------------------------------- transient
    var pendingBookForDetails by mutableStateOf<BookId?>(null)
    var noteBeingShown by mutableStateOf<String?>(null)
    var statusMessage by mutableStateOf<String?>(null)

    fun selectAllChapters(count: Int) {
        selectedChapters.clear()
        selectedChapters.addAll(0 until count)
    }

    fun clearChapterSelection() = selectedChapters.clear()

    fun toggleChapter(index: Int) {
        if (!selectedChapters.remove(index)) selectedChapters.add(index)
    }

    /** The catalogue as the user has filtered it. */
    fun visibleCatalogue(): List<CatalogVoice> {
        val language = languageFilter ?: return catalogue
        return catalogue.filter { it.localeDescription == language }
    }
}

@Composable
fun rememberAppUiState(): AppUiState = remember { AppUiState() }
