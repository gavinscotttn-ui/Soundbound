#!/usr/bin/env python3
"""
Traces Soundbound's mark out of the master icon artwork and emits Android vector path data.

The mark — headphones over an open book — is drawn once, as artwork. Everything the app shows
is derived from that one file rather than redrawn by hand, so the launcher icon, the splash
screen and the notification badge cannot drift apart from each other.

The mark is lifted by colour: it is the pale, desaturated part of the artwork, and the tile
behind it is saturated magenta, so the green channel separates the two cleanly. The outline is
then walked with marching squares and simplified, which keeps the arc of the headband smooth
while collapsing the long straight edges of the pages to a handful of points.

Requires Pillow. Run it only when the artwork changes:

    python3 tools/trace-mark.py branding/soundbound-icon.png

It prints the path data; paste it into androidApp/src/main/res/drawable/ic_launcher_foreground.xml.
"""
import sys
from collections import deque

try:
    from PIL import Image, ImageFilter
except ImportError:
    sys.exit("This tool needs Pillow: pip install pillow")

# The mark is pale; the tile behind it is saturated magenta. The green channel is what tells
# them apart — the palest part of the tile still sits well below the darkest part of the mark.
MARK_MIN_RED = 232
MARK_MIN_GREEN = 168
MARK_MIN_BLUE = 205

# Components smaller than this are artwork noise or the anti-aliased tile edge.
MIN_COMPONENT_AREA = 2000

# Diameter of the closing filter, in source pixels. Must be odd.
CLOSING = 9

# Simplification tolerance, in source pixels. At a ~1000px master this keeps curves smooth
# while collapsing straight edges; raise it if the emitted path is unwieldy.
TOLERANCE = 1.1

# Android adaptive icons are authored in a 108x108 viewport whose outer 18 units may be cropped
# by the launcher's mask. The mark is fitted to a circle of this diameter, centred, so that it
# survives every mask shape a launcher might apply.
VIEWPORT = 108.0
SAFE_DIAMETER = 68.0


def load_mask(path):
    """Returns (mask, width, height) where mask[y][x] is True inside the mark."""
    image = Image.open(path).convert("RGB")
    width, height = image.size
    pixels = image.load()
    flat = Image.new("L", (width, height))
    flat.putdata([
        255 if (pixels[x, y][0] > MARK_MIN_RED
                and pixels[x, y][1] > MARK_MIN_GREEN
                and pixels[x, y][2] > MARK_MIN_BLUE) else 0
        for y in range(height) for x in range(width)
    ])

    # The artwork shades the mark from white towards pink, and along the darkest edges that
    # shading dips below the threshold and bites small notches out of the outline. A
    # morphological closing — grow, then shrink by the same amount — fills those notches
    # without moving the edges anywhere else.
    grown = flat.filter(ImageFilter.MaxFilter(CLOSING))
    closed = grown.filter(ImageFilter.MinFilter(CLOSING))

    data = closed.load()
    mask = [[data[x, y] > 127 for x in range(width)] for y in range(height)]
    return mask, width, height


def components(mask, width, height):
    """Four-connected components of the mask, largest first, as (area, cells)."""
    seen = [[False] * width for _ in range(height)]
    found = []
    for sy in range(height):
        for sx in range(width):
            if seen[sy][sx] or not mask[sy][sx]:
                continue
            queue = deque([(sx, sy)])
            seen[sy][sx] = True
            cells = []
            while queue:
                x, y = queue.popleft()
                cells.append((x, y))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < width and 0 <= ny < height and not seen[ny][nx] and mask[ny][nx]:
                        seen[ny][nx] = True
                        queue.append((nx, ny))
            if len(cells) >= MIN_COMPONENT_AREA:
                found.append((len(cells), cells))
    found.sort(key=lambda c: c[0], reverse=True)
    return found


def touches_border(cells, width, height):
    """True when a component runs into the edge of the image — that is the tile, not the mark."""
    for x, y in cells:
        if x <= 1 or y <= 1 or x >= width - 2 or y >= height - 2:
            return True
    return False


def trace_outline(cells):
    """
    Walks the outline of one component with a Moore neighbourhood trace.

    Works on the cell grid rather than a smoothed field: the artwork is anti-aliased, so the
    threshold already gives a clean edge and a sub-pixel contour would only add points.
    """
    present = set(cells)
    start = min(cells, key=lambda c: (c[1], c[0]))
    # Eight-connected neighbours, clockwise from due west.
    offsets = [(-1, 0), (-1, -1), (0, -1), (1, -1), (1, 0), (1, 1), (0, 1), (-1, 1)]

    contour = [start]
    current = start
    backtrack = (start[0] - 1, start[1])
    guard = 0
    limit = 8 * len(cells) + 64

    while guard < limit:
        guard += 1
        bx, by = backtrack[0] - current[0], backtrack[1] - current[1]
        index = offsets.index((bx, by))
        nxt = None
        for step in range(1, 9):
            ox, oy = offsets[(index + step) % 8]
            candidate = (current[0] + ox, current[1] + oy)
            if candidate in present:
                nxt = candidate
                backtrack = (current[0] + offsets[(index + step - 1) % 8][0],
                             current[1] + offsets[(index + step - 1) % 8][1])
                break
        if nxt is None:
            break
        if nxt == start and len(contour) > 2:
            break
        contour.append(nxt)
        current = nxt
    return contour


def simplify(points, tolerance):
    """Ramer-Douglas-Peucker, iterative so that a long contour cannot blow the stack."""
    if len(points) < 3:
        return list(points)
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        if last <= first + 1:
            continue
        ax, ay = points[first]
        bx, by = points[last]
        dx, dy = bx - ax, by - ay
        length = (dx * dx + dy * dy) ** 0.5
        worst, worst_index = -1.0, -1
        for i in range(first + 1, last):
            px, py = points[i]
            if length == 0:
                distance = ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
            else:
                distance = abs(dy * px - dx * py + bx * ay - by * ax) / length
            if distance > worst:
                worst, worst_index = distance, i
        if worst > tolerance:
            keep[worst_index] = True
            stack.append((first, worst_index))
            stack.append((worst_index, last))
    return [p for p, k in zip(points, keep) if k]


def bounds(shapes):
    xs = [p[0] for shape in shapes for p in shape]
    ys = [p[1] for shape in shapes for p in shape]
    return min(xs), min(ys), max(xs), max(ys)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.strip().splitlines()[-1])
    source = sys.argv[1]

    mask, width, height = load_mask(source)
    parts = [
        cells for _, cells in components(mask, width, height)
        if not touches_border(cells, width, height)
    ]
    if not parts:
        sys.exit("No mark found. Check the thresholds against the artwork.")

    shapes = [simplify(trace_outline(cells), TOLERANCE) for cells in parts]

    # Fit the mark into the safe circle: scale on its longest side, then centre it.
    min_x, min_y, max_x, max_y = bounds(shapes)
    span = max(max_x - min_x, max_y - min_y)
    scale = SAFE_DIAMETER / span
    offset_x = (VIEWPORT - (max_x - min_x) * scale) / 2 - min_x * scale
    offset_y = (VIEWPORT - (max_y - min_y) * scale) / 2 - min_y * scale

    print(f"<!-- traced from {source}: {len(shapes)} shapes, "
          f"{sum(len(s) for s in shapes)} points -->")
    pieces = []
    for shape in shapes:
        coordinates = [
            (round(x * scale + offset_x, 2), round(y * scale + offset_y, 2)) for x, y in shape
        ]
        piece = f"M{coordinates[0][0]},{coordinates[0][1]}"
        for x, y in coordinates[1:]:
            piece += f"L{x},{y}"
        pieces.append(piece + "Z")
    print(" ".join(pieces))


if __name__ == "__main__":
    main()
