"""SVG -> normalized icon path data.

Produces, per icon, a list of parts:
  ("f", fill_rule, contours)          filled contours, rule 0=nonzero 1=evenodd
  ("s", half_width, contours)         stroked contours, round caps/joins

A contour is (start_x, start_y, [segments], closed) with segments
  ("L", x, y) or ("C", x1, y1, x2, y2, x, y)

All coordinates end up normalized into a unit box, aspect preserved, centered.
"""

import math
import re
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"

NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
CMD = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]")


# --------------------------------------------------------------------- matrix

def mat_mul(a, b):
    a0, a1, a2, a3, a4, a5 = a
    b0, b1, b2, b3, b4, b5 = b
    return (a0 * b0 + a2 * b1,
            a1 * b0 + a3 * b1,
            a0 * b2 + a2 * b3,
            a1 * b2 + a3 * b3,
            a0 * b4 + a2 * b5 + a4,
            a1 * b4 + a3 * b5 + a5)


def mat_apply(m, x, y):
    return m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]


def mat_scale(m):
    """Average scale factor, for converting stroke widths."""
    sx = math.hypot(m[0], m[1])
    sy = math.hypot(m[2], m[3])
    return math.sqrt(sx * sy)


IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def parse_transform(text):
    m = IDENTITY
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", text or ""):
        v = [float(n) for n in NUM.findall(args)]
        if name == "translate":
            t = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == "scale":
            t = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif name == "matrix":
            t = tuple(v[:6])
        elif name == "rotate":
            a = math.radians(v[0])
            c, s = math.cos(a), math.sin(a)
            t = (c, s, -s, c, 0, 0)
            if len(v) == 3:
                t = mat_mul(mat_mul((1, 0, 0, 1, v[1], v[2]), t), (1, 0, 0, 1, -v[1], -v[2]))
        elif name in ("skewX", "skewY"):
            tn = math.tan(math.radians(v[0]))
            t = (1, tn, 0, 1, 0, 0) if name == "skewY" else (1, 0, tn, 1, 0, 0)
        else:
            continue
        m = mat_mul(m, t)
    return m


# ------------------------------------------------------------------ path data

def arc_to_cubics(x0, y0, rx, ry, phi, large, sweep, x, y):
    """Endpoint arc -> list of cubic segments (SVG implementation notes F.6)."""
    if rx == 0 or ry == 0 or (abs(x - x0) < 1e-12 and abs(y - y0) < 1e-12):
        return [("L", x, y)]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(phi % 360.0)
    cp, sp = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x) / 2.0, (y0 - y) / 2.0
    x1p = cp * dx2 + sp * dy2
    y1p = -sp * dx2 + cp * dy2
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1.0:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x0 + x) / 2.0
    cy = sp * cxp + cp * cyp + (y0 + y) / 2.0

    def angle(ux, uy, vx, vy):
        d = math.hypot(ux, uy) * math.hypot(vx, vy)
        if d == 0:
            return 0.0
        c = max(-1.0, min(1.0, (ux * vx + uy * vy) / d))
        a = math.acos(c)
        return -a if ux * vy - uy * vx < 0 else a

    theta = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi

    out = []
    count = max(1, int(math.ceil(abs(delta) / (math.pi / 2.0))))
    step = delta / count
    t = 4.0 / 3.0 * math.tan(step / 4.0)
    a1 = theta
    px, py = x0, y0
    for i in range(count):
        a2 = a1 + step
        c1, s1 = math.cos(a1), math.sin(a1)
        c2, s2 = math.cos(a2), math.sin(a2)

        def pt(c, s):
            ex = rx * c
            ey = ry * s
            return cp * ex - sp * ey + cx, sp * ex + cp * ey + cy

        ex2, ey2 = pt(c2, s2)
        dx1 = rx * -s1
        dy1 = ry * c1
        dx2b = rx * -s2
        dy2b = ry * c2
        q1x = px + t * (cp * dx1 - sp * dy1)
        q1y = py + t * (sp * dx1 + cp * dy1)
        q2x = ex2 - t * (cp * dx2b - sp * dy2b)
        q2y = ey2 - t * (sp * dx2b + cp * dy2b)
        out.append(("C", q1x, q1y, q2x, q2y, ex2, ey2))
        px, py = ex2, ey2
        a1 = a2
    return out


def parse_path(d):
    """Path data -> list of contours, all absolute, only L and C segments."""
    tokens = []
    pos = 0
    while pos < len(d):
        ch = d[pos]
        if CMD.match(ch):
            tokens.append(ch)
            pos += 1
            continue
        m = NUM.match(d, pos)
        if m:
            tokens.append(float(m.group()))
            pos = m.end()
        else:
            pos += 1

    contours = []
    segs = None
    sx = sy = cx = cy = 0.0
    prev_c2 = None
    prev_q = None
    cmd = None
    i = 0

    def flush(closed):
        nonlocal segs
        if segs is not None and segs[1]:
            contours.append((segs[0][0], segs[0][1], segs[1], closed))
        segs = None

    while i < len(tokens):
        if isinstance(tokens[i], str):
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                flush(True)
                cx, cy = sx, sy
                segs = [(cx, cy), []]
                continue
        rel = cmd.islower()
        c = cmd.upper()

        def take(n):
            nonlocal i
            v = tokens[i:i + n]
            i += n
            return [float(x) for x in v]

        if c == "M":
            x, y = take(2)
            if rel:
                x, y = cx + x, cy + y
            flush(False)
            cx = cy = 0
            sx, sy, cx, cy = x, y, x, y
            segs = [(x, y), []]
            cmd = "l" if rel else "L"
            prev_c2 = prev_q = None
            continue
        if segs is None:
            segs = [(cx, cy), []]
        if c == "L":
            x, y = take(2)
            if rel:
                x, y = cx + x, cy + y
            segs[1].append(("L", x, y))
            cx, cy = x, y
            prev_c2 = prev_q = None
        elif c == "H":
            (x,) = take(1)
            if rel:
                x += cx
            segs[1].append(("L", x, cy))
            cx = x
            prev_c2 = prev_q = None
        elif c == "V":
            (y,) = take(1)
            if rel:
                y += cy
            segs[1].append(("L", cx, y))
            cy = y
            prev_c2 = prev_q = None
        elif c == "C":
            x1, y1, x2, y2, x, y = take(6)
            if rel:
                x1, y1, x2, y2, x, y = cx + x1, cy + y1, cx + x2, cy + y2, cx + x, cy + y
            segs[1].append(("C", x1, y1, x2, y2, x, y))
            prev_c2 = (x2, y2)
            prev_q = None
            cx, cy = x, y
        elif c == "S":
            x2, y2, x, y = take(4)
            if rel:
                x2, y2, x, y = cx + x2, cy + y2, cx + x, cy + y
            if prev_c2:
                x1, y1 = 2 * cx - prev_c2[0], 2 * cy - prev_c2[1]
            else:
                x1, y1 = cx, cy
            segs[1].append(("C", x1, y1, x2, y2, x, y))
            prev_c2 = (x2, y2)
            prev_q = None
            cx, cy = x, y
        elif c in ("Q", "T"):
            if c == "Q":
                qx, qy, x, y = take(4)
                if rel:
                    qx, qy, x, y = cx + qx, cy + qy, cx + x, cy + y
            else:
                x, y = take(2)
                if rel:
                    x, y = cx + x, cy + y
                qx, qy = (2 * cx - prev_q[0], 2 * cy - prev_q[1]) if prev_q else (cx, cy)
            segs[1].append(("C",
                            cx + 2.0 / 3.0 * (qx - cx), cy + 2.0 / 3.0 * (qy - cy),
                            x + 2.0 / 3.0 * (qx - x), y + 2.0 / 3.0 * (qy - y),
                            x, y))
            prev_q = (qx, qy)
            prev_c2 = None
            cx, cy = x, y
        elif c == "A":
            rx, ry, rot, large, sweep, x, y = take(7)
            if rel:
                x, y = cx + x, cy + y
            segs[1].extend(arc_to_cubics(cx, cy, rx, ry, rot, int(large), int(sweep), x, y))
            cx, cy = x, y
            prev_c2 = prev_q = None
        else:
            i += 1
    flush(False)
    return contours


# ------------------------------------------------------------------- elements

def rect_contour(x, y, w, h, rx, ry):
    if rx <= 0 and ry <= 0:
        return [(x, y, [("L", x + w, y), ("L", x + w, y + h), ("L", x, y + h)], True)]
    rx = min(rx if rx > 0 else ry, w / 2.0)
    ry = min(ry if ry > 0 else rx, h / 2.0)
    k = 0.5522847498
    segs = [
        ("L", x + w - rx, y),
        ("C", x + w - rx + rx * k, y, x + w, y + ry - ry * k, x + w, y + ry),
        ("L", x + w, y + h - ry),
        ("C", x + w, y + h - ry + ry * k, x + w - rx + rx * k, y + h, x + w - rx, y + h),
        ("L", x + rx, y + h),
        ("C", x + rx - rx * k, y + h, x, y + h - ry + ry * k, x, y + h - ry),
        ("L", x, y + ry),
        ("C", x, y + ry - ry * k, x + rx - rx * k, y, x + rx, y),
    ]
    return [(x + rx, y, segs, True)]


def ellipse_contour(cx, cy, rx, ry):
    k = 0.5522847498
    return [(cx + rx, cy, [
        ("C", cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry),
        ("C", cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy),
        ("C", cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry),
        ("C", cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy),
    ], True)]


def points_contour(text, closed):
    v = [float(n) for n in NUM.findall(text or "")]
    pts = list(zip(v[0::2], v[1::2]))
    if len(pts) < 2:
        return []
    return [(pts[0][0], pts[0][1], [("L", px, py) for px, py in pts[1:]], closed)]


def style_of(el, inherited):
    style = dict(inherited)
    raw = el.get("style", "")
    props = {}
    for item in raw.split(";"):
        if ":" in item:
            k, v = item.split(":", 1)
            props[k.strip()] = v.strip()
    for key in ("fill", "stroke", "stroke-width", "fill-rule", "clip-rule"):
        if el.get(key) is not None:
            style[key] = el.get(key)
        if key in props:
            style[key] = props[key]
    return style


def is_paint(value):
    if value is None:
        return False
    v = value.strip().lower()
    if v in ("none", "transparent", "", "white", "#fff", "#ffffff"):
        return False
    return not v.startswith("url(")


def transform_contours(contours, m):
    out = []
    for x, y, segs, closed in contours:
        nx, ny = mat_apply(m, x, y)
        ns = []
        for s in segs:
            if s[0] == "L":
                ns.append(("L",) + mat_apply(m, s[1], s[2]))
            else:
                a = mat_apply(m, s[1], s[2])
                b = mat_apply(m, s[3], s[4])
                c = mat_apply(m, s[5], s[6])
                ns.append(("C", a[0], a[1], b[0], b[1], c[0], c[1]))
        out.append((nx, ny, ns, closed))
    return out


def collect(el, ctm, style, parts):
    ctm = mat_mul(ctm, parse_transform(el.get("transform")))
    style = style_of(el, style)
    tag = el.tag.replace(NS, "")

    contours = []
    if tag == "path":
        contours = parse_path(el.get("d", ""))
    elif tag == "rect":
        f = lambda k, d=0.0: float(el.get(k, d) or d)
        contours = rect_contour(f("x"), f("y"), f("width"), f("height"), f("rx", -1), f("ry", -1))
    elif tag == "circle":
        f = lambda k: float(el.get(k, 0) or 0)
        contours = ellipse_contour(f("cx"), f("cy"), f("r"), f("r"))
    elif tag == "ellipse":
        f = lambda k: float(el.get(k, 0) or 0)
        contours = ellipse_contour(f("cx"), f("cy"), f("rx"), f("ry"))
    elif tag == "line":
        f = lambda k: float(el.get(k, 0) or 0)
        contours = [(f("x1"), f("y1"), [("L", f("x2"), f("y2"))], False)]
    elif tag == "polygon":
        contours = points_contour(el.get("points"), True)
    elif tag == "polyline":
        contours = points_contour(el.get("points"), False)

    if contours:
        contours = transform_contours(contours, ctm)
        if is_paint(style.get("fill", "black")):
            rule = 1 if style.get("fill-rule", "nonzero").strip() == "evenodd" else 0
            parts.append(("f", rule, contours))
        if is_paint(style.get("stroke")):
            width = float(NUM.findall(style.get("stroke-width", "1"))[0])
            parts.append(("s", width * mat_scale(ctm) / 2.0, contours))

    for child in el:
        collect(child, ctm, style, parts)


def load(path):
    root = ET.parse(path).getroot()
    parts = []
    collect(root, IDENTITY, {}, parts)
    return parts




SHAPES = ("path", "rect", "circle", "ellipse", "line", "polygon", "polyline")


def load_by_fill(path):
    """Every drawn shape of an SVG, grouped by fill colour, in document order.

    load() throws the colours away, which is right for the icon set: those are painted in one
    tint chosen at draw time. A logo made of more than one colour needs them kept, so this walks
    the same tree and records the fill next to the geometry. Returns [(fill, parts), ...].
    """
    root = ET.parse(path).getroot()
    groups = []
    index = {}

    def walk(el, ctm, style):
        tag = el.tag.replace(NS, "")
        if tag in SHAPES:
            parts = []
            # collect() applies this element's own transform and style, so it gets the parent ones.
            collect(el, ctm, style, parts)
            if parts:
                fill = style_of(el, style).get("fill", "black")
                key = fill.strip().lower()
                if key not in index:
                    index[key] = len(groups)
                    groups.append((fill, []))
                groups[index[key]][1].extend(parts)
            return
        ctm = mat_mul(ctm, parse_transform(el.get("transform")))
        style = style_of(el, style)
        for child in el:
            walk(child, ctm, style)

    walk(root, IDENTITY, {})
    return groups

# -------------------------------------------------------------- normalization

def flatten_cubic(p0, p1, p2, p3, out, steps=24):
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        x = u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0]
        y = u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]
        out.append((x, y))


def sample(contours):
    pts = []
    for x, y, segs, _ in contours:
        cur = (x, y)
        pts.append(cur)
        for s in segs:
            if s[0] == "L":
                cur = (s[1], s[2])
                pts.append(cur)
            else:
                nxt = (s[5], s[6])
                flatten_cubic(cur, (s[1], s[2]), (s[3], s[4]), nxt, pts)
                cur = nxt
    return pts


def bounds(parts):
    x0 = y0 = 1e30
    x1 = y1 = -1e30
    for kind, arg, contours in parts:
        pad = arg if kind == "s" else 0.0
        for px, py in sample(contours):
            x0 = min(x0, px - pad)
            y0 = min(y0, py - pad)
            x1 = max(x1, px + pad)
            y1 = max(y1, py + pad)
    return x0, y0, x1, y1


def normalize(parts, box=None, inset=0.0):
    x0, y0, x1, y1 = box if box else bounds(parts)
    span = max(x1 - x0, y1 - y0)
    if span <= 0:
        span = 1.0
    s = (1.0 - 2 * inset) / span
    ox = inset + (span - (x1 - x0)) / 2.0 * s - x0 * s
    oy = inset + (span - (y1 - y0)) / 2.0 * s - y0 * s
    m = (s, 0.0, 0.0, s, ox, oy)
    out = []
    for kind, arg, contours in parts:
        out.append((kind, arg * s if kind == "s" else arg, transform_contours(contours, m)))
    return out


# ------------------------------------------------------------------ text form

def fmt(v):
    if abs(v) < 5e-5:
        return "0"
    t = "%.4f" % v
    t = t.rstrip("0").rstrip(".")
    return t if t not in ("", "-") else "0"


def encode(parts):
    chunks = []
    for kind, arg, contours in parts:
        head = "f%d:" % arg if kind == "f" else "s%s:" % fmt(arg)
        body = []
        for x, y, segs, closed in contours:
            body.append("M%s %s" % (fmt(x), fmt(y)))
            for s in segs:
                body.append(s[0] + " ".join(fmt(v) for v in s[1:]))
            if closed:
                body.append("Z")
        chunks.append(head + " ".join(body))
    return "|".join(chunks)
