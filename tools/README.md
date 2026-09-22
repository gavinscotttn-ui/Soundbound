# Tools

Small scripts that are not part of the build.

| Script             | What it does                                                                     |
|--------------------|----------------------------------------------------------------------------------|
| `trace-mark.py`    | Traces the mark out of the master artwork into Android vector path data.          |
| `make-icons.py`    | Renders the desktop icons (`.png`, `.ico`, `.icns`) from the master artwork.      |
| `build-lexicon.py` | Builds a full 135,000-word pronunciation dictionary from CMUdict.                 |

`build-lexicon.py` is plain Python 3. The two icon scripts need Pillow (`pip install pillow`);
they are run by hand when the artwork changes, never by the build.

## The artwork

`branding/soundbound-icon.png` is the master: headphones over an open book, on the magenta tile.
Everything the app shows is derived from it, so change it there and re-run both icon scripts:

```bash
python3 tools/trace-mark.py branding/soundbound-icon.png   # paste into ic_launcher_foreground.xml
python3 tools/make-icons.py branding/soundbound-icon.png desktopApp/src/main/resources
```
