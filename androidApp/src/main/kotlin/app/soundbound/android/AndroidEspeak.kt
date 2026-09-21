package app.soundbound.android

import app.soundbound.core.tts.g2p.NativeEspeakBridge

/**
 * Optional espeak-ng support.
 *
 * Soundbound does not ship the library: it would add several megabytes across four architectures
 * for a component most users never need, given the built-in dictionary handles English well. A user
 * who wants espeak's phonemisation — worth having, since Piper trained on it — can supply
 * `libespeak-ng.so` and the `espeak-ng-data` folder, and this picks it up.
 *
 * The JNI names below match the shim described in `docs/espeak-ng.md`. When the library is absent,
 * [bridgeIfAvailable] returns null and nothing else in the app notices.
 */
object AndroidEspeak {

    private var loaded: Boolean? = null

    private fun tryLoad(): Boolean {
        loaded?.let { return it }
        val result = runCatching {
            System.loadLibrary("soundbound-espeak")
            true
        }.getOrDefault(false)
        loaded = result
        return result
    }

    fun bridgeIfAvailable(): NativeEspeakBridge? = if (tryLoad()) JniBridge else null

    private object JniBridge : NativeEspeakBridge {
        private var initialised = false

        override fun initialise(dataPath: String): Boolean {
            initialised = runCatching { nativeInitialise(dataPath) }.getOrDefault(false)
            return initialised
        }

        override fun setVoice(voiceName: String): Boolean =
            runCatching { nativeSetVoice(voiceName) }.getOrDefault(false)

        override fun textToPhonemes(text: String): String =
            runCatching { nativeTextToPhonemes(text) }.getOrDefault("")

        override fun isInitialised(): Boolean = initialised

        override fun release() {
            runCatching { nativeRelease() }
            initialised = false
        }

        @JvmStatic private external fun nativeInitialise(dataPath: String): Boolean
        @JvmStatic private external fun nativeSetVoice(voiceName: String): Boolean
        @JvmStatic private external fun nativeTextToPhonemes(text: String): String
        @JvmStatic private external fun nativeRelease()
    }
}
