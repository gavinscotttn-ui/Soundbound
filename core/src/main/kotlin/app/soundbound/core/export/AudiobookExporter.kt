package app.soundbound.core.export

import app.soundbound.core.audio.AudioClip
import app.soundbound.core.audio.Dsp
import app.soundbound.core.audio.WavCodec
import app.soundbound.core.book.BookSource
import app.soundbound.core.model.Book
import app.soundbound.core.model.ChapterIndex
import app.soundbound.core.player.SpeechPlan
import app.soundbound.core.player.SpeechPlanBuilder
import app.soundbound.core.player.SpeechPlanOptions
import app.soundbound.core.tts.LoadedVoice
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.tts.VoiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** What an export produces. */
enum class ExportFormat(val displayName: String, val extension: String) {
    /** MP3, encoded with LAME. Plays anywhere, including in a car. */
    MP3("MP3", "mp3"),

    /** Uncompressed WAV. Large, but lossless and needs no encoder. */
    WAV("WAV", "wav"),
}

/** How an export is split up. */
enum class ExportGrouping(val displayName: String) {
    /** One file per chapter, which is what audiobook players expect. */
    PER_CHAPTER("One file per chapter"),

    /** A single file for the whole selection. */
    SINGLE_FILE("One file for everything"),
}

/** An export the user has asked for. */
data class ExportRequest(
    val book: Book,
    /** Chapters to export, in order. Empty means the whole book. */
    val chapters: List<ChapterIndex> = emptyList(),
    val outputDirectory: File,
    val format: ExportFormat = ExportFormat.MP3,
    val grouping: ExportGrouping = ExportGrouping.PER_CHAPTER,
    val mp3Settings: Mp3Settings = Mp3Settings(),
    val voice: TtsVoice,
    val speechParams: SpeechParams = SpeechParams(),
    val planOptions: SpeechPlanOptions = SpeechPlanOptions(),
    /** Cover art to embed, as encoded image bytes. */
    val artwork: ByteArray? = null,
    val artworkMimeType: String = "image/jpeg",
    /** Normalise each file so chapters do not vary in loudness. */
    val normaliseLoudness: Boolean = true,
)

/** Progress reported while exporting. */
sealed interface ExportProgress {
    data class Preparing(val chapterCount: Int) : ExportProgress

    data class Rendering(
        val chapterIndex: Int,
        val chapterCount: Int,
        val chapterTitle: String?,
        val unitsDone: Int,
        val unitsTotal: Int,
    ) : ExportProgress {
        /** Overall fraction across the whole export, not just this chapter. */
        val fraction: Float
            get() {
                if (chapterCount <= 0) return 0f
                val withinChapter = if (unitsTotal <= 0) 0f else unitsDone.toFloat() / unitsTotal
                return ((chapterIndex + withinChapter) / chapterCount).coerceIn(0f, 1f)
            }
    }

    data class Wrote(val file: File, val durationMillis: Long) : ExportProgress
    data class Finished(val files: List<File>, val totalDurationMillis: Long) : ExportProgress
    data class Failed(val reason: String, val cause: Throwable? = null) : ExportProgress
}

/**
 * Renders a book to audio files.
 *
 * Unlike playback this runs flat out — synthesis is not throttled to real time, so a chapter that
 * takes fifteen minutes to listen to takes a fraction of that to export on a modern phone. Audio
 * is streamed straight to the file as it is produced and never accumulated, because a six-hour
 * book held in memory as 32-bit floats is about two gigabytes.
 *
 * Nothing here needs a network connection. The whole point of the feature is a book, in the voice
 * you chose, as a file you own.
 */
class AudiobookExporter(
    private val voiceRegistry: VoiceRegistry,
) {

    fun export(source: BookSource, request: ExportRequest): Flow<ExportProgress> = flow {
        val chapters = request.chapters.ifEmpty { source.chapters.map { it.index } }
        if (chapters.isEmpty()) {
            emit(ExportProgress.Failed("This book has no chapters to export."))
            return@flow
        }
        if (!request.outputDirectory.isDirectory && !request.outputDirectory.mkdirs()) {
            emit(ExportProgress.Failed("The export folder could not be created."))
            return@flow
        }

        emit(ExportProgress.Preparing(chapters.size))

        val voice = try {
            voiceRegistry.load(request.voice)
        } catch (e: Exception) {
            emit(ExportProgress.Failed("The voice \"${request.voice.displayName}\" could not be loaded.", e))
            return@flow
        }

        // MP3 accepts only a fixed set of sample rates, so a 22.05 kHz Piper voice is fine while a
        // 16 kHz one is resampled up rather than silently refused.
        val outputRate = when (request.format) {
            ExportFormat.MP3 ->
                if (Mp3Encoder.isSupportedRate(voice.sampleRate)) voice.sampleRate
                else Mp3Encoder.nearestSupportedRate(voice.sampleRate)

            ExportFormat.WAV -> voice.sampleRate
        }

        val planBuilder = SpeechPlanBuilder(request.planOptions)
        val written = ArrayList<File>()
        var totalDuration = 0L
        val partials = ArrayList<File>()

        try {
            when (request.grouping) {
                ExportGrouping.PER_CHAPTER -> {
                    chapters.forEachIndexed { position, chapter ->
                        val plan = planFor(source, planBuilder, chapter)
                        val title = plan.first
                        val file = File(
                            request.outputDirectory,
                            fileName(request.book, title, position + 1, chapters.size, request.format),
                        )
                        val partial = File(file.parentFile, file.name + ".part")
                        partials.add(partial)

                        val duration = writeOne(
                            target = partial,
                            plans = listOf(plan.second),
                            voice = voice,
                            request = request,
                            outputRate = outputRate,
                            tags = Id3.Tags(
                                title = title ?: "Chapter ${position + 1}",
                                artist = request.book.metadata.authorLine,
                                album = request.book.metadata.title,
                                albumArtist = request.book.metadata.authorLine,
                                trackNumber = position + 1,
                                trackTotal = chapters.size,
                                year = request.book.metadata.publishedDate?.take(4),
                                comment = "Read by ${request.voice.displayName} · Soundbound",
                                artwork = request.artwork,
                                artworkMimeType = request.artworkMimeType,
                            ),
                            onUnit = { done, total ->
                                emit(
                                    ExportProgress.Rendering(
                                        chapterIndex = position,
                                        chapterCount = chapters.size,
                                        chapterTitle = title,
                                        unitsDone = done,
                                        unitsTotal = total,
                                    ),
                                )
                            },
                        )

                        finalise(partial, file)
                        partials.remove(partial)
                        written.add(file)
                        totalDuration += duration
                        emit(ExportProgress.Wrote(file, duration))
                    }
                }

                ExportGrouping.SINGLE_FILE -> {
                    val plans = chapters.map { planFor(source, planBuilder, it) }
                    val file = File(
                        request.outputDirectory,
                        fileName(request.book, null, null, null, request.format),
                    )
                    val partial = File(file.parentFile, file.name + ".part")
                    partials.add(partial)

                    val totalUnits = plans.sumOf { it.second.units.size }
                    var unitsSoFar = 0

                    val duration = writeOne(
                        target = partial,
                        plans = plans.map { it.second },
                        voice = voice,
                        request = request,
                        outputRate = outputRate,
                        tags = Id3.Tags(
                            title = request.book.metadata.title,
                            artist = request.book.metadata.authorLine,
                            album = request.book.metadata.title,
                            albumArtist = request.book.metadata.authorLine,
                            trackNumber = 1,
                            trackTotal = 1,
                            year = request.book.metadata.publishedDate?.take(4),
                            comment = "Read by ${request.voice.displayName} · Soundbound",
                            artwork = request.artwork,
                            artworkMimeType = request.artworkMimeType,
                        ),
                        onUnit = { done, _ ->
                            unitsSoFar = done
                            emit(
                                ExportProgress.Rendering(
                                    chapterIndex = 0,
                                    chapterCount = 1,
                                    chapterTitle = request.book.metadata.title,
                                    unitsDone = unitsSoFar,
                                    unitsTotal = totalUnits,
                                ),
                            )
                        },
                    )

                    finalise(partial, file)
                    partials.remove(partial)
                    written.add(file)
                    totalDuration += duration
                    emit(ExportProgress.Wrote(file, duration))
                }
            }

            emit(ExportProgress.Finished(written, totalDuration))
        } catch (e: CancellationException) {
            partials.forEach { it.delete() }
            throw e
        } catch (e: Exception) {
            partials.forEach { it.delete() }
            emit(ExportProgress.Failed(e.message ?: "The export failed.", e))
        }
    }.flowOn(Dispatchers.Default)

    private fun planFor(
        source: BookSource,
        builder: SpeechPlanBuilder,
        chapter: ChapterIndex,
    ): Pair<String?, SpeechPlan> {
        val content = source.chapterContent(chapter)
        return content.title to builder.build(content)
    }

    /**
     * Renders and writes one output file.
     *
     * @return the audio duration in milliseconds.
     */
    private suspend fun writeOne(
        target: File,
        plans: List<SpeechPlan>,
        voice: LoadedVoice,
        request: ExportRequest,
        outputRate: Int,
        tags: Id3.Tags,
        onUnit: suspend (done: Int, total: Int) -> Unit,
    ): Long {
        val totalUnits = plans.sumOf { it.units.size }
        var samplesWritten = 0L

        when (request.format) {
            ExportFormat.MP3 -> {
                val encoder = Mp3Encoder(outputRate, request.mp3Settings)
                var vbrPlaceholderLength = 0
                BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { out ->
                    // The ID3v2 tag goes first, so players show the metadata before decoding.
                    out.write(Id3.build(tags))

                    var firstWrite = true
                    val sink: (ByteArray, Int) -> Unit = { buffer, length ->
                        if (firstWrite && encoder.writesVbrHeader) {
                            // Remember how long the placeholder frame was, so it can be patched.
                            vbrPlaceholderLength = length
                            firstWrite = false
                        }
                        out.write(buffer, 0, length)
                    }

                    samplesWritten = renderAll(plans, voice, request, outputRate, onUnit) { clip ->
                        encoder.encode(clip.samples, sink)
                    }
                    out.write(encoder.flush())
                }
                patchVbrHeader(target, encoder, tags, vbrPlaceholderLength)
                encoder.close()
            }

            ExportFormat.WAV -> {
                // WAV needs the total length in its header, so the audio is written first with a
                // placeholder and the header is corrected afterwards.
                BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { out ->
                    out.write(ByteArray(WAV_HEADER_BYTES))
                    samplesWritten = renderAll(plans, voice, request, outputRate, onUnit) { clip ->
                        out.write(clip.toPcm16())
                    }
                }
                rewriteWavHeader(target, outputRate, samplesWritten)
            }
        }

        onUnit(totalUnits, totalUnits)
        return if (outputRate <= 0) 0 else samplesWritten * 1000 / outputRate
    }

    /** Synthesises every utterance in order, handing each finished clip to [write]. */
    private suspend fun renderAll(
        plans: List<SpeechPlan>,
        voice: LoadedVoice,
        request: ExportRequest,
        outputRate: Int,
        onUnit: suspend (Int, Int) -> Unit,
        write: (AudioClip) -> Unit,
    ): Long {
        val totalUnits = plans.sumOf { it.units.size }
        var done = 0
        var samples = 0L

        plans.forEachIndexed { planIndex, plan ->
            plan.units.forEach { unit ->
                kotlin.coroutines.coroutineContext.ensureActive()

                var clip = runCatching { voice.synthesise(unit.spokenText, request.speechParams) }
                    .getOrElse { AudioClip.silence(150, voice.sampleRate) }

                if (request.normaliseLoudness) clip = Dsp.normalisePeak(clip, targetPeak = 0.89f, maxGain = 3f)
                clip = Dsp.applyEdgeFades(clip)
                if (clip.sampleRate != outputRate) clip = Dsp.resample(clip, outputRate)

                if (!clip.isEmpty) {
                    write(clip)
                    samples += clip.samples.size
                }

                if (unit.trailingPauseMillis > 0) {
                    val pause = AudioClip.silence(unit.trailingPauseMillis, outputRate)
                    write(pause)
                    samples += pause.samples.size
                }

                done++
                // Reporting every sentence would emit thousands of updates for a novel and do
                // nothing a progress bar can show; every few units is smooth enough.
                if (done % PROGRESS_EVERY == 0 || done == totalUnits) onUnit(done, totalUnits)
            }

            // A beat between chapters when everything lands in one file, so they do not run
            // together.
            if (planIndex < plans.lastIndex) {
                val gap = AudioClip.silence(CHAPTER_GAP_MILLIS, outputRate)
                write(gap)
                samples += gap.samples.size
            }
        }
        return samples
    }

    /** Overwrites the reserved placeholder frame with the real Xing/LAME header. */
    private fun patchVbrHeader(file: File, encoder: Mp3Encoder, tags: Id3.Tags, placeholderLength: Int) {
        if (!encoder.writesVbrHeader || placeholderLength <= 0) return
        val frame = encoder.lameTagFrame() ?: return
        if (frame.size != placeholderLength) return
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(Id3.build(tags).size.toLong())
                raf.write(frame)
            }
        }
    }

    private fun rewriteWavHeader(file: File, sampleRate: Int, sampleCount: Long) {
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val header = java.io.ByteArrayOutputStream()
                okio.Buffer().also { buffer ->
                    WavCodec.writeHeader(buffer, sampleRate, sampleCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    header.write(buffer.readByteArray())
                }
                raf.seek(0)
                raf.write(header.toByteArray())
            }
        }
    }

    private fun finalise(partial: File, target: File) {
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    /**
     * A filename that sorts correctly and survives every filesystem.
     *
     * The track number is zero-padded because otherwise chapter 10 sorts before chapter 2 in every
     * file manager and most car stereos, which is precisely the sort of thing that ruins a
     * six-hour listen.
     */
    private fun fileName(
        book: Book,
        chapterTitle: String?,
        track: Int?,
        trackTotal: Int?,
        format: ExportFormat,
    ): String {
        val bookPart = sanitise(book.metadata.title).take(60)
        val prefix = if (track != null && trackTotal != null) {
            val width = trackTotal.toString().length.coerceAtLeast(2)
            track.toString().padStart(width, '0') + " - "
        } else {
            ""
        }
        val titlePart = chapterTitle?.let { sanitise(it).take(60) }.orEmpty()
        val middle = if (titlePart.isEmpty()) bookPart else "$bookPart - $titlePart"
        return "$prefix$middle.${format.extension}"
    }

    private fun sanitise(name: String): String = name
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .ifEmpty { "Untitled" }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val CHAPTER_GAP_MILLIS = 1_500
        const val PROGRESS_EVERY = 4
    }
}
