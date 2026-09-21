package app.soundbound.core.export

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.PsyModel
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib

/** How an exported MP3 is encoded. */
data class Mp3Settings(
    /** Constant bit rate in kbit/s. 64 is ample for a mono voice; 96 is generous. */
    val bitrateKbps: Int = 64,
    /** Use variable bit rate, which is smaller at the same quality but less widely seekable. */
    val variableBitRate: Boolean = false,
    /** VBR quality, 0 best to 9 worst. Ignored unless [variableBitRate]. */
    val vbrQuality: Int = 4,
    /** Encoder effort, 0 slowest and best to 9 fastest. 2 is the usual sweet spot. */
    val encoderQuality: Int = 2,
) {
    fun coerced() = copy(
        bitrateKbps = bitrateKbps.coerceIn(32, 320),
        vbrQuality = vbrQuality.coerceIn(0, 9),
        encoderQuality = encoderQuality.coerceIn(0, 9),
    )
}

/**
 * Encodes mono audio to MP3 with LAME.
 *
 * The encoder is jump3r, a Java port of LAME, driven through its low-level API rather than its
 * `LameEncoder` convenience wrapper. That wrapper's constructor takes a
 * `javax.sound.sampled.AudioFormat`, which does not exist on Android — so using it would have
 * meant either shipping a native LAME for four architectures or offering MP3 export on the
 * desktop only. Wiring the modules by hand costs thirty lines and the same encoder then runs
 * on both, entirely offline.
 */
class Mp3Encoder(
    private val sampleRate: Int,
    settings: Mp3Settings = Mp3Settings(),
) : AutoCloseable {

    private val lame = Lame()
    private val vbrTag = VBRTag()
    private val flags: LameGlobalFlags
    private var closed = false

    /** True when a Xing/LAME header frame was reserved and needs patching after the flush. */
    val writesVbrHeader: Boolean = settings.variableBitRate

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val safe = settings.coerced()

        // The module graph, in the order jump3r's own wrapper builds it. Every one of these has
        // to be connected before lame_init_params, or encoding fails with a null dereference
        // somewhere deep in the psychoacoustic model.
        val gainAnalysis = GainAnalysis()
        val bitStream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val psyModel = PsyModel()
        val mpg = MPGLib()
        val mpgInterface = Interface()
        val common = Common()

        lame.setModules(gainAnalysis, bitStream, presets, quantizePvt, quantize, vbrTag, version, id3, mpg)
        bitStream.setModules(gainAnalysis, mpg, version, vbrTag)
        id3.setModules(bitStream, version)
        presets.setModules(lame)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, psyModel)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbrTag.setModules(lame, bitStream, version)
        mpg.setModules(mpgInterface, common)
        mpgInterface.setModules(vbrTag, common)
        lame.enc.setModules(bitStream, psyModel, quantizePvt, vbrTag)

        flags = lame.lame_init()
            ?: throw IllegalStateException("The MP3 encoder could not be initialised.")

        flags.num_channels = 1
        flags.in_samplerate = sampleRate
        flags.mode = MPEGMode.MONO
        flags.quality = safe.encoderQuality
        // Soundbound writes its own ID3v2 tag, which gives it control over the artwork and the
        // chapter naming; LAME's automatic tag would be written twice over.
        flags.write_id3tag_automatic = false
        flags.bWriteVbrTag = safe.variableBitRate

        if (safe.variableBitRate) {
            flags.VBR = VbrMode.vbr_mtrh
            flags.VBR_q = safe.vbrQuality
        } else {
            flags.VBR = VbrMode.vbr_off
            flags.brate = safe.bitrateKbps
        }

        val result = lame.lame_init_params(flags)
        require(result >= 0) { "The MP3 encoder rejected these settings (code $result)." }
    }

    /**
     * Encodes a block of samples, appending the result to [sink].
     *
     * Samples are floats in −1..1, which is what the synthesiser produces. LAME's integer entry
     * point treats each sample as using the full 32-bit range, so they are scaled to 16-bit and
     * shifted up by sixteen bits; getting that shift wrong produces an MP3 that is either silent
     * or savagely clipped, which is why there is a round-trip test for it.
     */
    fun encode(samples: FloatArray, sink: (ByteArray, Int) -> Unit) {
        check(!closed) { "This encoder has already been closed." }
        if (samples.isEmpty()) return

        var offset = 0
        while (offset < samples.size) {
            val count = minOf(BLOCK_SAMPLES, samples.size - offset)
            val left = IntArray(count)
            for (i in 0 until count) {
                val clamped = samples[offset + i].coerceIn(-1f, 1f)
                left[i] = (clamped * 32767f).toInt() shl 16
            }
            val buffer = ByteArray(estimateOutputSize(count))
            val written = lame.lame_encode_buffer_int(flags, left, left, count, buffer, 0, buffer.size)
            if (written < 0) throw IllegalStateException("MP3 encoding failed (code $written).")
            if (written > 0) sink(buffer, written)
            offset += count
        }
    }

    /**
     * The Xing/LAME header frame, once encoding has finished.
     *
     * A VBR file begins with a zero-filled placeholder frame which has to be overwritten with
     * this once the total length and the seek table are known. Skipping the patch leaves a file
     * that plays but reports the wrong duration and cannot be seeked — which for a six-hour
     * audiobook is worse than not offering variable bit rate at all.
     *
     * Returns null for a constant bit rate stream, which needs no such frame.
     */
    fun lameTagFrame(): ByteArray? {
        if (!writesVbrHeader) return null
        val buffer = ByteArray(VBRTag.MAXFRAMESIZE)
        val written = runCatching { vbrTag.getLameTagFrame(flags, buffer) }.getOrDefault(0)
        return if (written <= 0) null else buffer.copyOf(written)
    }

    /** Flushes the final partial frame. Must be called, or the last second or so is lost. */
    fun flush(): ByteArray {
        check(!closed) { "This encoder has already been closed." }
        val buffer = ByteArray(FLUSH_BUFFER)
        val written = lame.lame_encode_flush(flags, buffer, 0, buffer.size)
        return if (written <= 0) ByteArray(0) else buffer.copyOf(written)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { lame.lame_close(flags) }
    }

    private fun estimateOutputSize(sampleCount: Int): Int =
        // LAME's own guidance: 1.25 times the sample count, plus 7,200 bytes of slack.
        (sampleCount * 5 / 4) + 7_200

    companion object {
        private const val BLOCK_SAMPLES = 4_096
        private const val FLUSH_BUFFER = 8_192

        /** Sample rates MP3 supports. Anything else has to be resampled first. */
        val SUPPORTED_SAMPLE_RATES = intArrayOf(
            8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000,
        )

        /** The nearest supported rate at or above [rate], so nothing is thrown away resampling. */
        fun nearestSupportedRate(rate: Int): Int =
            SUPPORTED_SAMPLE_RATES.firstOrNull { it >= rate } ?: SUPPORTED_SAMPLE_RATES.last()

        fun isSupportedRate(rate: Int): Boolean = rate in SUPPORTED_SAMPLE_RATES.toList()
    }
}
