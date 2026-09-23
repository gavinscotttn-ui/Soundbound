package app.soundbound.core.player

import app.soundbound.core.model.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Which of the two players is in charge. */
enum class PlaybackMode { READ_ALOUD, AUDIOBOOK }

/**
 * What is playing, whichever kind of book it is.
 *
 * The two players report rather different things — one knows about sentences and voices, the
 * other about files and timestamps — and almost nothing above them cares which. This is the
 * shape they have in common.
 */
data class PlaybackSnapshot(
    val mode: PlaybackMode = PlaybackMode.READ_ALOUD,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    /** Position and length in milliseconds. Estimated for a synthesised book; exact for a recording. */
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val chapterTitle: String? = null,
    val speed: Float = 1f,
    val sleepTimerMillisRemaining: Long? = null,
    val errorMessage: String? = null,
    /** The sentence being spoken. Empty for a recorded audiobook, which has no text. */
    val currentSentence: String = "",
) {
    val isAudiobook: Boolean get() = mode == PlaybackMode.AUDIOBOOK

    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING

    val fraction: Double
        get() = if (durationMillis <= 0) 0.0 else {
            (positionMillis.toDouble() / durationMillis).coerceIn(0.0, 1.0)
        }

    val remainingMillis: Long get() = (durationMillis - positionMillis).coerceAtLeast(0)

    /**
     * "About 3h 54m left".
     *
     * Hedged for a synthesised book because the figure really is an estimate, and stated plainly
     * for a recording because that one is measured.
     */
    fun remainingLabel(): String? {
        if (durationMillis <= 0) return null
        val duration = ListeningEstimate.describe(remainingMillis)
        return if (isAudiobook) "$duration left" else "About $duration left"
    }
}

/**
 * One transport for both kinds of book.
 *
 * Soundbound plays two quite different things: text it reads aloud itself, and audio somebody
 * else recorded. Keeping two players is right — the machinery genuinely differs — but exposing
 * two of everything above them would not be. The notification, the media session, the lock
 * screen, the player screen and the sleep timer should not each have to ask which sort of book
 * is open and then branch.
 *
 * So this is the one thing they talk to, and the branch happens once, here.
 */
class Playback(
    val readAloud: ReadAloudController,
    val audiobook: AudiobookController,
    scope: CoroutineScope,
) {

    private val _mode = MutableStateFlow(PlaybackMode.READ_ALOUD)
    val mode: StateFlow<PlaybackMode> = _mode

    val snapshot: StateFlow<PlaybackSnapshot> =
        combine(_mode, readAloud.state, audiobook.state) { mode, spoken, recorded ->
            when (mode) {
                PlaybackMode.READ_ALOUD -> PlaybackSnapshot(
                    mode = mode,
                    status = spoken.status,
                    positionMillis = ListeningEstimate.elapsedMillis(spoken.progress, spoken.params.rate),
                    durationMillis = ListeningEstimate.totalMillis(spoken.progress, spoken.params.rate),
                    chapterTitle = spoken.chapterTitle,
                    speed = spoken.params.rate,
                    sleepTimerMillisRemaining = spoken.sleepTimerMillisRemaining,
                    errorMessage = spoken.errorMessage,
                    currentSentence = spoken.currentSentence,
                )

                PlaybackMode.AUDIOBOOK -> PlaybackSnapshot(
                    mode = mode,
                    status = recorded.status,
                    positionMillis = recorded.positionMillis,
                    durationMillis = recorded.durationMillis,
                    chapterTitle = recorded.chapterTitle ?: recorded.trackTitle,
                    speed = recorded.speed,
                    sleepTimerMillisRemaining = recorded.sleepTimerMillisRemaining,
                    errorMessage = recorded.errorMessage,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, PlaybackSnapshot())

    /**
     * Switches which player is in charge, stopping the other.
     *
     * Both share one audio device, so leaving the previous one running would have two books
     * talking over each other.
     */
    suspend fun use(mode: PlaybackMode) {
        if (_mode.value == mode) return
        when (mode) {
            PlaybackMode.READ_ALOUD -> audiobook.stop()
            PlaybackMode.AUDIOBOOK -> readAloud.stop()
        }
        _mode.value = mode
    }

    // ---------------------------------------------------------------- transport

    suspend fun play() = onCurrent({ play() }, { play() })

    suspend fun pause() = onCurrent({ pause() }, { pause() })

    suspend fun togglePlayPause() = onCurrent({ togglePlayPause() }, { togglePlayPause() })

    suspend fun stop() = onCurrent({ stop() }, { stop() })

    /**
     * A step forward or back.
     *
     * A sentence for a synthesised book, because that is its natural unit and there is no
     * timeline to step along. A fixed number of seconds for a recording, which is what every
     * audiobook player does and what the buttons on a headset expect.
     */
    suspend fun skip(direction: Int) = onCurrent(
        { skipSentences(direction) },
        { skipSeconds(direction * SKIP_SECONDS) },
    )

    suspend fun skipChapter(delta: Int) = onCurrent({ skipChapter(delta) }, { skipChapter(delta) })

    suspend fun seekToFraction(fraction: Double) =
        onCurrent({ seekToFraction(fraction) }, { seekToFraction(fraction) })

    suspend fun setSpeed(speed: Float) = onCurrent(
        { setParams(state.value.params.copy(rate = speed)) },
        { setSpeed(speed) },
    )

    // ---------------------------------------------------------------- sleep timer

    fun setSleepTimer(millis: Long?) {
        readAloud.setSleepTimer(if (_mode.value == PlaybackMode.READ_ALOUD) millis else null)
        audiobook.setSleepTimer(if (_mode.value == PlaybackMode.AUDIOBOOK) millis else null)
    }

    fun extendSleepTimer(extraMillis: Long) {
        when (_mode.value) {
            PlaybackMode.READ_ALOUD -> readAloud.extendSleepTimer(extraMillis)
            PlaybackMode.AUDIOBOOK -> audiobook.extendSleepTimer(extraMillis)
        }
    }

    /** Progress through the book, for the library and the covers. */
    fun progress(): ReadingProgress = when (_mode.value) {
        PlaybackMode.READ_ALOUD -> readAloud.state.value.progress
        PlaybackMode.AUDIOBOOK -> audiobook.state.value.let { state ->
            ReadingProgress(
                fraction = state.fraction,
                charactersRead = state.positionMillis,
                charactersTotal = state.durationMillis,
                chapterFraction = state.fraction,
            )
        }
    }

    private suspend inline fun onCurrent(
        spoken: ReadAloudController.() -> Unit,
        recorded: AudiobookController.() -> Unit,
    ) {
        when (_mode.value) {
            PlaybackMode.READ_ALOUD -> readAloud.spoken()
            PlaybackMode.AUDIOBOOK -> audiobook.recorded()
        }
    }

    companion object {
        /** The step a recorded audiobook moves by. Thirty seconds is the near-universal choice. */
        const val SKIP_SECONDS = 30
    }
}
