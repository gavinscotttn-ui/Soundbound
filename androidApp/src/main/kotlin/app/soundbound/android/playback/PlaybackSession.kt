package app.soundbound.android.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import app.soundbound.core.player.PlaybackStatus
import java.io.File

/**
 * Soundbound's media session: what the lock screen, the notification shade, a Bluetooth headset,
 * a car head unit and a watch all talk to.
 *
 * Without one of these the app can still make noise in the background, but it is invisible to
 * the rest of the system: the lock screen shows nothing, the button on a headset does nothing,
 * and the app never appears in the media carousel at the top of Quick Settings. A book that can
 * only be paused by unlocking the phone and finding the app is not an audiobook.
 *
 * This wraps the platform session rather than a media library's. Soundbound's audio does not
 * come from a file being played — it is synthesised a sentence at a time — so there is no
 * player object to hand over, only a position that the app itself knows. The platform API is the
 * one that lets the app report that position directly.
 */
class PlaybackSession(
    context: Context,
    private val callbacks: Callbacks,
) {

    /** What the outside world can ask for. Each maps to something the player already does. */
    interface Callbacks {
        fun onPlay()
        fun onPause()
        fun onStop()
        /** Next chapter. */
        fun onSkipToNext()
        /** Previous chapter, or the start of this one. */
        fun onSkipToPrevious()
        /** One sentence on. */
        fun onFastForward()
        /** One sentence back. */
        fun onRewind()
        fun onSeekTo(positionMillis: Long)
        fun onSetSpeed(speed: Float)
    }

    private val session = MediaSession(context, "Soundbound").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = callbacks.onPlay()
            override fun onPause() = callbacks.onPause()
            override fun onStop() = callbacks.onStop()
            override fun onSkipToNext() = callbacks.onSkipToNext()
            override fun onSkipToPrevious() = callbacks.onSkipToPrevious()
            override fun onFastForward() = callbacks.onFastForward()
            override fun onRewind() = callbacks.onRewind()
            override fun onSeekTo(pos: Long) = callbacks.onSeekTo(pos)
            override fun onSetPlaybackSpeed(speed: Float) = callbacks.onSetSpeed(speed)
        })
        isActive = true
    }

    val token: MediaSession.Token get() = session.sessionToken

    private var lastCoverPath: String? = null
    private var lastCover: Bitmap? = null

    /**
     * Publishes what is being read.
     *
     * [durationMillis] is an estimate for a synthesised book — see ListeningEstimate — which is
     * why it is passed in rather than measured here. Publishing it anyway is deliberate: a
     * listener wants to see how much is left, and an estimate shown as an estimate beats an
     * empty scrubber.
     */
    fun publishMetadata(
        title: String,
        author: String?,
        chapter: String?,
        durationMillis: Long,
        coverFile: File?,
    ) {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMillis.coerceAtLeast(0))

        author?.takeIf { it.isNotBlank() }?.let {
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, it)
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, it)
        }
        chapter?.takeIf { it.isNotBlank() }?.let {
            builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
        }
        coverBitmap(coverFile)?.let {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
        }

        runCatching { session.setMetadata(builder.build()) }
    }

    /**
     * Publishes where playback has reached.
     *
     * The position is a fixed number plus a speed, not a stream of updates: the system
     * extrapolates between publishes, so the lock screen's scrubber moves smoothly while the app
     * sends nothing at all. Re-publishing on every sentence would drain the battery to show the
     * same thing.
     */
    fun publishState(status: PlaybackStatus, positionMillis: Long, speed: Float) {
        val state = when (status) {
            PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            PlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            PlaybackStatus.BUFFERING, PlaybackStatus.PREPARING -> PlaybackState.STATE_BUFFERING
            PlaybackStatus.FINISHED -> PlaybackState.STATE_STOPPED
            PlaybackStatus.ERROR -> PlaybackState.STATE_ERROR
            PlaybackStatus.IDLE -> PlaybackState.STATE_NONE
        }

        // A paused session must report a speed of zero, or the system keeps advancing the
        // scrubber while nothing is being said.
        val reportedSpeed = if (status == PlaybackStatus.PLAYING) speed else 0f

        val builder = PlaybackState.Builder()
            .setState(state, positionMillis.coerceAtLeast(0), reportedSpeed, SystemClock.elapsedRealtime())
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_SET_PLAYBACK_SPEED,
            )

        runCatching { session.setPlaybackState(builder.build()) }
    }

    /**
     * Loads a cover for the lock screen, downscaled.
     *
     * Full-size cover art crosses a process boundary on every metadata update, and Android caps
     * what a session may carry; a 5MB bitmap is silently dropped, leaving no artwork at all.
     */
    private fun coverBitmap(file: File?): Bitmap? {
        if (file == null || !file.isFile) return null
        if (file.absolutePath == lastCoverPath && lastCover != null) return lastCover

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }

        var sample = 1
        while (bounds.outHeight / sample > COVER_MAX_PIXELS || bounds.outWidth / sample > COVER_MAX_PIXELS) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
        lastCoverPath = file.absolutePath
        lastCover = bitmap
        return bitmap
    }

    fun release() {
        lastCover = null
        lastCoverPath = null
        runCatching {
            session.isActive = false
            session.release()
        }
    }

    private companion object {
        const val COVER_MAX_PIXELS = 512
    }
}
