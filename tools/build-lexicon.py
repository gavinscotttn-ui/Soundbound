#!/usr/bin/env python3
"""
Builds a pronunciation dictionary for Soundbound from CMUdict.

The app ships a small built-in table — the English function words and the notorious irregulars —
which is what lets it speak with no setup at all. This produces the full thing: about 135,000
entries, roughly 1 MB gzipped, which removes almost every case where the letter-to-sound rules
have to guess.

    python3 tools/build-lexicon.py --out lexicon.txt.gz

Then copy the file into Soundbound's data folder; docs/building.md lists where that is per
platform. The app picks it up on the next launch.

CMUdict is public domain. This script downloads it, so it needs a connection once.
"""
import argparse
import gzip
import sys
import urllib.request

CMUDICT_URL = (
    "https://raw.githubusercontent.com/cmusphinx/cmudict/master/cmudict.dict"
)


def fetch(url):
    print(f"Fetching {url}")
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read().decode("utf-8", errors="replace")


def parse(text):
    """
    CMUdict lines look like:  hello HH AH0 L OW1

    Alternative pronunciations are marked `word(2)`; only the first is kept, because a reader has
    no way to choose between them and the first is the most common.
    """
    entries = {}
    skipped = 0
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        word = parts[0]
        if word.endswith(")"):
            skipped += 1
            continue
        # Punctuation entries such as `!exclamation-point` are of no use here.
        if not word[0].isalpha():
            continue
        entries.setdefault(word.lower(), " ".join(parts[1:]))
    return entries, skipped


def write(entries, path, british):
    header = "# soundbound-lexicon 1 arpabet " + ("british" if british else "american")
    print(f"Writing {len(entries):,} entries to {path}")
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=9) as handle:
        handle.write(header + "\n")
        for word in sorted(entries):
            handle.write(f"{word}\t{entries[word]}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default="lexicon.txt.gz", help="output file")
    parser.add_argument("--url", default=CMUDICT_URL, help="source dictionary")
    parser.add_argument("--american", action="store_true",
                        help="use the General American vowel table rather than the RP-leaning one")
    parser.add_argument("--extra", action="append", default=[],
                        help="an additional word\\tARPABET file to merge in, repeatable")
    args = parser.parse_args()

    try:
        text = fetch(args.url)
    except Exception as error:
        print(f"Could not fetch the dictionary: {error}", file=sys.stderr)
        return 1

    entries, skipped = parse(text)
    print(f"Parsed {len(entries):,} entries ({skipped:,} alternative pronunciations skipped)")

    for path in args.extra:
        with open(path, encoding="utf-8") as handle:
            extra, _ = parse(handle.read())
        print(f"Merging {len(extra):,} entries from {path}")
        entries.update(extra)

    write(entries, args.out, british=not args.american)
    print("Done. See docs/building.md for where to put it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
