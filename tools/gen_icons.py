import io
import pathlib
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import svgconv as S

IMG = pathlib.Path("img")
OUT = pathlib.Path("src/main/java/de/emilo/spotifycontrol/gui/raster/VectorIcons.java")

# name -> (source svg, optical inset inside the unit box)
SOURCES = [
    ("PLAY", "play.svg", 0.06),
    ("PAUSE", "pause.svg", 0.04),
    ("NEXT", "skip.svg", 0.02),
    ("PREVIOUS", "back.svg", 0.02),
    ("SHUFFLE", "shuffle.svg", 0.0),
    ("REPEAT", "repeat.svg", 0.0),
    ("HEART", "hearth_full.svg", 0.0),
    ("HEART_OUTLINE", "hearth_empty.svg", 0.0),
    ("SEARCH", "search.svg", 0.0),
    ("HOME", "home.svg", 0.0),
    ("LIBRARY", "library.svg", 0.02),
    ("SPEAKER_MUTE", "speaker_mute.svg", 0.0),
    ("SPEAKER_QUIET", "speaker_quiet.svg", 0.0),
    ("SPEAKER_LOUD", "speaker_loud.svg", 0.0),
    ("DEVICES", "devices.svg", 0.02),
    ("PLUS", "plus.svg", 0.06),
    ("QUEUE", "queue.svg", 0.0),
    ("PEOPLE", "friend.svg", 0.0),
    ("GEAR", "settings.svg", 0.0),
]

# icons the set does not cover, authored in the same 24x24 space
EXTRA = {
    "CLOSE": ('<svg viewBox="0 0 24 24">'
              '<path d="M4 4 L20 20 M20 4 L4 20" '
              'stroke="#000" stroke-width="1.8" fill="none"/></svg>', 0.0),
    "CHECK": ('<svg viewBox="0 0 24 24">'
              '<path d="M4 12.4 L9.6 18 L20 6" '
              'stroke="#000" stroke-width="1.8" fill="none"/></svg>', 0.0),
    "NOTE": ('<svg viewBox="0 0 24 24" fill="#000">'
             '<path d="M9 6.2 L20 3.8 L20 7.2 L9 9.6 Z"/>'
             '<path d="M9 8 L10.6 8 L10.6 17 L9 17 Z"/>'
             '<path d="M18.4 4.6 L20 4.6 L20 14.8 L18.4 14.8 Z"/>'
             '<ellipse cx="7.4" cy="17" rx="3" ry="2.4"/>'
             '<ellipse cx="16.8" cy="14.8" rx="3" ry="2.4"/></svg>', 0.02),
}


def parts_of_string(text):
    root = ET.fromstring(text)
    parts = []
    S.collect(root, S.IDENTITY, {}, parts)
    return parts


def hex_argb(fill):
    """#rgb or #rrggbb from the SVG as an opaque Java colour literal."""
    text = fill.strip().lstrip("#")
    if len(text) == 3:
        text = "".join(c * 2 for c in text)
    if len(text) != 6:
        raise SystemExit("cannot read colour " + fill)
    return "FF" + text.upper()


def main():
    icons = {}
    for name, filename, inset in SOURCES:
        parts = S.load(IMG / filename)
        if not parts:
            raise SystemExit("no geometry in " + filename)
        icons[name] = S.encode(S.normalize(parts, inset=inset))

    for name, (text, inset) in EXTRA.items():
        icons[name] = S.encode(S.normalize(parts_of_string(text), inset=inset))

    # The logo is the one drawing here that carries more than a single colour, so its groups are
    # kept apart. All of them are normalized against the shared bounding box rather than each
    # against its own, which is what makes them line up when drawn on top of one another.
    groups = S.load_by_fill(IMG / "logo.svg")
    if len(groups) != 2:
        raise SystemExit("expected 2 colours in logo.svg, got %d" % len(groups))
    everything = [part for _, parts in groups for part in parts]
    box = S.bounds(everything)
    icons["LOGO"] = S.encode(S.normalize(everything, box=box))
    icons["LOGO_BARS"] = S.encode(S.normalize(groups[0][1], box=box))
    icons["LOGO_HEADS"] = S.encode(S.normalize(groups[1][1], box=box))
    colors = [("LOGO_BARS_COLOR", groups[0][0]), ("LOGO_HEADS_COLOR", groups[1][0])]

    body = io.StringIO()
    body.write("package de.emilo.spotifycontrol.gui.raster;\n\n")
    body.write("/**\n * Icon outlines converted from the SVG sources in {@code img/}.\n")
    body.write(" * Generated - do not edit by hand.\n")
    body.write(" *\n * <p>Each icon is normalized into a unit box, aspect preserved and centered.\n")
    body.write(" * Parts are separated by {@code |}: {@code f0}/{@code f1} fill a path with the\n")
    body.write(" * nonzero/evenodd rule, {@code s<w>} strokes it with round caps and joins.\n */\n")
    body.write("public final class VectorIcons {\n\n")
    body.write("    private VectorIcons() {\n    }\n")
    for name in sorted(icons):
        body.write("\n    public static final String %s =\n            \"%s\";\n" % (name, icons[name]))
    # The logo colours travel with the shapes, so recolouring the SVG reaches the game
    # without anybody having to remember a hex value sitting in a Java file.
    for name, value in colors:
        body.write("\n    public static final int %s = 0x%s;\n" % (name, hex_argb(value)))
    body.write("}\n")
    OUT.write_text(body.getvalue(), encoding="utf-8")

    for name in sorted(icons):
        print("%-16s %5d chars" % (name, len(icons[name])))
    print("\nwrote", OUT)


if __name__ == "__main__":
    main()
