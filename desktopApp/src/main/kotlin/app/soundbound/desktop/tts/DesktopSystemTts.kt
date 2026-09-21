package app.soundbound.desktop.tts

import app.soundbound.core.model.VoiceId
import app.soundbound.core.tts.EngineKind
import app.soundbound.core.tts.SpeechParams
import app.soundbound.core.tts.TtsVoice
import app.soundbound.core.tts.VoiceGender
import app.soundbound.core.tts.VoiceQuality
import app.soundbound.core.tts.system.SystemTtsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The desktop's own speech engine.
 *
 * macOS has `say`, which drives the same voices as the system-wide speech feature — including the
 * downloadable premium ones, which are very good indeed. Windows has SAPI, reachable from
 * PowerShell. Linux has espeak-ng or speech-dispatcher if either is installed.
 *
 * Each is driven to render a WAV file rather than to speak directly, so the audio goes through the
 * same pipeline as every other voice and gains the same speed control, highlighting and export.
 */
class DesktopSystemTts(private val workDirectory: File) : SystemTtsBridge {

    private val platform: Platform = Platform.detect()
    private var cachedVoices: List<TtsVoice>? = null

    private enum class Platform { MAC, WINDOWS, LINUX, UNSUPPORTED;

        companion object {
            fun detect(): Platform {
                val os = System.getProperty("os.name").orEmpty().lowercase()
                return when {
                    os.contains("mac") -> MAC
                    os.contains("win") -> WINDOWS
                    os.contains("nux") || os.contains("nix") -> LINUX
                    else -> UNSUPPORTED
                }
            }
        }
    }

    override val isAvailable: Boolean
        get() = when (platform) {
            Platform.MAC -> commandExists("say")
            Platform.WINDOWS -> commandExists("powershell")
            Platform.LINUX -> commandExists("espeak-ng") || commandExists("espeak")
            Platform.UNSUPPORTED -> false
        }

    /** The platforms all apply their own rate; Soundbound applies it instead, for consistency. */
    override val appliesRateNatively: Boolean get() = false

    override suspend fun voices(): List<TtsVoice> = withContext(Dispatchers.IO) {
        cachedVoices?.let { return@withContext it }
        val voices = when (platform) {
            Platform.MAC -> macVoices()
            Platform.WINDOWS -> windowsVoices()
            Platform.LINUX -> linuxVoices()
            Platform.UNSUPPORTED -> emptyList()
        }
        cachedVoices = voices
        voices
    }

    /** `say -v ?` lists one voice per line: `Daniel              en_GB    # Hello, my name is…` */
    private fun macVoices(): List<TtsVoice> = runCatching {
        val output = run(listOf("say", "-v", "?")) ?: return emptyList()
        output.lineSequence().mapNotNull { line ->
            val match = MAC_VOICE_LINE.find(line) ?: return@mapNotNull null
            val name = match.groupValues[1].trim()
            val locale = match.groupValues[2].trim().replace('_', '-')
            if (name.isEmpty()) return@mapNotNull null
            TtsVoice(
                id = VoiceId("system/mac:$name"),
                displayName = name,
                engine = EngineKind.SYSTEM,
                language = locale,
                gender = VoiceGender.UNSPECIFIED,
                // macOS premium voices are markedly better, and their names say so.
                quality = if (name.contains("(Premium)") || name.contains("(Enhanced)")) {
                    VoiceQuality.HIGH
                } else {
                    VoiceQuality.MEDIUM
                },
                sampleRate = MAC_SAMPLE_RATE,
                isInstalled = true,
            )
        }.toList()
    }.getOrDefault(emptyList())

    private fun windowsVoices(): List<TtsVoice> = runCatching {
        val script = """
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}s.GetInstalledVoices() | ForEach-Object {
              ${'$'}i = ${'$'}_.VoiceInfo
              "${'$'}(${'$'}i.Name)|${'$'}(${'$'}i.Culture.Name)|${'$'}(${'$'}i.Gender)"
            }
        """.trimIndent()
        val output = run(listOf("powershell", "-NoProfile", "-Command", script)) ?: return emptyList()
        output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
            TtsVoice(
                id = VoiceId("system/win:${parts[0]}"),
                displayName = parts[0],
                engine = EngineKind.SYSTEM,
                language = parts[1],
                gender = when (parts.getOrNull(2)?.lowercase()) {
                    "female" -> VoiceGender.FEMININE
                    "male" -> VoiceGender.MASCULINE
                    else -> VoiceGender.UNSPECIFIED
                },
                quality = VoiceQuality.MEDIUM,
                sampleRate = WINDOWS_SAMPLE_RATE,
                isInstalled = true,
            )
        }.toList()
    }.getOrDefault(emptyList())

    private fun linuxVoices(): List<TtsVoice> = runCatching {
        val binary = if (commandExists("espeak-ng")) "espeak-ng" else "espeak"
        val output = run(listOf(binary, "--voices")) ?: return emptyList()
        output.lineSequence().drop(1).mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size < 4) return@mapNotNull null
            val language = columns[1]
            val name = columns.getOrNull(3) ?: return@mapNotNull null
            TtsVoice(
                id = VoiceId("system/espeak:$name"),
                displayName = name,
                engine = EngineKind.SYSTEM,
                language = language,
                quality = VoiceQuality.LOW,
                sampleRate = LINUX_SAMPLE_RATE,
                isInstalled = true,
            )
        }.toList()
    }.getOrDefault(emptyList())

    override suspend fun synthesiseToWav(
        text: String,
        voice: TtsVoice,
        params: SpeechParams,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        if (!workDirectory.isDirectory) workDirectory.mkdirs()
        val output = File(workDirectory, "utterance-${System.nanoTime()}.wav")
        val raw = voice.id.value.removePrefix("system/")

        val command = when {
            raw.startsWith("mac:") -> listOf(
                "say",
                "-v", raw.removePrefix("mac:"),
                // Little-endian 16-bit PCM at a rate the rest of the pipeline understands.
                "--data-format=LEI16@$MAC_SAMPLE_RATE",
                "-o", output.absolutePath,
                text,
            )

            raw.startsWith("win:") -> return@withContext synthesiseWithSapi(
                text = text,
                voiceName = raw.removePrefix("win:"),
                output = output,
            )

            raw.startsWith("espeak:") -> listOf(
                if (commandExists("espeak-ng")) "espeak-ng" else "espeak",
                "-v", raw.removePrefix("espeak:"),
                "-w", output.absolutePath,
                text,
            )

            else -> return@withContext null
        }

        val ran = run(command, timeoutSeconds = 120) != null
        readAndDelete(output, ran)
    }

    private fun synthesiseWithSapi(text: String, voiceName: String, output: File): ByteArray? {
        // The text is passed through a temporary file rather than interpolated into the script:
        // a book contains quotes, dollars and backticks, all of which PowerShell would interpret.
        val textFile = File(workDirectory, "utterance-${System.nanoTime()}.txt")
        return try {
            textFile.writeText(text, Charsets.UTF_8)
            val script = """
                Add-Type -AssemblyName System.Speech
                ${'$'}text = [System.IO.File]::ReadAllText('${textFile.absolutePath.replace("'", "''")}', [System.Text.Encoding]::UTF8)
                ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
                ${'$'}s.SelectVoice('${voiceName.replace("'", "''")}')
                ${'$'}s.SetOutputToWaveFile('${output.absolutePath.replace("'", "''")}')
                ${'$'}s.Speak(${'$'}text)
                ${'$'}s.Dispose()
            """.trimIndent()
            val ran = run(listOf("powershell", "-NoProfile", "-Command", script), timeoutSeconds = 120) != null
            readAndDelete(output, ran)
        } finally {
            textFile.delete()
        }
    }

    private fun readAndDelete(output: File, ran: Boolean): ByteArray? {
        if (!ran || !output.isFile || output.length() < 64) {
            output.delete()
            return null
        }
        val bytes = runCatching { output.readBytes() }.getOrNull()
        output.delete()
        return bytes
    }

    override fun release() {
        runCatching { workDirectory.listFiles()?.forEach { it.delete() } }
    }

    private fun run(command: List<String>, timeoutSeconds: Long = 15): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) null else output
    }.getOrNull()

    private fun commandExists(command: String): Boolean = runCatching {
        val probe = if (platform == Platform.WINDOWS) listOf("where", command) else listOf("which", command)
        val process = ProcessBuilder(probe).redirectErrorStream(true).start()
        process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private companion object {
        const val MAC_SAMPLE_RATE = 22_050
        const val WINDOWS_SAMPLE_RATE = 22_050
        const val LINUX_SAMPLE_RATE = 22_050

        /** `Daniel (Enhanced)   en_GB   # Hello, my name is Daniel.` */
        val MAC_VOICE_LINE = Regex("""^(.{1,60}?)\s{2,}([a-z]{2}[_-][A-Z0-9]{2,3})\s""")
    }
}
