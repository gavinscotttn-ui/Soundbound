package app.soundbound.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.soundbound.core.library.BookImporter
import app.soundbound.core.library.BookOpener
import app.soundbound.core.library.LibraryRepository
import app.soundbound.core.prefs.SettingsRepository
import app.soundbound.core.session.Soundbound
import app.soundbound.core.session.SoundboundPaths
import app.soundbound.core.tts.VoiceRegistry
import app.soundbound.core.tts.g2p.EspeakPhonemizer
import app.soundbound.core.tts.g2p.Lexicon
import app.soundbound.core.tts.g2p.LexiconPhonemizer
import app.soundbound.core.tts.g2p.Phonemizer
import app.soundbound.core.tts.onnx.KokoroTtsEngine
import app.soundbound.core.tts.onnx.PiperTtsEngine
import app.soundbound.core.tts.onnx.VoiceStore
import app.soundbound.core.tts.system.SystemTtsEngine
import app.soundbound.core.voices.VoiceInstaller
import app.soundbound.desktop.audio.DesktopAudioDecoders
import app.soundbound.desktop.audio.DesktopAudioSink
import app.soundbound.desktop.platform.DesktopPlatformBridge
import app.soundbound.desktop.tts.DesktopEspeak
import app.soundbound.desktop.tts.DesktopSystemTts
import app.soundbound.pdfjvm.PdfBoxBackend
import app.soundbound.ui.app.SoundboundApp
import app.soundbound.ui.theme.DesktopFonts
import app.soundbound.ui.theme.SoundboundColours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

private const val APP_VERSION = "1.0.0"

/**
 * The desktop entry point.
 *
 * Assembles the same [Soundbound] the Android app does, with the desktop's own audio output, PDF
 * backend and speech engine, and then hands it to the shared interface. The window remembers
 * nothing yet beyond its size; everything else the user changes is saved by the app itself.
 */
fun main() = application {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val engine = remember { buildEngine(scope) }
    val bridge = remember {
        DesktopPlatformBridge(
            exportsDirectory = File(engine.paths.root, "exports"),
            appVersion = APP_VERSION,
            onMessage = { statusMessage = it },
        )
    }

    val windowState = rememberWindowState(size = DpSize(1_180.dp, 860.dp))

    Window(
        onCloseRequest = {
            engine.saveReadingPosition()
            engine.close()
            exitApplication()
        },
        state = windowState,
        title = "Soundbound",
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SoundboundApp(engine = engine, bridge = bridge)

            // The desktop has no snackbar host outside the interface, so a message from the
            // bridge itself — a file manager that refused to open, say — is shown here. It sits
            // outside SoundboundTheme, so its colours are named explicitly rather than read from
            // MaterialTheme, which out here would quietly hand back Material's own defaults.
            statusMessage?.let { message ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = SoundboundColours.Ink800,
                    contentColor = SoundboundColours.Paper100,
                ) {
                    Text(
                        text = message,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // Clear the message after a few seconds, the way a snackbar would.
            LaunchedEffect(statusMessage) {
                if (statusMessage != null) {
                    delay(4_000)
                    statusMessage = null
                }
            }
        }
    }
}

/** Builds the application graph. Mirrors `SoundboundApplication` on Android. */
private fun buildEngine(scope: CoroutineScope): Soundbound {
    val paths = SoundboundPaths(defaultRoot()).also { it.ensure() }
    DesktopFonts.directory = paths.fontsDirectory

    val library = LibraryRepository(paths.libraryFile)
    val settings = SettingsRepository(paths.settingsFile)
    val voiceStore = VoiceStore(paths.voicesDirectory)

    // Kokoro first: where a pack is installed it is the most human-sounding of the three, and
    // the registry sorts by quality within a language anyway.
    val kokoro = KokoroTtsEngine(
        store = voiceStore,
        phonemizerProvider = { phonemizerFor(settings, paths) },
    )
    val piper = PiperTtsEngine(
        store = voiceStore,
        phonemizerProvider = { phonemizerFor(settings, paths) },
    )
    val systemTts = DesktopSystemTts(File(paths.root, "tts-cache"))
    val registry = VoiceRegistry(listOf(kokoro, piper, SystemTtsEngine(systemTts)))

    val opener = BookOpener.standard(PdfBoxBackend)

    return Soundbound(
        paths = paths,
        library = library,
        settings = settings,
        voiceRegistry = registry,
        voiceInstaller = VoiceInstaller(voiceStore),
        opener = opener,
        importer = BookImporter(library, opener, paths.coversDirectory),
        audioSink = DesktopAudioSink(scope),
        // MP3 and the formats Java's sound system knows. AAC is refused with a message rather
        // than importing a book that then makes no sound; see DesktopAudioDecoder.
        audioDecoders = DesktopAudioDecoders(),
        scope = scope,
        deviceLanguageTag = Locale.getDefault().toLanguageTag(),
    )
}

private fun phonemizerFor(settings: SettingsRepository, paths: SoundboundPaths): Phonemizer {
    val overrides = settings.current.speech.pronunciationOverrides

    if (settings.current.speech.preferEspeak) {
        val bridge = DesktopEspeak.bridgeIfAvailable()
        val dataDirectory = paths.espeakDataDirectory.takeIf { it.isDirectory }
            ?: DesktopEspeak.likelyDataDirectory()
        if (bridge != null && dataDirectory != null) {
            return EspeakPhonemizer(bridge, dataDirectory.absolutePath)
        }
    }

    val lexicon = if (paths.lexiconFile.isFile) {
        runCatching { Lexicon.load(paths.lexiconFile.inputStream(), "installed") }
            .getOrElse { Lexicon.builtIn() }
    } else {
        Lexicon.builtIn()
    }
    return LexiconPhonemizer(lexicon, overrides)
}

/**
 * Where Soundbound keeps its data.
 *
 * Each platform's own convention, so that a backup tool or a system cleanup behaves as the user
 * expects rather than finding a stray dot-directory in their home folder.
 */
private fun defaultRoot(): File {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") -> File(home, "Library/Application Support/Soundbound")
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")
            if (appData.isNullOrBlank()) File(home, "Soundbound") else File(appData, "Soundbound")
        }

        else -> {
            val dataHome = System.getenv("XDG_DATA_HOME")
            if (dataHome.isNullOrBlank()) File(home, ".local/share/soundbound")
            else File(dataHome, "soundbound")
        }
    }
}
