package app.soundbound.android

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.soundbound.android.platform.AndroidPlatformBridge
import app.soundbound.core.library.ImportResult
import app.soundbound.ui.app.SoundboundApp
import kotlinx.coroutines.launch
import java.io.File

/**
 * The only activity.
 *
 * Its job is to hand the shared interface a [PlatformBridge] and to handle the two things Android
 * sends an app from outside: a book opened from a file manager, and a book shared from another app.
 */
class MainActivity : ComponentActivity() {

    private lateinit var bridge: AndroidPlatformBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = SoundboundApplication.of(this)
        bridge = AndroidPlatformBridge(
            activity = this,
            booksDirectory = app.paths.booksDirectory,
            exportsDirectory = File(app.paths.root, "exports"),
            appVersion = BuildConfig.VERSION_NAME,
        )

        setContent {
            SoundboundApp(engine = app.engine, bridge = bridge)
        }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Imports and opens a book that arrived from another app. */
    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
            else -> null
        } ?: return

        val app = SoundboundApplication.of(this)
        lifecycleScope.launch {
            val handle = bridge.copyIntoLibrary(uri)
            if (handle == null) {
                bridge.showMessage("That file could not be read.")
                return@launch
            }
            when (val result = app.engine.import(listOf(handle)).firstOrNull()) {
                is ImportResult.Added -> app.engine.openBook(result.entry.book.id)
                is ImportResult.AlreadyPresent -> app.engine.openBook(result.entry.book.id)
                is ImportResult.Failed -> bridge.showMessage(result.reason)
                null -> Unit
            }
        }
    }

    /**
     * Volume keys turn the page when the user has asked for that.
     *
     * Intercepted here rather than in Compose because the keys never reach a composable: Android
     * routes them to the audio service first, and only an activity can claim them.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val app = SoundboundApplication.of(this)
        val settings = app.engine.settings.current
        if (!settings.reader.volumeKeysTurnPages) return super.onKeyDown(keyCode, event)
        if (app.engine.state.value.activeBookId == null) return super.onKeyDown(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                lifecycleScope.launch { app.engine.reader.nextChapter() }
                true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                lifecycleScope.launch { app.engine.reader.previousChapter() }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        // Save the position whenever the app leaves the foreground: Android may not give us
        // another chance, and losing someone's place is the one bug a reader must never have.
        SoundboundApplication.of(this).engine.saveReadingPosition()
    }
}
