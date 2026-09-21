# Building Soundbound

## What you need

- **JDK 17 or newer.** No toolchain is pinned, so whatever you already have works. The project
  targets Java 17 bytecode because that is what Android accepts.
- **Android SDK** — only for `:androidApp` and for `:ui`'s Android target. Android Studio installs
  it; otherwise the command-line tools plus `platforms;android-36` and `build-tools;36.0.0`.
- Nothing else. Gradle fetches the rest.

## The three build configurations

```bash
# Everything.
./gradlew build

# Shared interface and the desktop app, no Android SDK needed.
./gradlew -Psoundbound.withAndroid=false :desktopApp:run

# The engine alone: pure Kotlin, no Android SDK, no access to Google's Maven repository.
./gradlew -Psoundbound.withUi=false -Psoundbound.withAndroid=false :core:test :pdfjvm:test
```

The third is worth knowing about. `:core` holds nearly all of the behaviour and all of the tests,
and it builds on a bare JDK in a few seconds. It is the configuration to develop the engine in.

## Android

```bash
./gradlew :androidApp:assembleDebug
# androidApp/build/outputs/apk/debug/androidApp-debug.apk

adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### A signed release build

The release type is signed only when you supply a keystore, so that CI can still produce an
unsigned APK. Create one:

```bash
keytool -genkeypair -v -keystore soundbound.jks -alias soundbound \
        -keyalg RSA -keysize 4096 -validity 10000
```

Then put the details in `~/.gradle/gradle.properties` — **not** in the repository:

```properties
soundbound.release.storeFile=/absolute/path/to/soundbound.jks
soundbound.release.storePassword=…
soundbound.release.keyAlias=soundbound
soundbound.release.keyPassword=…
```

```bash
./gradlew :androidApp:assembleRelease
```

Without those properties the release APK is built unsigned, which is fine for inspection but
cannot be installed until it is signed.

## Desktop

```bash
# Run it.
./gradlew -Psoundbound.withAndroid=false :desktopApp:run

# Package an installer for the machine you are on.
./gradlew -Psoundbound.withAndroid=false :desktopApp:packageDistributionForCurrentOS
```

Output lands in `desktopApp/build/compose/binaries/main/`.

A `.dmg` can only be built on macOS and an `.msi` only on Windows — `jpackage` wraps each
platform's own tooling. The CI workflow does all three on the matching runners; see
`.github/workflows/build.yml`.

**Windows** additionally needs [WiX Toolset 3.x](https://wixtoolset.org/) on the `PATH` for
`packageMsi`. `packageDistributionForCurrentOS` will tell you if it is missing.

**macOS** builds are ad-hoc signed. Distributing outside your own machine needs a Developer ID
certificate and notarisation; set `macOS { signing { sign.set(true) } }` in
`desktopApp/build.gradle.kts` and supply the identity.

## Continuous integration

`.github/workflows/build.yml` runs on every push:

1. **Tests** — `:core` and `:pdfjvm` on Ubuntu, with no Android SDK. Seconds.
2. **Android** — debug and release APKs, uploaded as artefacts.
3. **Desktop** — `.dmg`, `.msi` and `.deb` on their respective runners, uploaded as artefacts.

Download any of them from the run's **Artifacts** section. Tagging `v1.0.0` runs
`release.yml`, which attaches the installers to a draft GitHub release.

## Installing a full pronunciation dictionary

The built-in dictionary covers the English function words and the common irregulars. For the full
135,000-word CMU dictionary:

```bash
python3 tools/build-lexicon.py --out lexicon.txt.gz
```

Then place `lexicon.txt.gz` in the app's data folder:

| Platform | Folder                                           |
|----------|--------------------------------------------------|
| Android  | `/data/data/app.soundbound/files/`               |
| macOS    | `~/Library/Application Support/Soundbound/`      |
| Windows  | `%APPDATA%\Soundbound\`                          |
| Linux    | `~/.local/share/soundbound/`                     |

On Android, `adb push lexicon.txt.gz /sdcard/` and then move it with a file manager, or use
`adb shell run-as app.soundbound.debug` on a debug build.

## Reading faces

Soundbound does not bundle licensed fonts. To use Literata, Atkinson Hyperlegible or OpenDyslexic,
drop the file into the `fonts` folder inside the data folder above, named exactly:

- `Literata-Regular.ttf`
- `AtkinsonHyperlegible-Regular.ttf`
- `OpenDyslexic-Regular.otf`
- `Bookerly-Regular.ttf`

Anything missing falls back to the platform serif or sans.

## Regenerating the desktop icons

```bash
python3 tools/make-icons.py desktopApp/src/main/resources
```

No image library needed; the mark is rasterised by the script.

## Troubleshooting

**`Plugin [id: 'com.android.application'] was not found`** — you are building a configuration that
needs AGP without access to Google's Maven repository. Add `-Psoundbound.withUi=false`.

**`Cannot find a Java installation … matching languageVersion=17`** — you are on an older Gradle
or a modified build file; the project deliberately pins no toolchain, so any JDK 17+ should work.

**`No audio output is available`** on the desktop — another application has exclusive use of the
device. On Linux this is usually a stale PulseAudio or PipeWire session.

**The voice store is empty** — it needs one connection to fetch the index. Voices already
installed keep working offline; the store says so rather than hanging.
