package app.soundbound.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import app.soundbound.core.model.BookId
import app.soundbound.ui.components.SoundboundIcons

/** Where the app can be. */
@Immutable
sealed interface Destination {
    data object Library : Destination
    data class Reader(val bookId: BookId) : Destination
    data object Player : Destination
    data object Voices : Destination
    data object Settings : Destination
    data class BookDetails(val bookId: BookId) : Destination
    data object Notebook : Destination
}

/** The four places reachable from the bottom bar. */
enum class TopLevel(val label: String, val icon: ImageVector, val destination: Destination) {
    LIBRARY("Library", SoundboundIcons.Library, Destination.Library),
    NOTEBOOK("Notes", SoundboundIcons.Note, Destination.Notebook),
    VOICES("Voices", SoundboundIcons.Voices, Destination.Voices),
    SETTINGS("Settings", SoundboundIcons.Settings, Destination.Settings),
}

/**
 * A back stack, kept deliberately small.
 *
 * Soundbound has six screens and no deep linking, so a navigation library would be more
 * configuration than code. This also keeps the interface identical on Android and the desktop,
 * where the platform navigation conventions differ.
 */
class Navigator(initial: Destination = Destination.Library) {

    private val stack = mutableStateListOf(initial)

    val current: Destination get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    /** The top-level tab the current destination belongs to, for highlighting the bottom bar. */
    val currentTopLevel: TopLevel?
        get() = when (current) {
            Destination.Library, is Destination.BookDetails -> TopLevel.LIBRARY
            Destination.Notebook -> TopLevel.NOTEBOOK
            Destination.Voices -> TopLevel.VOICES
            Destination.Settings -> TopLevel.SETTINGS
            is Destination.Reader, Destination.Player -> null
        }

    /** True when the chrome should be hidden: the reader and the player are full-screen. */
    val isImmersive: Boolean
        get() = current is Destination.Reader || current is Destination.Player

    fun goTo(destination: Destination) {
        if (stack.lastOrNull() == destination) return
        stack.add(destination)
    }

    /** Switches tab, collapsing the stack: a tab is a root, not a push. */
    fun selectTopLevel(tab: TopLevel) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack[0] = tab.destination
    }

    /** Replaces the current destination rather than stacking, for reader to player and back. */
    fun replace(destination: Destination) {
        stack[stack.lastIndex] = destination
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Pops everything back to the root. */
    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun rememberNavigator(initial: Destination = Destination.Library): Navigator =
    remember { Navigator(initial) }

/** Sheets, which are not destinations but do need one owner so only one is ever open. */
enum class Sheet {
    NONE,
    CONTENTS,
    READER_APPEARANCE,
    SPEECH_SETTINGS,
    VOICE_PICKER,
    SPEED,
    SLEEP_TIMER,
    BOOKMARKS,
    EXPORT,
    SEARCH_IN_BOOK,
    BOOK_ACTIONS,
    IMPORT_HELP,
}

class SheetController {
    var open: Sheet by mutableStateOf(Sheet.NONE)
        private set

    fun show(sheet: Sheet) { open = sheet }
    fun dismiss() { open = Sheet.NONE }
    fun isShowing(sheet: Sheet) = open == sheet
}

@Composable
fun rememberSheetController(): SheetController = remember { SheetController() }
