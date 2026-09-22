#!/usr/bin/env python3
"""
Renders the desktop application icons from the master artwork.

The mark lives once, in branding/soundbound-icon.png. This script only resizes it into the
containers macOS, Windows and Linux each want, so that the three desktop builds and the Android
launcher icon cannot drift apart.

Android does not appear here: it takes the mark as a vector instead, produced by
tools/trace-mark.py, which stays crisp at any launcher size.

Requires Pillow. Run it only when the artwork changes:

    python3 tools/make-icons.py branding/soundbound-icon.png desktopApp/src/main/resources
"""
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("This tool needs Pillow: pip install pillow")

# Windows resolves the nearest size out of the .ico rather than scaling, so the small sizes are
# listed explicitly: a 256px mark squeezed into a 16px taskbar slot turns to mush.
ICO_SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]

PNG_SIZE = 512
ICNS_SIZE = 1024


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: make-icons.py <master.png> <output directory>")
    source, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

    master = Image.open(source).convert("RGBA")
    if master.width != master.height:
        sys.exit(f"The artwork must be square; {source} is {master.width}x{master.height}.")

    master.resize((PNG_SIZE, PNG_SIZE), Image.LANCZOS).save(os.path.join(out_dir, "icon.png"))
    master.save(os.path.join(out_dir, "icon.ico"), sizes=ICO_SIZES)
    master.resize((ICNS_SIZE, ICNS_SIZE), Image.LANCZOS).save(os.path.join(out_dir, "icon.icns"))

    for name in ("icon.png", "icon.ico", "icon.icns"):
        path = os.path.join(out_dir, name)
        print(f"{path}  {os.path.getsize(path):,} bytes")


if __name__ == "__main__":
    main()
