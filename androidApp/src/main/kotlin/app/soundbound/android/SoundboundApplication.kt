package app.soundbound.android

import android.app.Application
import android.content.Context
import app.soundbound.android.audio.AndroidAudioSink
import app.soundbound.android.pdf.AndroidPdfBackend
import app.soundbound.android.tts.AndroidSystemTts
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
import app.soundbound.core.tts.onnx.PiperTtsEngine
import app.soundbound.core.tts.onnx.VoiceStore
import app.soundbound.core.tts.system.SystemTtsEngine
import app.soundbound.core.voices.VoiceInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.soundbound.android.playback.SoundboundPlaybackService
import app.soundbound.core.player.PlaybackStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Assembles the app.
 *
 * Everything is built once, here, and handed to the shared interface. There is no dependency
 * injection framework: the graph is a dozen objects with no cycles, and writing it out is shorter
 * and easier to follow than configuring a container to do the same thing.
 */
class SoundboundApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var paths: SoundboundPaths
        private set

    lateinit var audioSink: AndroidAudioSink
        private set

    lateinit var systemTts: AndroidSystemTts
        private set

    lateinit var engine: Soundbound
        private set

    override fun onCreate() {
        super.onCreate()

        paths = SoundboundPaths(filesDir).also { it.ensure() }
        AndroidPdfBackend.install(this)

        val library = LibraryRepository(paths.libraryFile)
        val settings = SettingsRepository(paths.settingsFile)
        val voiceStore = VoiceStore(paths.voicesDirectory)

        systemTts = AndroidSystemTts(this)
        audioSink = AndroidAudioSink(scope)

        val piper = PiperTtsEngine(
            store = voiceStore,
            phonemizerProvider = { phonemizerFor(settings) },
        )
        val registry = VoiceRegistry(listOf(piper, SystemTtsEngine(systemTts)))

        val opener = BookOpener.standard(AndroidPdfBackend.factory(this))

        engine = Soundbound(
            paths = paths,
            library = library,
            settings = settings,
            voiceRegistry = registry,
            voiceInstaller = VoiceInstaller(voiceStore),
            opener = opener,
            importer = BookImporter(library, opener, paths.coversDirectory),
            audioSink = audioSink,
            scope = scope,
            deviceLanguageTag = Locale.getDefault().toLanguageTag(),
        )

        // The platform engine takes a moment to bind. Doing it here means a brand-new install can
        // speak as soon as the user presses play, without waiting for a voice download.
        scope.launch {
            if (systemTts.initialise()) engine.refreshVoices()
        }

        followPlaybackWithForegroundService()
    }

    /**
     * espeak-ng where it has been installed, the built-in dictionary otherwise.
     *
     * Piper's models were trained on espeak-ng's phonemes, so matching it is audibly better and
     * brings the other forty-odd languages with it. Soundbound does not bundle the library — it
     * would add several megabytes for four architectures — so the fallback has to be good enough
     * to stand on its own, and for English it is.
     */
    private fun phonemizerFor(settings: SettingsRepository): Phonemizer {
        val overrides = settings.current.speech.pronunciationOverrides

        if (settings.current.speech.preferEspeak) {
            val bridge = AndroidEspeak.bridgeIfAvailable()
            if (bridge != null && paths.espeakDataDirectory.isDirectory) {
                return EspeakPhonemizer(bridge, paths.espeakDataDirectory.absolutePath)
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
     * Runs the playback service exactly while something is being read aloud.
     *
     * The interface calls the player directly and knows nothing about Android services, so the two
     * are tied together here: without a foreground service the system stops the audio when the
     * screen locks, and with one running needlessly there is a notification nobody wants.
     */
    private fun followPlaybackWithForegroundService() {
        scope.launch {
            var serviceRunning = false
            engine.player.state
                .map { it.status }
                .distinctUntilChanged()
                .collect { status ->
                    val shouldRun = status == PlaybackStatus.PLAYING ||
                        status == PlaybackStatus.BUFFERING ||
                        status == PlaybackStatus.PAUSED
                    when {
                        shouldRun && !serviceRunning -> {
                            runCatching { SoundboundPlaybackService.start(this@SoundboundApplication) }
                            serviceRunning = true
                        }

                        !shouldRun && serviceRunning -> {
                            runCatching { SoundboundPlaybackService.stop(this@SoundboundApplication) }
                            serviceRunning = false
                        }
                    }
                }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // A Piper session is tens of megabytes of resident memory. Releasing it is far better than
        // being killed mid-chapter; it reloads in a moment when playback resumes.
        scope.launch { engine.voiceRegistry.unload() }
        app.soundbound.ui.components.CoverCache.clear()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            app.soundbound.ui.components.CoverCache.clear()
        }
    }

    companion object {
        fun of(context: Context): SoundboundApplication =
            context.applicationContext as SoundboundApplication
    }
}
