"""Renders img/logo.svg to the mod icon Fabric shows in the mod list.

The overlay rasterizes the logo from the generated outlines at runtime, so the SVG is the only
source of truth for the shape. A mod list entry cannot do that: it wants a PNG on disk, hence this
file. It paints the same way VectorRaster does in game, by scanline with sub rows and exact
horizontal coverage, so the icon and the logo on screen come out of the same geometry.

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
OUT = pathlib.Path("src/main/resources/assets/spotifycontrol/icon.png")

SIZE = 128
SUB = 8          # sub rows per pixel row; horizontal coverage is exact, so only y is sampled
INSET = 0.04     # a breath of transparent margin so the note does not touch the edge


def polygons(contours):
    """One closed point list per contour.

    svgconv.sample flattens every contour of a part into a single list, which is all the icon
    encoder needs. Filling needs the subpaths kept apart, or a shape with a hole in it would come
    out solid.
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


def add_span(row, a, b, weight, size):
    if b <= 0 or a >= size:
        return
    a = max(a, 0.0)
    b = min(b, float(size))
    first = int(a)
    last = min(int(b), size - 1)
    if first == last:
        row[first] += (b - a) * weight
        return
    row[first] += (first + 1 - a) * weight
    for px in range(first + 1, last):
        row[px] += weight
    row[last] += (b - last) * weight


def part_coverage(part, size, sub):
    """Per pixel alpha in 0..1 for a single part, honouring its fill rule."""
    kind, rule, contours = part
    edges = []
    for pts in polygons(contours):
        for i in range(len(pts) - 1):
            x0, y0 = pts[i]
            x1, y1 = pts[i + 1]
            if y0 != y1:
                edges.append((x0, y0, x1, y1, 1 if y1 > y0 else -1))

    cov = [[0.0] * size for _ in range(size)]
    weight = 1.0 / sub
    for r in range(size * sub):
        y = (r + 0.5) / sub
        crossings = []
        for x0, y0, x1, y1, winding in edges:
            if (y0 <= y < y1) or (y1 <= y < y0):
                crossings.append((x0 + (y - y0) / (y1 - y0) * (x1 - x0), winding))
        if len(crossings) < 2:
            continue
        crossings.sort()
        row = cov[r // sub]
        if rule == 1:
            for i in range(0, len(crossings) - 1, 2):
                add_span(row, crossings[i][0], crossings[i + 1][0], weight, size)
        else:
            wind = 0
            start = 0.0
            for x, direction in crossings:
                if wind == 0:
                    start = x
                wind += direction
                if wind == 0:
                    add_span(row, start, x, weight, size)
    return cov


def coverage(parts, size, sub):
    """The union of a group of parts.

    Every part is rasterized on its own and the results are combined by taking the larger value,
    rather than throwing all the edges into one sweep. Shapes in this logo overlap on purpose,
    the note heads sit under the stems, and a shared even odd sweep would turn each overlap into
    a hole. Taking the maximum also avoids a seam where two parts meet, which adding would leave.
    """
    cov = [[0.0] * size for _ in range(size)]
    for part in parts:
        local = part_coverage(part, size, sub)
        for y in range(size):
            target = cov[y]
            source = local[y]
            for x in range(size):
                if source[x] > target[x]:
                    target[x] = source[x]
    return cov


def rgb(fill):
    text = fill.strip().lstrip("#")
    if len(text) == 3:
        text = "".join(c * 2 for c in text)
    return int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16)


def over(dst, cov, color, size):
    """Source over compositing, so the groups stack the way they do in the SVG."""
    r, g, b = color
    for y in range(size):
        row = cov[y]
        for x in range(size):
            a = min(1.0, max(0.0, row[x]))
            if a <= 0.0:
                continue
            i = (y * size + x) * 4
            da = dst[i + 3] / 255.0
            outa = a + da * (1.0 - a)
            if outa <= 0.0:
                continue
            dst[i] = int(round((r * a + dst[i] * da * (1.0 - a)) / outa))
            dst[i + 1] = int(round((g * a + dst[i + 1] * da * (1.0 - a)) / outa))
            dst[i + 2] = int(round((b * a + dst[i + 2] * da * (1.0 - a)) / outa))
            dst[i + 3] = int(round(outa * 255))


def write_png(path, buffer, size):
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # filter type none
        raw += buffer[y * size * 4:(y + 1) * size * 4]

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    path.write_bytes(png)


def main():
    groups = S.load_by_fill(SRC)
    if not groups:
        raise SystemExit("no geometry in " + str(SRC))

    # One shared box, so the groups keep their positions relative to each other. Normalizing each
    # against its own bounds would centre every group separately and take the note apart.
    everything = [part for _, parts in groups for part in parts]
    box = S.bounds(everything)
    scale = (SIZE, 0.0, 0.0, SIZE, 0.0, 0.0)

    buffer = bytearray(SIZE * SIZE * 4)
    for fill, parts in groups:
        placed = S.normalize(parts, box=box, inset=INSET)
        scaled = [(k, a, S.transform_contours(c, scale)) for k, a, c in placed]
        over(buffer, coverage(scaled, SIZE, SUB), rgb(fill), SIZE)

    write_png(OUT, buffer, SIZE)
    print("wrote %s (%dx%d) from %d colour group(s)" % (OUT, SIZE, SIZE, len(groups)))


if __name__ == "__main__":
    main()
