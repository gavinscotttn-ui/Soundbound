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
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.soundbound.android.MainActivity
import app.soundbound.android.R
import app.soundbound.android.SoundboundApplication
import app.soundbound.core.player.PlaybackSnapshot
import app.soundbound.core.player.PlaybackStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps reading aloud with the screen off, and puts transport controls everywhere the system
 * offers them: the lock screen, the notification shade, the media carousel in Quick Settings, a
 * Bluetooth headset's buttons, a car head unit, a watch.
 *
 * Three separate things are needed for that, and leaving any one out breaks it in a different
 * way. A foreground service, or Android kills the process the moment the app is no longer in
 * front. A media session, or the app makes sound that the rest of the system cannot see or
 * control. Audio focus, so Soundbound pauses for a phone call and ducks for a navigation prompt
 * rather than talking over both.
 *
 * A wake lock is held while playing as well. Synthesising a sentence is real processor work, and
 * unlike a file being decoded by the audio hardware it does not keep the processor awake by
 * itself, so without one the reading stutters or stops a few minutes after the screen goes off.
 */
class SoundboundPlaybackService : LifecycleService() {

    private val app: SoundboundApplication by lazy { SoundboundApplication.of(this) }
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false
    private var headphoneReceiver: BroadcastReceiver? = null
    private var session: PlaybackSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** What was last published to the session, so unchanged metadata is not re-sent. */
    private var publishedKey: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        session = PlaybackSession(this, SessionCallbacks())
        observePlayback()
        registerHeadphoneReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            // Sent when playback has already begun inside the app: there is nothing to start,
            // but audio focus has to be taken so that a phone call still interrupts the book.
            ACTION_ATTACH -> requestAudioFocus()

            ACTION_PLAY -> lifecycleScope.launch {
                if (requestAudioFocus()) app.engine.playback.play()
            }

            ACTION_PAUSE -> lifecycleScope.launch { app.engine.playback.pause() }
            ACTION_NEXT -> lifecycleScope.launch { app.engine.playback.skip(1) }
            ACTION_PREVIOUS -> lifecycleScope.launch { app.engine.playback.skip(-1) }
            ACTION_STOP -> lifecycleScope.launch {
                app.engine.playback.pause()
                stopForegroundCompat()
                stopSelf()
            }
        }

        startForegroundCompat()
        return START_NOT_STICKY
    }

    private fun observePlayback() {
        lifecycleScope.launch {
            // The unified snapshot, so that a recorded audiobook reaches the lock screen exactly
            // as a synthesised one does.
            app.engine.playback.snapshot.collectLatest { state ->
                publish(state)
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification())
                holdWakeLock(state.status == PlaybackStatus.PLAYING)
                if (state.status == PlaybackStatus.IDLE || state.status == PlaybackStatus.FINISHED) {
                    abandonAudioFocus()
                }
            }
        }
    }

    /** Pushes what is playing to the media session, and so to everything watching it. */
    private fun publish(state: PlaybackSnapshot) {
        val session = session ?: return
        val entry = app.engine.state.value.activeBookId?.let { app.engine.library.entry(it) }

        // Metadata crosses a process boundary and may carry a bitmap, so it is only re-sent when
        // something a listener would see has actually changed.
        val key = listOf(
            entry?.book?.id?.value,
            state.chapterTitle,
            state.durationMillis,
        ).joinToString("|")
        if (key != publishedKey) {
            publishedKey = key
            session.publishMetadata(
                title = entry?.book?.metadata?.title ?: getString(R.string.notification_reading),
                author = entry?.book?.metadata?.authors?.firstOrNull(),
                chapter = state.chapterTitle,
                durationMillis = state.durationMillis,
                // The library stores an absolute path, written when the book was imported.
                coverFile = entry?.book?.coverImageRef?.let(::File),
            )
        }

        session.publishState(
            status = state.status,
            positionMillis = state.positionMillis,
            speed = state.speed,
        )
    }

    private fun buildNotification(): Notification {
        val state = app.engine.playback.snapshot.value
        val entry = app.engine.state.value.activeBookId?.let { app.engine.library.entry(it) }
        val title = entry?.book?.metadata?.title ?: getString(R.string.notification_reading)
        val isPlaying = state.status == PlaybackStatus.PLAYING

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(
                state.chapterTitle?.takeIf { it.isNotBlank() }
                    ?: entry?.book?.metadata?.authorLine.orEmpty(),
            )
            .setContentIntent(openApp)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(command(ACTION_STOP))
            .addAction(
                action(R.drawable.ic_notification_previous, R.string.action_previous, ACTION_PREVIOUS),
            )
            .addAction(
                if (isPlaying) {
                    action(R.drawable.ic_notification_pause, R.string.action_pause, ACTION_PAUSE)
                } else {
                    action(R.drawable.ic_notification_play, R.string.action_play, ACTION_PLAY)
                },
            )
            .addAction(action(R.drawable.ic_notification_next, R.string.action_next, ACTION_NEXT))

        // MediaStyle is what turns this from a notification with buttons into the system's media
        // player: the artwork, the scrubber and the place in the Quick Settings carousel all come
        // from handing it the session token.
        session?.let { live ->
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(live.token)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        }

        return builder.build()
    }

    private fun action(icon: Int, label: Int, commandAction: String): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            getString(label),
            command(commandAction),
        ).build()

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, SoundboundPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---------------------------------------------------------------- media session

    /**
     * What a headset button, a car's steering wheel or the lock screen asks for.
     *
     * Next and previous are chapters, because that is what those buttons mean on an audiobook —
     * on a music player they are tracks, and a chapter is the nearest thing a book has. Sentence
     * steps are on fast-forward and rewind, and in the app itself.
     */
    private inner class SessionCallbacks : PlaybackSession.Callbacks {
        override fun onPlay() {
            lifecycleScope.launch { if (requestAudioFocus()) app.engine.playback.play() }
        }

        override fun onPause() = onPlayback { pause() }

        override fun onStop() {
            lifecycleScope.launch {
                app.engine.playback.pause()
                stopForegroundCompat()
                stopSelf()
            }
        }

        override fun onSkipToNext() = onPlayback { skipChapter(1) }
        override fun onSkipToPrevious() = onPlayback { skipChapter(-1) }
        override fun onFastForward() = onPlayback { skip(1) }
        override fun onRewind() = onPlayback { skip(-1) }

        override fun onSeekTo(positionMillis: Long) {
            val total = app.engine.playback.snapshot.value.durationMillis
            if (total <= 0) return
            onPlayback { seekToFraction(positionMillis.toDouble() / total) }
        }

        override fun onSetSpeed(speed: Float) = onPlayback { setSpeed(speed.coerceIn(0.5f, 3.5f)) }

        private fun onPlayback(
            block: suspend app.soundbound.core.player.Playback.() -> Unit,
        ) {
            lifecycleScope.launch { app.engine.playback.block() }
        }
    }

    // ---------------------------------------------------------------- wake lock

    /**
     * Holds the processor awake while speaking.
     *
     * Audio being decoded by the hardware keeps itself going with the screen off; audio being
     * *synthesised* does not, because the work is happening on the processor the system is trying
     * to put to sleep. The lock is released the moment playback stops, and it never keeps the
     * screen on.
     */
    private fun holdWakeLock(hold: Boolean) {
        if (hold) {
            if (wakeLock?.isHeld == true) return
            val power = getSystemService(PowerManager::class.java) ?: return
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                runCatching { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
            }
        } else {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
        }
    }

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
                app.engine.playback.pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> lifecycleScope.launch {
                wasPlayingBeforeFocusLoss =
                    app.engine.playback.snapshot.value.status == PlaybackStatus.PLAYING
                app.engine.playback.pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (duckPreferred) {
                    app.audioSink.duck(true)
                } else {
                    lifecycleScope.launch {
                        wasPlayingBeforeFocusLoss =
                            app.engine.playback.snapshot.value.status == PlaybackStatus.PLAYING
                        app.engine.playback.pause()
                    }
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                app.audioSink.duck(false)
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    lifecycleScope.launch { app.engine.playback.play() }
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
                        app.engine.playback.pause()
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
        holdWakeLock(false)
        session?.release()
        session = null
        abandonAudioFocus()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "soundbound.playback"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "soundbound:playback"

        /**
         * A safety net, not a limit. The lock is released when playback stops; the timeout only
         * matters if the process is killed in a way that skips that, so that a bug can never
         * flatten a battery overnight. Longer than any plausible single sitting.
         */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 6L * 60 * 60 * 1000

        const val ACTION_ATTACH = "app.soundbound.ATTACH"
        const val ACTION_PLAY = "app.soundbound.PLAY"
        const val ACTION_PAUSE = "app.soundbound.PAUSE"
        const val ACTION_NEXT = "app.soundbound.NEXT"
        const val ACTION_PREVIOUS = "app.soundbound.PREVIOUS"
        const val ACTION_STOP = "app.soundbound.STOP"

        fun start(context: Context, action: String = ACTION_ATTACH) {
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
