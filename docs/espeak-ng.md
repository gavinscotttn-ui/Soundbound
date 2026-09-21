# Using espeak-ng for phonemisation

Soundbound speaks perfectly well without this. Read on only if you want the best possible result
from Piper voices, or you want languages beyond English.

## Why it matters

Piper's models were trained on phonemes produced by espeak-ng. Feeding them exactly what they were
trained on is audibly better than feeding them a good approximation — particularly on names,
loanwords and anything the built-in dictionary has never seen. It also brings the other forty-odd
languages, which the English-only fallback cannot.

Soundbound does not bundle it. Doing so would mean shipping native binaries for four Android
architectures and three desktop platforms — several megabytes — for a component most people
reading English in one language never need.

## Desktop

Install the library; Soundbound finds it automatically through JNA.

```bash
# macOS
brew install espeak-ng

# Debian, Ubuntu
sudo apt install espeak-ng

# Fedora
sudo dnf install espeak-ng
```

**Windows**: install [espeak-ng](https://github.com/espeak-ng/espeak-ng/releases) and make sure
`libespeak-ng.dll` is on the `PATH`, or beside the Soundbound executable.

The data folder is found automatically in the usual places. If yours is elsewhere, copy or symlink
it to `espeak-ng-data` inside Soundbound's data folder (see
[building.md](building.md#installing-a-full-pronunciation-dictionary) for where that is).

Turn it on under **Settings → How words are said**; it is preferred by default and simply falls
back when absent.

## Android

This one needs building, because there is no published AAR that exposes the phonemiser.

### 1. Build espeak-ng for Android

Follow espeak-ng's own Android instructions, or build it with the NDK for `arm64-v8a`,
`armeabi-v7a`, `x86_64` and `x86`.

### 2. Build the JNI shim

Soundbound looks for a library called `soundbound-espeak` exposing four functions. A minimal shim:

```c
// soundbound-espeak.c
#include <jni.h>
#include <string.h>
#include <espeak-ng/speak_lib.h>

#define PKG Java_app_soundbound_android_AndroidEspeak_00024JniBridge

JNIEXPORT jboolean JNICALL PKG_nativeInitialise(JNIEnv *env, jclass clazz, jstring dataPath) {
    const char *path = (*env)->GetStringUTFChars(env, dataPath, 0);
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, 0);
    (*env)->ReleaseStringUTFChars(env, dataPath, path);
    return rate > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL PKG_nativeSetVoice(JNIEnv *env, jclass clazz, jstring name) {
    const char *voice = (*env)->GetStringUTFChars(env, name, 0);
    espeak_ERROR result = espeak_SetVoiceByName(voice);
    (*env)->ReleaseStringUTFChars(env, name, voice);
    return result == EE_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL PKG_nativeTextToPhonemes(JNIEnv *env, jclass clazz, jstring text) {
    const char *input = (*env)->GetStringUTFChars(env, text, 0);
    const void *pointer = input;

    char buffer[8192];
    buffer[0] = '\0';
    // espeak advances the pointer as it consumes the text, so keep calling until it is done.
    while (pointer != NULL) {
        const char *chunk = espeak_TextToPhonemes(&pointer, espeakCHARS_UTF8, 0x02);
        if (chunk == NULL) break;
        if (strlen(buffer) + strlen(chunk) + 1 >= sizeof(buffer)) break;
        strcat(buffer, chunk);
    }

    (*env)->ReleaseStringUTFChars(env, text, input);
    return (*env)->NewStringUTF(env, buffer);
}

JNIEXPORT void JNICALL PKG_nativeRelease(JNIEnv *env, jclass clazz) {
    espeak_Terminate();
}
```

Build it against espeak-ng and link statically, producing `libsoundbound-espeak.so` per ABI.

### 3. Put the pieces in place

- Copy each `libsoundbound-espeak.so` into `androidApp/src/main/jniLibs/<abi>/`.
- Copy the `espeak-ng-data` folder into the app's files directory, as
  `/data/data/app.soundbound/files/espeak-ng-data`.

Soundbound loads it if present and carries on quietly if not — `AndroidEspeak.bridgeIfAvailable()`
returns null and nothing else in the app notices.

## Checking it worked

Open **Voices** and preview a Piper voice with a word the dictionary will not know — a place name
does nicely. With espeak-ng in use it is pronounced sensibly; without it, the letter-to-sound
rules have a reasonable go and occasionally an amusing one.
