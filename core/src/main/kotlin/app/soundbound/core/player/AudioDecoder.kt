package app.soundbound.core.player

import app.soundbound.core.audio.AudioClip

/**
 * Turns an encoded audio file into the blocks of PCM the rest of the app deals in.
 *
 * The one part of audiobook playback that cannot live in :core. Decoding MP3, AAC, Opus, FLAC
 * and the rest means either shipping a decoder for each or using the one the platform already
 * has, and the platform's is hardware-accelerated, kept up to date and already handles whatever
 * the device supports. So the format handling is platform code, and everything above it — the
 * timeline, the chapters, the position, the speed control — is not.
 *
 * Implementations are used from one coroutine at a time and need not be thread-safe.
 */
interface AudioDecoder : AutoCloseable {

    val sampleRate: Int

    /** 1 or 2. A stereo recording is kept stereo; see the note on [AudioClip]. */
    val channels: Int

    /**
     * The file's real length, as the decoder measures it.
     *
     * This is the authority. The length worked out at import time from the file's tags is an
     * estimate, and a variable-bitrate MP3 with no Xing header can be out by a noticeable
     * margin; the timeline is corrected from this the first time each file is opened.
     */
    val durationMillis: Long

    /**
     * Moves to [millis] into this file.
     *
     * Lands on the nearest frame the format allows, which for MP3 can be some tens of
     * milliseconds away. Precision beyond that would mean decoding from the start every time.
     */
    fun seekTo(millis: Long)

    /** The next block of audio, or null at the end of the file. */
    fun read(): AudioClip?
}

/** Opens files for decoding. Supplied by the platform. */
interface AudioDecoderFactory {
    /** True if this factory expects to be able to decode the file. */
    fun canDecode(uri: String): Boolean

    /** @throws AudioDecodeException when the file cannot be opened. */
    fun open(uri: String): AudioDecoder
}

/** Thrown when a file cannot be decoded. Carries a message fit to show a human. */
class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
