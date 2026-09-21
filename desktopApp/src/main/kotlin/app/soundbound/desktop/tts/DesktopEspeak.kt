package app.soundbound.desktop.tts

import app.soundbound.core.tts.g2p.NativeEspeakBridge
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * Optional espeak-ng, bound with JNA.
 *
 * Worth having because Piper's models were trained on espeak-ng's phonemes, so matching it is
 * audibly better than any approximation, and because it brings forty-odd languages the built-in
 * English dictionary cannot. It is not bundled: on macOS it is a `brew install espeak-ng` away, on
 * Windows a `.dll` next to the app, and shipping it would mean carrying binaries for every
 * platform in an app most of whose users read English.
 */
object DesktopEspeak {

    private const val OUTPUT_PHONEMES = 0x02
    private const val AUDIO_OUTPUT_SYNCHRONOUS = 0x02
    private const val ESPEAK_CHARS_UTF8 = 1

    private interface EspeakLibrary : Library {
        fun espeak_Initialize(output: Int, bufferLength: Int, path: String?, options: Int): Int
        fun espeak_SetVoiceByName(name: String): Int
        fun espeak_TextToPhonemes(textPointer: Pointer, textMode: Int, phonemeMode: Int): String?
        fun espeak_Terminate(): Int
    }

    private var library: EspeakLibrary? = null
    private var attempted = false

    /** Returns a bridge if libespeak-ng can be loaded, and null otherwise. */
    @Synchronized
    fun bridgeIfAvailable(): NativeEspeakBridge? {
        if (!attempted) {
            attempted = true
            library = CANDIDATE_NAMES.firstNotNullOfOrNull { name ->
                runCatching { Native.load(name, EspeakLibrary::class.java) }.getOrNull()
            }
        }
        return library?.let { JnaBridge(it) }
    }

    /** Where the data folder usually lives, so the caller need not ask the user for it. */
    fun likelyDataDirectory(): File? = LIKELY_DATA_PATHS
        .map(::File)
        .firstOrNull { it.isDirectory }

    private class JnaBridge(private val library: EspeakLibrary) : NativeEspeakBridge {
        private var initialised = false

        override fun initialise(dataPath: String): Boolean {
            if (initialised) return true
            // A negative return means espeak could not find its data; a sample rate comes back
            // otherwise.
            val rate = runCatching {
                library.espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, dataPath.ifBlank { null }, 0)
            }.getOrDefault(-1)
            initialised = rate > 0
            return initialised
        }

        override fun setVoice(voiceName: String): Boolean =
            runCatching { library.espeak_SetVoiceByName(voiceName) == 0 }.getOrDefault(false)

        /**
         * espeak's `TextToPhonemes` takes a *pointer to a pointer* and advances it as it consumes
         * the text, so the whole utterance needs several calls. The phoneme mode asks for IPA with
         * stress marks, which is what Piper expects.
         */
        override fun textToPhonemes(text: String): String {
            if (!initialised) return ""
            return runCatching {
                val bytes = text.toByteArray(Charsets.UTF_8) + 0
                val textMemory = com.sun.jna.Memory(bytes.size.toLong())
                textMemory.write(0, bytes, 0, bytes.size)

                val pointerHolder = com.sun.jna.Memory(Native.POINTER_SIZE.toLong())
                pointerHolder.setPointer(0, textMemory)

                val out = StringBuilder()
                var guard = 0
                while (guard++ < MAX_CHUNKS) {
                    val chunk = library.espeak_TextToPhonemes(
                        pointerHolder,
                        ESPEAK_CHARS_UTF8,
                        // 0x02 selects IPA; the low bits carry the separator, and 0 means none.
                        IPA_PHONEME_MODE,
                    ) ?: break
                    out.append(chunk)
                    if (pointerHolder.getPointer(0) == null) break
                }
                out.toString().trim()
            }.getOrDefault("")
        }

        override fun isInitialised(): Boolean = initialised

        override fun release() {
            runCatching { library.espeak_Terminate() }
            initialised = false
        }

        private companion object {
            const val MAX_CHUNKS = 512
            const val IPA_PHONEME_MODE = 0x02
        }
    }

    private val CANDIDATE_NAMES = listOf("espeak-ng", "espeak", "libespeak-ng", "libespeak-ng.so.1")

    private val LIKELY_DATA_PATHS = listOf(
        "/opt/homebrew/share/espeak-ng-data",
        "/usr/local/share/espeak-ng-data",
        "/usr/share/espeak-ng-data",
        "/usr/lib/x86_64-linux-gnu/espeak-ng-data",
        "C:\\Program Files\\eSpeak NG\\espeak-ng-data",
    )
}
