package app.soundbound.desktop.audio

import app.soundbound.core.audio.AudioClip
import app.soundbound.core.audiobook.AudioFormats
import app.soundbound.core.audiobook.Mpeg
import app.soundbound.core.player.AudioDecodeException
import app.soundbound.core.player.AudioDecoder
import app.soundbound.core.player.AudioDecoderFactory
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.MP3Data
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Decodes audiobook files on the desktop.
 *
 * Two routes. Anything Java's own sound system understands — WAV, AIFF, AU, and whatever else a
 * service provider has been installed for — goes through that, converted to 16-bit PCM. MP3 goes
 * through jump3r, the same pure-Java LAME port the app already uses to export.
 *
 * AAC, which is what an `.m4b` or `.m4a` holds, has no decoder in a plain Java runtime and is
 * refused with a message that says so. Android plays those perfectly well, because the decoding
 * there is the phone's own; the desktop would need a bundled decoder, and pretending otherwise
 * would mean a book that imports and then makes no sound.
 */
sealed class DesktopAudioDecoder : AudioDecoder {

    companion object {
        /** Formats this decoder can handle, as lowercase extensions. */
        val SUPPORTED = setOf("mp3", "wav", "wave", "aif", "aiff", "au", "snd")

        fun open(uri: String): AudioDecoder {
            val file = File(uri)
            if (!file.isFile) throw AudioDecodeException("This file is missing: ${file.name}")

            return when (AudioFormats.extensionOf(file.name)) {
                "mp3" -> Mp3Decoder(file)
                in SUPPORTED -> SampledDecoder(file)
                "m4a", "m4b", "aac", "m4p" -> throw AudioDecodeException(
                    "The desktop app cannot play AAC audio (${file.name}). Android plays it with " +
                        "the phone's own decoder; on the desktop, convert the book to MP3.",
                )

                else -> throw AudioDecodeException("Soundbound cannot play ${file.name}")
            }
        }
    }
}

/**
 * Anything Java's sound system can open.
 *
 * The stream is converted to 16-bit signed PCM at the source's own rate, because that is the one
 * format every provider can produce and the only one worth writing a reader for.
 */
private class SampledDecoder(private val file: File) : DesktopAudioDecoder() {

    private var stream: AudioInputStream = openStream()
    private var format: AudioFormat = stream.format

    override val sampleRate: Int get() = format.sampleRate.toInt()
    override val channels: Int get() = format.channels.coerceIn(1, 2)

    override val durationMillis: Long = run {
        val frames = stream.frameLength
        if (frames > 0 && format.frameRate > 0) {
            (frames * 1000.0 / format.frameRate).toLong()
        } else {
            0L
        }
    }

    private fun openStream(): AudioInputStream {
        val source = try {
            AudioSystem.getAudioInputStream(file)
        } catch (error: Throwable) {
            throw AudioDecodeException("This file could not be read: ${file.name}", error)
        }
        val wanted = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            source.format.sampleRate,
            16,
            source.format.channels,
            source.format.channels * 2,
            source.format.sampleRate,
            false, // little-endian, matching the rest of the pipeline
        )
        return if (AudioSystem.isConversionSupported(wanted, source.format)) {
            AudioSystem.getAudioInputStream(wanted, source)
        } else {
            source
        }
    }

    /**
     * Seeks by reopening and skipping.
     *
     * [AudioInputStream] cannot seek backwards, and skipping forward from the start is exact for
     * these formats because every frame is the same size. Reopening costs a file handle and a
     * few milliseconds, which is nothing against a nine-hour book.
     */
    override fun seekTo(millis: Long) {
        runCatching { stream.close() }
        stream = openStream()
        format = stream.format
        val frame = (millis * format.frameRate / 1000).toLong().coerceAtLeast(0)
        val bytes = frame * format.frameSize
        var skipped = 0L
        while (skipped < bytes) {
            val step = stream.skip(bytes - skipped)
            if (step <= 0) break
            skipped += step
        }
    }

    override fun read(): AudioClip? {
        val frameSize = format.frameSize.coerceAtLeast(1)
        val buffer = ByteArray(BLOCK_FRAMES * frameSize)
        var filled = 0
        while (filled < buffer.size) {
            val read = stream.read(buffer, filled, buffer.size - filled)
            if (read <= 0) break
            filled += read
        }
        if (filled <= 0) return null

        val frames = filled / frameSize
        val samples = FloatArray(frames * channels)
        var index = 0
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val at = frame * frameSize + channel * 2
                val low = buffer[at].toInt() and 0xFF
                val high = buffer[at + 1].toInt()
                samples[index++] = ((high shl 8) or low).toShort() / 32768f
            }
        }
        return AudioClip(samples, sampleRate, channels)
    }

    override fun close() {
        runCatching { stream.close() }
    }

    private companion object {
        const val BLOCK_FRAMES = 16_384
    }
}

/**
 * MP3, through jump3r's decoder.
 *
 * Fed in chunks and drained until it runs dry after each one. Feeding a chunk and reading a
 * single frame — the obvious way to write this — silently drops most of the file, and drops all
 * of a variable-bitrate file whose first frame is the zero-filled Xing placeholder.
 */
private class Mp3Decoder(private val file: File) : DesktopAudioDecoder() {

    /**
     * The file is read a chunk at a time rather than loaded whole.
     *
     * An audiobook as one MP3 runs to several hundred megabytes, and holding all of it in memory
     * to play it would be absurd — worse on a machine that has several books open. Only the head
     * is read up front, which is all that is needed to find the first frame and work out the
     * length.
     */
    private val handle: RandomAccessFile = try {
        RandomAccessFile(file, "r")
    } catch (error: Throwable) {
        throw AudioDecodeException("This file could not be read: ${file.name}", error)
    }

    private val head: ByteArray = ByteArray(minOf(HEAD_BYTES.toLong(), handle.length()).toInt())
        .also { runCatching { handle.readFully(it) } }

    private val audioStart = audioStart(head)

    private val firstFrame = Mpeg.firstFrame(head, audioStart)
        ?: throw AudioDecodeException("This does not appear to be an MP3: ${file.name}")

    override val sampleRate: Int = firstFrame.sampleRate
    override val channels: Int = firstFrame.channels

    /**
     * The length, from the header where there is one, and otherwise from the bitrate and the
     * file's real size — which the head alone cannot give.
     */
    override val durationMillis: Long = run {
        val fileLength = runCatching { handle.length() }.getOrDefault(0L)
        val fromHeader = Mpeg.duration(head, audioStart)
        // The head-only figure is right when a Xing header supplied it, and far too short when
        // it came from measuring the head, so that case is recomputed against the whole file.
        val headIsWhole = head.size.toLong() >= fileLength
        when {
            headIsWhole -> fromHeader ?: 0L
            firstFrame.bitrateKbps > 0 ->
                ((fileLength - audioStart) * 8 / firstFrame.bitrateKbps).coerceAtLeast(0)

            else -> fromHeader ?: 0L
        }
    }

    private val mpg = MPGLib()
    private val left = ShortArray(1 shl 16)
    private val right = ShortArray(1 shl 16)
    private val info = MP3Data()

    /**
     * jump3r's decoder handle. Its type is internal to the library, so it is left inferred
     * rather than named.
     */
    private var stream = createStream()
    private var offset = firstFrame.offset.toLong()
    private var finished = false

    init {
        runCatching { handle.seek(offset) }
    }

    private fun createStream() = run {
        val mpgInterface = Interface()
        val common = Common()
        val vbrTag = VBRTag()
        BitStream().setModules(GainAnalysis(), mpg, Version(), vbrTag)
        mpg.setModules(mpgInterface, common)
        mpgInterface.setModules(vbrTag, common)
        mpg.hip_decode_init()
    }

    private fun restartAt(byteOffset: Long) {
        stream = createStream()
        offset = byteOffset
        finished = false
        runCatching { handle.seek(offset) }
    }

    /**
     * Seeks by working out where in the file that moment lives.
     *
     * Exact for a constant-bitrate file. For a variable-bitrate one it is an estimate from the
     * average rate, so the landing point can be a second or two out — acceptable for a book,
     * and the alternative is decoding from the beginning every time, which for a nine-hour file
     * would take longer than the listener is prepared to wait.
     */
    override fun seekTo(millis: Long) {
        val length = runCatching { handle.length() }.getOrDefault(0L)
        val audioBytes = (length - audioStart).coerceAtLeast(0)
        val target = if (durationMillis <= 0 || audioBytes <= 0) {
            audioStart.toLong()
        } else {
            audioStart + (audioBytes * millis.toDouble() / durationMillis).toLong()
        }

        // Land on a real frame boundary: starting mid-frame gives a burst of noise. A window is
        // read around the estimate and searched, rather than searching the whole file.
        val from = target.coerceIn(audioStart.toLong(), (length - 4).coerceAtLeast(0))
        val window = ByteArray(minOf(SEEK_WINDOW_BYTES.toLong(), length - from).toInt())
        val found = runCatching {
            handle.seek(from)
            handle.readFully(window)
            Mpeg.firstFrame(window, 0)
        }.getOrNull()

        restartAt(from + (found?.offset ?: 0))
    }

    override fun read(): AudioClip? {
        if (finished) return null
        val handle = stream
        val collected = ArrayList<Float>(BLOCK_SAMPLES)

        while (collected.size < BLOCK_SAMPLES) {
            val chunk = readChunk()

            var produced = mpg.hip_decode1_headers(handle, chunk, chunk.size, left, right, info)
            if (produced <= 0 && chunk.isEmpty()) {
                finished = true
                break
            }
            while (produced > 0) {
                for (i in 0 until produced) {
                    collected.add(left[i] / 32768f)
                    if (channels == 2) collected.add(right[i] / 32768f)
                }
                produced = mpg.hip_decode1_headers(handle, EMPTY, 0, left, right, info)
            }
        }

        if (collected.isEmpty()) return null
        return AudioClip(collected.toFloatArray(), sampleRate, channels)
    }

    /** The next chunk of encoded data, or an empty array once the file is exhausted. */
    private fun readChunk(): ByteArray {
        val buffer = ByteArray(CHUNK_BYTES)
        val read = runCatching { handle.read(buffer) }.getOrDefault(-1)
        if (read <= 0) return EMPTY
        offset += read
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    override fun close() {
        finished = true
        runCatching { handle.close() }
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val CHUNK_BYTES = 1 shl 12
        const val BLOCK_SAMPLES = 1 shl 16

        /** Enough of the file to find the first frame past even a very large tag. */
        const val HEAD_BYTES = 512 * 1024

        /** How much to read around a seek estimate when looking for a frame boundary. */
        const val SEEK_WINDOW_BYTES = 64 * 1024
    }
}

/** Where the audio starts: past an ID3v2 tag, if there is one. */
private fun audioStart(bytes: ByteArray): Int {
    if (bytes.size < 10) return 0
    if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() ||
        bytes[2] != '3'.code.toByte()
    ) {
        return 0
    }
    val size = ((bytes[6].toInt() and 0x7F) shl 21) or
        ((bytes[7].toInt() and 0x7F) shl 14) or
        ((bytes[8].toInt() and 0x7F) shl 7) or
        (bytes[9].toInt() and 0x7F)
    return (size + 10).coerceIn(0, bytes.size)
}

/** Opens audiobook files on the desktop. */
class DesktopAudioDecoders : AudioDecoderFactory {

    override fun canDecode(uri: String): Boolean =
        AudioFormats.extensionOf(uri.substringAfterLast('/')) in DesktopAudioDecoder.SUPPORTED

    override fun open(uri: String): AudioDecoder = DesktopAudioDecoder.open(uri)
}
