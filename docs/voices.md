# Voices

Soundbound speaks with three kinds of voice, all of them running on the device.

| Engine | Where it comes from | Why you would use it |
|--------|---------------------|----------------------|
| **Piper** | The in-app voice store | Hundreds of voices, forty-odd languages, 20–110 MB each. The breadth. |
| **Kokoro** | Installed by hand, see below | The most human-sounding option. Reach for it when a book is a six-hour listen. |
| **Device voice** | Already on your phone or computer | No download at all. On a recent Samsung handset this is Samsung's or Google's neural voice, which is very good. |

None of them sends a word of your book anywhere. The only network access in the whole app is
fetching a Piper voice you asked for.

---

## Piper

Open **Voices** and install one. That is the whole procedure.

The store reads the published index rather than guessing URLs, so what it lists is what exists,
and each download is checked against the MD5 the index publishes before it is put in place.

**Which to pick.** *Medium* quality is about 60 MB and is genuinely good. *High* is around 110 MB
and is better, mostly in the consonants and in how it handles a long sentence. On a modern phone
both run comfortably faster than real time.

A voice you already have can be added by hand with the folder button at the top of the installed
list — pick both the `.onnx` model and its `.onnx.json` companion, since a Piper voice is the pair.

---

## Kokoro

Kokoro is a small model that sounds markedly more like a person than anything else that runs
offline. It is not in the in-app store because its packs are not published in a single indexed
form, so it is installed by hand.

### Installing a pack

Make a folder inside Soundbound's `voices` directory — call it `kokoro` — containing:

```
voices/
└── kokoro/
    ├── model.onnx          the model
    ├── config.json         its configuration, including the vocabulary
    └── voices/
        ├── af_heart.bin    one file per speaker
        ├── bf_emma.bin
        └── …
```

The ONNX export and the voice files are published by the community on Hugging Face; the
`onnx-community/Kokoro-82M-v1.0-ONNX` repository is the usual source. Any export works provided it
ships a `config.json` carrying a `vocab` object — Soundbound reads the vocabulary, the sample rate
and the style dimensions from that file rather than assuming them, so a pack that differs in shape
still loads or fails cleanly rather than producing nonsense.

Soundbound's `voices` directory is:

| Platform | Folder |
|----------|--------|
| Android  | `/data/data/app.soundbound/files/voices/` |
| macOS    | `~/Library/Application Support/Soundbound/voices/` |
| Windows  | `%APPDATA%\Soundbound\voices\` |
| Linux    | `~/.local/share/soundbound/voices/` |

Speaker names follow a convention that Soundbound decodes for the list: the first letter is the
language (`a` American English, `b` British English, `e` Spanish, `f` French, `h` Hindi, `i`
Italian, `j` Japanese, `p` Portuguese, `z` Chinese) and the second is the gender. So `bf_emma`
appears as **Emma**, English (United Kingdom), female.

### An honest caveat

Kokoro support in Soundbound is written to the contract its packs publish and is covered by tests
for the tokeniser, the configuration parser and the style-table reader — but it has not been run
against a real model by its author, because the machine it was written on could not download one.
Piper is the path that has been exercised end to end. If a Kokoro pack sounds wrong, that is worth
reporting rather than working around.

---

## Why some names are pronounced oddly

Piper and Kokoro do not read letters; they read phonemes. Turning text into phonemes is a separate
step, and Soundbound offers two ways of doing it.

**The built-in dictionary** is the default and needs nothing installed. It knows the English
function words and the notorious irregulars — *said*, *once*, *colonel*, *Gloucester* — derives
plurals and past tenses from known stems, and falls back to spelling rules for anything it has
never seen. It is good, and it is occasionally wrong about a surname.

**espeak-ng** is what Piper's models were actually trained on, so matching it is audibly better and
brings the other languages along. It is not bundled; see [espeak-ng.md](espeak-ng.md).

For the full 135,000-word dictionary, run `tools/build-lexicon.py` and drop the result in
Soundbound's data folder. See [building.md](building.md#installing-a-full-pronunciation-dictionary).

### Correcting a single word

**Settings → How words are said → Pronunciation corrections**. Give the word and its phonemes in
IPA, and Soundbound will use yours from then on, in every book and every voice. Your corrections
survive a reset of the speech settings, because they are your work rather than a preference.
