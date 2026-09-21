# Soundbound

An ebook reader that reads aloud, properly, with no connection of any kind.

Android, macOS and Windows. Your books, your voice, your device. Nothing is uploaded, there is no
account, and the only time the app touches the network at all is the moment you choose to install
a voice.

---

## What it does

**Reads EPUB, PDF and plain text.** EPUB 2 and 3, with the publisher's structure intact —
navigation documents, NCX fallbacks, footnotes, tables, images, internal links. PDFs are not read
line by line: Soundbound reconstructs the actual paragraphs from the page geometry, detects
columns, strips running heads and folios, and heals words broken across a line ending in a hyphen.

**Speaks with a real voice, offline.** Hundreds of [Piper](https://github.com/rhasspy/piper)
neural voices across more than forty languages, run locally through ONNX Runtime. Your device's
own speech engine is there too, so a fresh install can speak before you have downloaded anything.

**Sounds like a narrator, not a speaking clock.** "£12.50" becomes *twelve pounds fifty*. "1984"
becomes *nineteen eighty-four*. "Mr. Darcy" does not end a sentence, and neither does "3.14" or
"J. R. R. Tolkien". Speed changes are done with WSOLA time-stretching, so 1.5× is faster without
turning the narrator into a chipmunk.

**Follows along.** The sentence being spoken is highlighted on the page, the word within it is
picked out, and the page scrolls to keep up — but only when the narration has actually drifted off
screen, so it never fights your thumb. Reading and listening share one position: put the phone
down mid-sentence and pick the book up on the desktop where the voice left off.

**Exports to MP3.** Pick chapters, pick a bit rate, and Soundbound renders the book *flat out* —
far faster than listening to it — into tagged MP3 files with cover art and track numbers. Put them
on a USB stick, an old iPod, or the car.

**Looks like something you want to read in.** Seven reading surfaces from Paper to true black,
adjustable type size, leading, measure, margins and paragraph spacing, six reading faces, and a
library built around covers rather than a spreadsheet.

---

## Getting it

### Android

Grab the APK from the **Actions** tab — every push builds one — or build it yourself:

```bash
./gradlew :androidApp:assembleDebug
# androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Requires Android 8.0 (API 26) or later. Tested targets are API 26 through 36.

### macOS and Windows

```bash
./gradlew -Psoundbound.withAndroid=false :desktopApp:packageDistributionForCurrentOS
# desktopApp/build/compose/binaries/main/dmg/  (macOS)
# desktopApp/build/compose/binaries/main/msi/  (Windows)
```

Or download the `.dmg` / `.msi` from the Actions tab.

### First run

1. Add a book — the **+** in the library, or share an EPUB to Soundbound from anywhere.
2. Open **Voices** and install one. The **medium** quality English voices are about 60 MB and are
   very good; **high** is around 110 MB and is better still.
3. Press play.

Steps 2 needs a connection, once. Everything else, ever, does not.

---

## What is actually going on

```
┌─────────────────────────────────────────────────────────────────┐
│  androidApp                    desktopApp                       │
│  AudioTrack · SAF pickers      SourceDataLine · Swing pickers   │
│  TextToSpeech · PDFBox-Android say / SAPI · Apache PDFBox       │
└───────────────────────┬─────────────────────────────────────────┘
                        │  one interface, three platforms
┌───────────────────────┴─────────────────────────────────────────┐
│  ui  — Compose Multiplatform                                    │
│  Library · Reader · Player · Voices · Notes · Settings          │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────┴─────────────────────────────────────────┐
│  core — plain Kotlin/JVM, no Android, no Compose, no java.awt   │
│                                                                  │
│  EPUB / PDF / text  →  blocks + plain text with exact offsets   │
│                          ↓                                       │
│  normalise & segment →  utterances that sound like sentences    │
│                          ↓                                       │
│  Piper via ONNX      →  audio, throttled by the audio queue     │
│                          ↓                                       │
│  WSOLA · resample · fades · LAME  →  speaker, or an MP3         │
└──────────────────────────────────────────────────────────────────┘
```

`:core` is a plain Kotlin/JVM library. It has no Android dependency, no Compose dependency, and
deliberately avoids `java.awt` and `java.nio.file`, so the identical jar runs on a desktop JVM and
on ART. That is why nearly all of the interesting behaviour is covered by ordinary JUnit tests
that run in seconds on any machine, with no emulator and no Android SDK:

```bash
./gradlew -Psoundbound.withUi=false -Psoundbound.withAndroid=false :core:test :pdfjvm:test
```

More on the design in [docs/architecture.md](docs/architecture.md).

---

## Privacy, plainly

- There is no analytics, no crash reporting, no telemetry and no account.
- The `INTERNET` permission exists **only** so the voice store can fetch a model you asked for.
  Revoke it and everything except that keeps working.
- Your library, positions, bookmarks, highlights and settings are two JSON files in the app's own
  storage. You can read them, back them up, and move them between devices by hand.
- Nothing you read is ever sent anywhere. Synthesis happens on the device, always.

---

## Modules

| Module        | What it is                                                                 |
|---------------|----------------------------------------------------------------------------|
| `:core`       | Parsing, text pipeline, speech engines, playback, library, export. No UI.   |
| `:pdfjvm`     | Apache PDFBox binding for the desktop. Kept out of `:core` so no `java.awt` reference reaches the Android build. |
| `:ui`         | The whole interface, in Compose Multiplatform. Shared by all three targets. |
| `:androidApp` | `AudioTrack`, the Storage Access Framework, `TextToSpeech`, PDFBox-Android, the playback service. |
| `:desktopApp` | `SourceDataLine`, Swing pickers, `say` / SAPI, Apache PDFBox, packaging.    |

Build flags, for working on one part without the rest:

```bash
-Psoundbound.withUi=false        # :core and :pdfjvm only — no Android SDK, no Google Maven
-Psoundbound.withAndroid=false   # shared interface and desktop app, no Android SDK needed
```

---

## Honest limitations

- **Scanned PDFs have no text layer.** Soundbound detects them and says so rather than reading
  silence. OCR is not included.
- **espeak-ng is optional, not bundled.** Piper's models were trained on espeak-ng's phonemes, so
  it gives the best result, but shipping it would mean binaries for four Android architectures and
  three desktop platforms. The built-in English pronunciation dictionary and letter-to-sound rules
  are the default and are good; see [docs/espeak-ng.md](docs/espeak-ng.md) to add the real thing,
  and [tools/build-lexicon.py](tools/build-lexicon.py) to install a full 135,000-word dictionary.
- **Word-level highlighting is interpolated.** No offline engine reports word timings, so the word
  within a sentence is estimated from the elapsed fraction. It tracks well enough to follow; it is
  not frame-accurate, and pretending otherwise would be a lie drawn on the screen.
- **Fonts are not bundled.** Literata, Atkinson Hyperlegible and OpenDyslexic are offered in the
  type picker and used if you drop the files into the app's `fonts` folder; otherwise the platform
  serif and sans are used. Licensing fonts into a repository is somebody else's job.
- **Reading position is estimated for the progress bar** until a chapter has been opened, because
  an exact figure would mean parsing the whole book up front.

---

## Licence and credits

Soundbound is the application. It stands on:

- [Piper](https://github.com/rhasspy/piper) voices (MIT), published by
  [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices)
- [ONNX Runtime](https://onnxruntime.ai) (MIT)
- [Apache PDFBox](https://pdfbox.apache.org) and
  [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0)
- [jump3r](https://github.com/Sciss/jump3r), a Java port of LAME (LGPL)
- [jsoup](https://jsoup.org) (MIT), [okio](https://square.github.io/okio/) and
  [OkHttp](https://square.github.io/okhttp/) (Apache 2.0)
- [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) (Apache 2.0)

Individual voice models carry their own licences; the voice store shows them.
