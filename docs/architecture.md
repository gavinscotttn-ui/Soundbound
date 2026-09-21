# How Soundbound is put together

This is the reasoning behind the shape of the code, rather than a tour of it. The code itself is
commented where a decision is not obvious; this file covers the decisions that span more than one
file.

## One coordinate system

Every parser produces two things from a chapter: a list of renderable **blocks**, and the flat
**plain text**. Each block records its exact character offset into that plain text, and that
single number is what ties the app together:

- the reader highlights a range of it,
- the speech planner cuts sentences out of it,
- the saved reading position is a point in it,
- a search result is a point in it,
- a bookmark and a highlight are ranges of it.

The invariant is that `plainText.substring(block.textStart, block.textEnd) == block.text` for
every block, and `ChapterContent.validate()` checks it. Every parser is unit-tested against it,
because everything that synchronises reading with listening rests on it being true.

## `:core` has no platform

`:core` is a plain Kotlin/JVM library. It has no Android dependency and no Compose dependency, and
it deliberately avoids `java.awt` and `java.nio.file` so that the same jar runs on a desktop JVM
and on ART with `minSdk 26`.

This is not tidiness for its own sake. It means the EPUB parser, the PDF reflow heuristics, the
number and date expansion, the sentence segmenter, the WSOLA time-stretcher, the MP3 encoder, the
library and the whole playback controller are covered by ordinary JUnit tests that run in seconds
on any machine — no emulator, no Android SDK, no device. That is the difference between a test
suite people run and one they do not.

Where a platform genuinely differs, the boundary is an interface in `:core` with a small
implementation on each side:

| Interface          | Android                    | Desktop                      |
|--------------------|----------------------------|------------------------------|
| `AudioSink`        | `AudioTrack`               | `SourceDataLine`             |
| `PdfBackend`       | PDFBox-Android + `PdfRenderer` | Apache PDFBox            |
| `SystemTtsBridge`  | `TextToSpeech`             | `say` / SAPI / espeak        |
| `NativeEspeakBridge` | JNI shim                 | JNA                          |
| `PlatformBridge`   | Storage Access Framework   | Swing choosers               |

`:pdfjvm` exists purely so that no `java.awt` reference can reach the Android build. Apache PDFBox
touches it; keeping the binding in its own module means R8 never sees it.

## Synthesis is throttled by the speaker

The obvious way to write read-aloud is a loop: synthesise a sentence, play it, synthesise the
next. That produces a gap at every sentence boundary, because synthesis takes real time.

The obvious fix is to render ahead on a timer, which is worse: it guesses, and every guess is
wrong on some device.

What Soundbound does instead is render ahead into the audio device's own queue and let the
*device* apply the back-pressure. `AudioSink.enqueue` performs a blocking write; it returns as the
hardware drains. A fast phone therefore renders further ahead and a slow one keeps up, with no
tuning parameter anywhere. Nothing is ever rendered more than a second or two in advance, which
matters because a change of speed, voice or position throws it all away.

The corollary is that **which clip is audible is not the clip most recently handed over** — those
differ by the whole buffer. The sink reports the audible one, read from the hardware's frame
position, and the highlight follows that. Getting this wrong is why some apps highlight a sentence
a beat before the voice says it.

## The text pipeline is the difference

A neural voice reproduces the rhythm of whatever it is given. Most of what makes a synthesised
book bearable is therefore decided before any audio exists:

1. **Reflow.** A PDF has no concept of a paragraph; it knows only where each glyph was painted.
   Reading a PDF line by line — a pause at the end of every typeset line — is unbearable, so
   `PdfReflow` reconstructs paragraphs from column layout, margins, line gaps, indents,
   hyphenation and running heads.
2. **Normalise.** "£12.50" read literally is *pound twelve point five zero*. Every rule in
   `TextNormaliser` exists because some real book broke without it. The mapping back to the
   original characters is kept, so the reader can still highlight the exact words being spoken.
3. **Segment.** A full stop in English ends a sentence, marks an abbreviation, sits inside a
   decimal, and separates initials. `SentenceSegmenter` looks at what surrounds each one. Getting
   it wrong is instantly audible: the voice either charges through a full stop or takes a breath
   in the middle of "Mr. Darcy".
4. **Plan.** Block kind changes delivery. A heading gets a pause; a page-break marker is silent; a
   footnote is lifted out of the flow rather than read mid-sentence.

## Voices are pluggable, and one is always present

`TtsEngine` has two implementations. `PiperTtsEngine` runs Piper (VITS) models through ONNX
Runtime; `SystemTtsEngine` wraps whatever the platform already has. The registry merges them,
sorts by language and quality, and — importantly — keeps **exactly one model loaded**. A Piper
session is tens of megabytes of resident memory, and keeping several is the quickest way to be
killed by Android's low-memory reaper halfway through a chapter.

The system engine is not a token fallback. On a recent Samsung handset it is Samsung's or Google's
neural voice: already installed, genuinely good, and available the moment the app opens. It is
rendered to a WAV file rather than spoken directly, so it goes through the same pipeline as every
other voice and gains the same speed control, highlighting and MP3 export.

## Phonemisation, and why it is a fork in the road

Piper models are trained on espeak-ng's IPA output. Matching it exactly is audibly better than any
approximation, and brings forty-odd languages along.

But espeak-ng is a native library, and bundling it means binaries for four Android architectures
and three desktop platforms — several megabytes, for a component most users reading English never
need. So `Phonemizer` has two implementations: an espeak-ng bridge used when the library is
present, and a pure-Kotlin one that is always available. The latter is a pronunciation dictionary
with inflection derivation, plus NRL-style letter-to-sound rules for words it has never seen. It
ships with the English function words and the notorious irregulars; `tools/build-lexicon.py`
installs a full 135,000-word dictionary.

## The library is a file, not a database

`LibraryRepository` holds the whole library in memory over a single atomically written JSON
document. A personal library is hundreds of books, not millions: search and sorting are instant,
and one readable file is far easier to back up, sync by hand and inspect when something goes wrong
than a binary database would be. Writes go through a temporary file and a rename, with a `.bak`
copy of the previous version, because a process killed mid-write is routine on Android and losing
someone's reading position is the one bug a reader must never have.

Books are identified by a hash of their first and last 256 KB plus their length, not by their
path. The same book imported twice from two folders is one book with one position, and hashing
only the ends stays fast on a 600 MB illustrated PDF.

## Export runs flat out

Playback is throttled to real time by definition. Export is not: `AudiobookExporter` synthesises
as fast as the device manages and streams straight to the file, so a fifteen-minute chapter takes
a fraction of that. Audio is never accumulated — a six-hour book held in memory as 32-bit floats
is about two gigabytes.

MP3 encoding uses jump3r, a Java port of LAME, driven through its **low-level** API. Its
convenience wrapper takes a `javax.sound.sampled.AudioFormat`, which does not exist on Android;
using it would have meant shipping a native LAME for four architectures, or offering export on the
desktop only. Wiring LAME's modules by hand costs thirty lines, and the identical encoder then
runs on both.

## Why the interface is shared, and where it is not

The interface is one Compose Multiplatform module. A reader's behaviour should not quietly differ
between a phone and a laptop, and maintaining two of everything is how that happens.

What is *not* shared is anything where the platforms genuinely disagree: file pickers, audio
output, background playback, and the handful of items behind `PlatformBridge`. Keeping that list
short is what makes the arrangement worth having.

Navigation is a hand-written back stack rather than a navigation library. There are six screens
and no deep linking; a library would be more configuration than code, and it keeps behaviour
identical on both platforms.
