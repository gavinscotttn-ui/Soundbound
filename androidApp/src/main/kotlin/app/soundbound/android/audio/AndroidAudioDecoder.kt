package app.soundbound.android.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.soundbound.core.audio.AudioClip
import app.soundbound.core.player.AudioDecodeException
import app.soundbound.core.player.AudioDecoder
import app.soundbound.core.player.AudioDecoderFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an audio file using the device's own codecs.
 *
 * Everything the phone can play, Soundbound can play: MP3, AAC in an M4A or M4B, Opus, Vorbis,
 * FLAC, WAV. Shipping decoders for all of those would add tens of megabytes and then lag behind
 * the platform, and the hardware path uses markedly less battery over a nine-hour book.
 *
 * [MediaExtractor] pulls the encoded frames out of the container and [MediaCodec] turns them
 * into PCM. The pair is fiddly — two queues, an end-of-stream flag that has to be threaded
 * through both, and output that arrives in its own time — which is why all of it is confined to
 * this one class behind a three-method interface.
 */
class AndroidAudioDecoder private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    override val sampleRate: Int,
    override val channels: Int,
    override val durationMillis: Long,
) : AudioDecoder {

    private val bufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false

    /**
     * Some codecs report a channel count on the output format that differs from the input —
     * a mono file decoded as stereo, most often. The output format is the one that describes
     * the bytes actually arriving, so it wins once it is known.
     */
    private var outputChannels = channels
    private var outputRate = sampleRate

    override fun seekTo(millis: Long) {
        // SEEK_TO_PREVIOUS_SYNC, not CLOSEST: landing on an earlier keyframe and decoding
        // forward gives correct audio, whereas starting mid-frame gives a burst of noise.
        extractor.seekTo(millis * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        runCatching { codec.flush() }
        inputDone = false
        outputDone = false
    }

    override fun read(): AudioClip? {
        if (outputDone) return null

        while (true) {
            feed()

            when (val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_MICROS)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    outputRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputRate
                    outputChannels =
                        format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Nothing ready yet. If there is also nothing left to feed in, the file is
                    // finished; otherwise go round again.
                    if (inputDone && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        return null
                    }
                    if (inputDone && !hasPendingInput()) {
                        outputDone = true
                        return null
                    }
                }

                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit // Only meaningful before API 21.

                else -> {
                    if (index < 0) continue
                    val clip = drain(index)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (clip != null) return clip
                    if (outputDone) return null
                }
            }
        }
    }

    /** Hands the codec the next chunk of encoded data, if it has room for one. */
    private fun feed() {
        if (inputDone) return
        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_MICROS)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return

        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun hasPendingInput(): Boolean = extractor.sampleTime >= 0

    /**
     * Converts one output buffer to floats.
     *
     * Almost every device produces 16-bit PCM, but some produce floats directly, and a handful
     * of older ones produce 8-bit. The format says which, and guessing wrong turns the book into
     * static.
     */
    private fun drain(index: Int): AudioClip? {
        val buffer = codec.getOutputBuffer(index)
        if (buffer == null || bufferInfo.size <= 0) {
            codec.releaseOutputBuffer(index, false)
            return null
        }

        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        val encoding = codec.outputFormat.getIntegerOrNull("pcm-encoding") ?: ENCODING_PCM_16BIT

        val samples = when (encoding) {
            ENCODING_PCM_FLOAT -> readFloats(buffer)
            ENCODING_PCM_8BIT -> readBytes(buffer)
            else -> readShorts(buffer)
        }

        codec.releaseOutputBuffer(index, false)
        if (samples.isEmpty()) return null
        return AudioClip(samples, outputRate, outputChannels.coerceIn(1, 2))
    }

    private fun readShorts(buffer: ByteBuffer): FloatArray {
        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }

    private fun readFloats(buffer: ByteBuffer): FloatArray {
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(floats.remaining()) { floats.get(it) }
    }

    private fun readBytes(buffer: ByteBuffer): FloatArray =
        FloatArray(buffer.remaining()) { ((buffer.get(buffer.position() + it).toInt() and 0xFF) - 128) / 128f }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_MICROS = 10_000L

        // From AudioFormat; named here so this file does not depend on the constant's location.
        private const val ENCODING_PCM_16BIT = 2
        private const val ENCODING_PCM_8BIT = 3
        private const val ENCODING_PCM_FLOAT = 4

        /** Opens [uri], which may be a file path or a content URI. */
        fun open(context: Context, uri: String): AndroidAudioDecoder {
            val extractor = MediaExtractor()
            try {
                if (uri.startsWith("content://")) {
                    extractor.setDataSource(context, Uri.parse(uri), null)
                } else {
                    extractor.setDataSource(File(uri).absolutePath)
                }
            } catch (error: Throwable) {
                runCatching { extractor.release() }
                throw AudioDecodeException("This file could not be opened", error)
            }

            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            if (track == null) {
                extractor.release()
                throw AudioDecodeException("This file has no audio in it")
            }

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: run {
                    extractor.release()
                    throw AudioDecodeException("This file does not say what kind of audio it holds")
                }

            val codec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (error: Throwable) {
                runCatching { extractor.release() }
                throw AudioDecodeException("This device cannot play $mime", error)
            }

            return AndroidAudioDecoder(
                extractor = extractor,
                codec = codec,
                sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100,
                channels = (format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2).coerceIn(1, 2),
                // In microseconds in the format, and absent from a stream that does not know.
                durationMillis = (format.getLongOrNull(MediaFormat.KEY_DURATION) ?: 0L) / 1000,
            )
        }
    }
}

/** MediaFormat throws rather than returning null for a key it does not have. */
private fun MediaFormat.getIntegerOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.getLongOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

/** Opens audio files with the device's codecs. */
class AndroidAudioDecoders(private val context: Context) : AudioDecoderFactory {

    override fun canDecode(uri: String): Boolean =
        app.soundbound.core.audiobook.AudioFormats.isAudio(uri.substringAfterLast('/'))

    override fun open(uri: String): AudioDecoder = AndroidAudioDecoder.open(context, uri)
}
