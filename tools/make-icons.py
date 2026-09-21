#!/usr/bin/env python3
"""
Generates Soundbound's desktop application icons.

Kept as a script rather than committing only the binaries so that the mark can be changed in one
place. It rasterises by hand — no image library — because the mark is geometric and the build
should not need a Python package installed to reproduce it.

Usage: python3 tools/make-icons.py desktopApp/src/main/resources
"""
import math
import os
import struct
import sys
import zlib

# The app's own colours, from SoundboundColours.
INK = (0x12, 0x10, 0x0E, 0xFF)
PAPER = (0xFF, 0xF3, 0xE6, 0xFF)
AMBER = (0xE8, 0x9B, 0x2C, 0xFF)


def blend(dst, src):
    """Alpha-composites src over dst."""
    a = src[3] / 255.0
    if a >= 1.0:
        return src
    if a <= 0.0:
        return dst
    return tuple(int(round(src[i] * a + dst[i] * (1 - a))) for i in range(3)) + (255,)


class Canvas:
    def __init__(self, size):
        self.size = size
        self.pixels = [[(0, 0, 0, 0)] * size for _ in range(size)]

    def fill_rounded_rect(self, x0, y0, x1, y1, radius, colour):
        for y in range(max(0, int(y0)), min(self.size, int(math.ceil(y1)))):
            for x in range(max(0, int(x0)), min(self.size, int(math.ceil(x1)))):
                coverage = self._rounded_coverage(x, y, x0, y0, x1, y1, radius)
                if coverage <= 0:
                    continue
                c = (colour[0], colour[1], colour[2], int(round(colour[3] * coverage)))
                self.pixels[y][x] = blend(self.pixels[y][x], c)

    def _rounded_coverage(self, x, y, x0, y0, x1, y1, radius):
        """Supersampled coverage of one pixel, which is what keeps the edges smooth."""
        samples = 4
        hits = 0
        for sy in range(samples):
            for sx in range(samples):
                px = x + (sx + 0.5) / samples
                py = y + (sy + 0.5) / samples
                if self._inside_rounded(px, py, x0, y0, x1, y1, radius):
                    hits += 1
        return hits / (samples * samples)

    @staticmethod
    def _inside_rounded(px, py, x0, y0, x1, y1, radius):
        if px < x0 or px > x1 or py < y0 or py > y1:
            return False
        if radius <= 0:
            return True
        for cx, cy in ((x0 + radius, y0 + radius), (x1 - radius, y0 + radius),
                       (x0 + radius, y1 - radius), (x1 - radius, y1 - radius)):
            in_x = (px < x0 + radius) if cx == x0 + radius else (px > x1 - radius)
            in_y = (py < y0 + radius) if cy == y0 + radius else (py > y1 - radius)
            if in_x and in_y:
                return (px - cx) ** 2 + (py - cy) ** 2 <= radius ** 2
        return True

    def to_png(self):
        raw = bytearray()
        for row in self.pixels:
            raw.append(0)  # no filtering
            for r, g, b, a in row:
                raw += bytes((r, g, b, a))

        def chunk(tag, data):
            return (struct.pack(">I", len(data)) + tag + data
                    + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

        header = struct.pack(">IIBBBBB", self.size, self.size, 8, 6, 0, 0, 0)
        return (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", header)
                + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
                + chunk(b"IEND", b""))


def draw_mark(size):
    """The mark: an amber waveform standing on a pale page edge, on an ink tile."""
    canvas = Canvas(size)
    s = size / 1024.0

    # The tile. macOS and Windows both round the corners themselves, but a rounded source looks
    # right in every other context too — a Linux dock, a README, a favicon.
    canvas.fill_rounded_rect(0, 0, size, size, 220 * s, INK)

    # The page: a single block with a thicker foot, which reads as the edge of a book at 16px
    # where any kind of outline drawing turns to mud.
    canvas.fill_rounded_rect(232 * s, 690 * s, 792 * s, 736 * s, 23 * s, PAPER)
    canvas.fill_rounded_rect(292 * s, 746 * s, 732 * s, 780 * s, 17 * s,
                             (PAPER[0], PAPER[1], PAPER[2], 110))

    # The waveform. Seven bars, heights chosen by hand rather than from a curve so that it reads
    # as speech rather than as a level meter: no symmetry, one clear peak, a quiet tail.
    bars = [0.34, 0.68, 0.92, 1.00, 0.55, 0.78, 0.40]
    bar_width = 58 * s
    gap = 34 * s
    total = len(bars) * bar_width + (len(bars) - 1) * gap
    left = (size - total) / 2
    baseline = 636 * s
    tallest = 392 * s

    for index, level in enumerate(bars):
        height = max(bar_width, level * tallest)
        x0 = left + index * (bar_width + gap)
        canvas.fill_rounded_rect(x0, baseline - height, x0 + bar_width, baseline,
                                 bar_width / 2, AMBER)
    return canvas


def write_ico(pngs, path):
    """An ICO holding PNG images, which every Windows version since Vista understands."""
    count = len(pngs)
    header = struct.pack("<HHH", 0, 1, count)
    entries = b""
    offset = 6 + 16 * count
    for size, data in pngs:
        width = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", width, width, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    with open(path, "wb") as handle:
        handle.write(header + entries + b"".join(data for _, data in pngs))


def write_icns(pngs, path):
    """An ICNS holding PNG images under the modern ic## types."""
    type_for_size = {16: b"icp4", 32: b"icp5", 64: b"icp6", 128: b"ic07",
                     256: b"ic08", 512: b"ic09", 1024: b"ic10"}
    body = b""
    for size, data in pngs:
        tag = type_for_size.get(size)
        if tag is None:
            continue
        body += tag + struct.pack(">I", len(data) + 8) + data
    with open(path, "wb") as handle:
        handle.write(b"icns" + struct.pack(">I", len(body) + 8) + body)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)

    rendered = {}
    for size in (16, 32, 64, 128, 256, 512, 1024):
        rendered[size] = draw_mark(size).to_png()
        print(f"  rendered {size}x{size}")

    with open(os.path.join(out, "icon.png"), "wb") as handle:
        handle.write(rendered[512])

    write_ico([(s, rendered[s]) for s in (16, 32, 64, 128, 256)], os.path.join(out, "icon.ico"))
    write_icns([(s, rendered[s]) for s in (16, 32, 128, 256, 512, 1024)],
               os.path.join(out, "icon.icns"))
    print(f"Wrote icon.png, icon.ico and icon.icns to {out}")


if __name__ == "__main__":
    main()
