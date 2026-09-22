package app.soundbound.android.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.soundbound.android.MainActivity
import app.soundbound.android.R
import app.soundbound.android.SoundboundApplication
import app.soundbound.core.player.PlaybackStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps reading aloud with the screen off, and puts transport controls in the notification shade
 * and on the lock screen.
 *
 * Android will kill a background process that is producing audio without a foreground service, and
 * a book that stops when the phone locks is not an audiobook. The service also takes audio focus,
 * which is what makes Soundbound pause for a phone call and duck for a navigation prompt rather
 * than talking over both.
 */
class SoundboundPlaybackService : LifecycleService() {

    private val app: SoundboundApplication by lazy { SoundboundApplication.of(this) }
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false
    private var headphoneReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observePlayback()
        registerHeadphoneReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_PLAY -> lifecycleScope.launch {
                if (requestAudioFocus()) app.engine.player.play()
            }

            ACTION_PAUSE -> lifecycleScope.launch { app.engine.player.pause() }
            ACTION_NEXT -> lifecycleScope.launch { app.engine.player.skipSentences(1) }
            ACTION_PREVIOUS -> lifecycleScope.launch { app.engine.player.skipSentences(-1) }
            ACTION_STOP -> lifecycleScope.launch {
                app.engine.player.pause()
                stopForegroundCompat()
                stopSelf()
            }
        }

        startForegroundCompat()
        return START_NOT_STICKY
    }

    private fun observePlayback() {
        lifecycleScope.launch {
            app.engine.player.state.collectLatest { state ->
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, buildNotification())
                if (state.status == PlaybackStatus.IDLE || state.status == PlaybackStatus.FINISHED) {
                    abandonAudioFocus()
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val state = app.engine.player.state.value
        val bookId = app.engine.state.value.activeBookId
        val title = bookId?.let { app.engine.library.entry(it)?.book?.metadata?.title }
            ?: getString(R.string.notification_reading)
        val isPlaying = state.status == PlaybackStatus.PLAYING

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(state.currentSentence.take(120).ifBlank { state.chapterTitle.orEmpty() })
            .setContentIntent(openApp)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_notification_previous,
                getString(R.string.action_previous),
                command(ACTION_PREVIOUS),
            )
            .addAction(
                if (isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
                getString(if (isPlaying) R.string.action_pause else R.string.action_play),
                command(if (isPlaying) ACTION_PAUSE else ACTION_PLAY),
            )
            .addAction(
                R.drawable.ic_notification_next,
                getString(R.string.action_next),
                command(ACTION_NEXT),
            )
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, SoundboundPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel_name),
            // LOW: the controls should be present but must never make a sound of their own.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------- audio focus

    private fun requestAudioFocus(): Boolean {
        val manager = getSystemService(AudioManager::class.java) ?: return true
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // Soundbound handles ducking itself, by lowering the track's volume rather than letting
            // the system attenuate everything.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(::onAudioFocusChange)
            .build()
        audioFocusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun onAudioFocusChange(change: Int) {
        val duckPreferred = app.engine.settings.current.speech.duckOnInterruption
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> lifecycleScope.launch {
                wasPlayingBeforeFocusLoss = false
                app.engine.player.pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> lifecycleScope.launch {
                wasPlayingBeforeFocusLoss =
                    app.engine.player.state.value.status == PlaybackStatus.PLAYING
                app.engine.player.pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (duckPreferred) {
                    app.audioSink.duck(true)
                } else {
                    lifecycleScope.launch {
                        wasPlayingBeforeFocusLoss =
                            app.engine.player.state.value.status == PlaybackStatus.PLAYING
                        app.engine.player.pause()
                    }
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                app.audioSink.duck(false)
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    lifecycleScope.launch { app.engine.player.play() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- headphones

    private fun registerHeadphoneReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> lifecycleScope.launch {
                        // Headphones pulled out: pause rather than blasting the book out loud.
                        app.engine.player.pause()
                    }
                }
            }
        }
        headphoneReceiver = receiver
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    /**
     * Android 14 requires the service type to be declared at the call site as well as in the
     * manifest, and throws if it is missing.
     */
    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        headphoneReceiver?.let { runCatching { unregisterReceiver(it) } }
        headphoneReceiver = null
        abandonAudioFocus()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "soundbound.playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "app.soundbound.PLAY"
        const val ACTION_PAUSE = "app.soundbound.PAUSE"
        const val ACTION_NEXT = "app.soundbound.NEXT"
        const val ACTION_PREVIOUS = "app.soundbound.PREVIOUS"
        const val ACTION_STOP = "app.soundbound.STOP"

        fun start(context: Context, action: String = ACTION_PLAY) {
            val intent = Intent(context, SoundboundPlaybackService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SoundboundPlaybackService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
