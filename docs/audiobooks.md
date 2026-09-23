# Audiobooks you already own

Soundbound plays two quite different things. It reads text aloud itself, and it plays recordings
somebody else made. This is about the second.

The aim is that the difference stops mattering the moment a book is in your library: the same
shelf, the same covers, the same bookmarks, the same resume-where-you-left-off, the same lock
screen. Only the layer that actually makes sound knows which sort of book it has.

## Adding one

**+** in the library, then **Add an audiobook**.

Everything you pick in one go becomes **one book**. Thirty MP3s are one book. One M4B is one book.
Two M4Bs picked together are one book in two parts. That rule is deliberate: picking four EPUBs
gives you four books, and picking forty MP3s giving you forty would be absurd, so the unit is what
you just expressed by selecting it.

On the desktop you can choose a folder. On Android you choose files, and they are copied into the
app's own storage — which costs disk, but a permission granted by the file picker is not
guaranteed to survive a reboot, and a book that plays today and cannot be found next week is worse
than one that took a minute to add.

## What it reads out of your files

| | |
|---|---|
| **Title** | The album tag; else the folder's name; else what the file names have in common; else the first file's title. |
| **Author** | The album artist, then the artist — on an audiobook the plain artist field is as often the narrator as the writer. |
| **Narrator** | The composer field, or a `NARRATOR` custom tag. Shown on the book's details. |
| **Cover** | Embedded artwork, from the first file that has any. |
| **Chapters** | The file's own chapter list where it has one; otherwise one chapter per file. |

## Order

The thing that must never be wrong. A book played in the wrong order is worthless and it is the
first thing anyone notices.

1. Disc number, then track number, where the tags have them.
2. Otherwise the file names, compared the way a person reads them — the digits inside a name are
   compared as numbers, so `Part 10` comes after `Part 2` rather than after `Part 1`.
3. A file with no track number sorts after the numbered ones, so a stray `intro.mp3` does not land
   in the middle of a numbered set.

If a book does come out wrong, the fix is in the files' tags rather than in the app.

## Chapters

A folder of MP3s usually has one chapter per file, and that is what you get.

A single M4B usually has its chapters listed inside it, and those are read out of the file —
Soundbound parses both conventions, Nero's `chpl` box and QuickTime's chapter track. A
nine-hour recording with no way to move about in it is no use at all, and no mobile metadata API
will hand a chapter list over, which is why the parsing is done here rather than delegated.

MP3s can carry chapters too, in ID3 `CHAP` frames, and those are read as well.

## Formats

**Android** plays whatever the phone plays, because the decoding is the phone's own: MP3, AAC in
an M4A or M4B, Opus, Vorbis, FLAC, WAV.

**Desktop** plays MP3, WAV, AIFF and AU. It cannot play AAC — a plain Java runtime has no decoder
for it and bundling one would add tens of megabytes — so an `.m4b` there is refused with a message
saying so. Converting the book to MP3 is the workaround.

## What works the same as a book being read aloud

Speed, without the chipmunk effect — the same WSOLA time-stretching. The sleep timer, including
stopping at the end of the chapter. Bookmarks. Progress on the cover. The lock screen, a Bluetooth
headset, a car and a watch. Audio focus, so a phone call interrupts and a navigation prompt ducks.

## What is deliberately different

**Skipping.** A synthesised book steps by a sentence, because that is its natural unit and there
is no timeline to step along. A recording steps by thirty seconds, which is what every audiobook
player does and what a headset's buttons expect. Next and previous are chapters in both.

**The player screen.** A synthesised book shows the sentence being spoken and counts progress in
sentences, because it has no fixed duration — change the speed or the voice and every timestamp
moves. A recording shows a draggable timeline with the elapsed and remaining time, because it has
one and hiding it would be wilful.

**Exporting to MP3** applies only to books Soundbound reads itself. Your audiobook is already an
MP3; it does not need rendering into one.

## Known rough edges

- Seeking inside a **variable-bitrate MP3** is estimated from the average bitrate and can land a
  second or two from where you dropped the thumb. Exact seeking would mean scanning every frame of
  a file that may be several hundred megabytes.
- An MP3's **length** is likewise worked out rather than read, since MP3 has no header that states
  it. A Xing or VBRI header is believed where present; otherwise it comes from the bitrate and the
  file size. The decoder corrects it when the file is opened.
- **Moving the files** after import makes it a different book, because the library identifies an
  audiobook by the files it is made of. The old entry's files really are gone, so saying so is the
  honest answer.
