"""Renders img/logo.svg to img/logo.png, the mark the README shows at the top.

The repository has no binary logo of its own: the mod rasterizes the vector at runtime, so the only
source of truth is the SVG. A README cannot do that, hence this file. It writes the PNG the same way
VectorRaster draws in game, by scanline with sub-rows and exact horizontal coverage, so the result
matches what the mod puts on screen rather than being a second, slightly different logo.

    python tools/gen_logo.py

Only the standard library is used, as in gen_icons.py, so the tools stay runnable on a bare Python.
"""

import pathlib
import struct
import sys
import zlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import svgconv as S

SRC = pathlib.Path("img/logo.svg")
OUT = pathlib.Path("img/logo.png")

SIZE = 512
SUB = 8          # sub-rows per pixel row; horizontal coverage is exact, so only y is sampled
INSET = 0.02     # a breath of transparent margin so the disc does not touch the edge
COLOR = (0x1D, 0xB9, 0x54)


def polygons(contours):
    """One closed point list per contour.

    svgconv.sample flattens every contour of a part into a single list, which is all the icon
    encoder needs. Even-odd filling needs the subpaths kept apart, or the three waves would not be
    cut out of the disc.
    """
    out = []
    for x, y, segs, _ in contours:
        pts = [(x, y)]
        cur = (x, y)
        for s in segs:
            if s[0] == "L":
                cur = (s[1], s[2])
                pts.append(cur)
            else:
                nxt = (s[5], s[6])
                S.flatten_cubic(cur, (s[1], s[2]), (s[3], s[4]), nxt, pts)
                cur = nxt
        if pts[0] != pts[-1]:
            pts.append(pts[0])
        out.append(pts)
    return out


def coverage(polys, size, sub):
    """Per-pixel alpha in 0..1, even-odd rule."""
    edges = []
    for pts in polys:
        for i in range(len(pts) - 1):
            x0, y0 = pts[i]
            x1, y1 = pts[i + 1]
            if y0 != y1:
                edges.append((x0, y0, x1, y1))

    cov = [[0.0] * size for _ in range(size)]
    weight = 1.0 / sub
    for r in range(size * sub):
        y = (r + 0.5) / sub
        xs = []
        for x0, y0, x1, y1 in edges:
            if (y0 <= y < y1) or (y1 <= y < y0):
                xs.append(x0 + (y - y0) / (y1 - y0) * (x1 - x0))
        if len(xs) < 2:
            continue
        xs.sort()
        row = cov[r // sub]
        for i in range(0, len(xs) - 1, 2):
            a, b = xs[i], xs[i + 1]
            if b <= 0 or a >= size:
                continue
            a = max(a, 0.0)
            b = min(b, float(size))
            first = int(a)
            last = min(int(b), size - 1)
            if first == last:
                row[first] += (b - a) * weight
                continue
            row[first] += (first + 1 - a) * weight
            for px in range(first + 1, last):
                row[px] += weight
            row[last] += (b - last) * weight
    return cov


def write_png(path, cov, color):
    size = len(cov)
    r, g, b = color
    raw = bytearray()
    for row in cov:
        raw.append(0)  # filter type none
        for c in row:
            alpha = int(round(min(1.0, max(0.0, c)) * 255))
            raw += bytes((r, g, b, alpha))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    path.write_bytes(png)


def main():
    parts = S.load(SRC)
    if len(parts) != 1 or parts[0][0] != "f" or parts[0][1] != 1:
        raise SystemExit("expected one even-odd fill in %s" % SRC)
    parts = S.normalize(parts, inset=INSET)
    contours = S.transform_contours(parts[0][2], (SIZE, 0.0, 0.0, SIZE, 0.0, 0.0))
    write_png(OUT, coverage(polygons(contours), SIZE, SUB), COLOR)
    print("wrote %s (%dx%d)" % (OUT, SIZE, SIZE))


if __name__ == "__main__":
    main()
