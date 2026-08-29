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


def main():
    icons = {}
    for name, filename, inset in SOURCES:
        parts = S.load(IMG / filename)
        if not parts:
            raise SystemExit("no geometry in " + filename)
        icons[name] = S.encode(S.normalize(parts, inset=inset))

    for name, (text, inset) in EXTRA.items():
        icons[name] = S.encode(S.normalize(parts_of_string(text), inset=inset))

    # the logo is one evenodd path: disc with the three waves cut out.
    # the waves alone are needed too, normalized against the disc so they line
    # up when painted on top of a green circle.
    logo = S.load(IMG / "logo.svg")
    if len(logo) != 1 or logo[0][0] != "f":
        raise SystemExit("unexpected logo structure")
    kind, rule, contours = logo[0]
    box = S.bounds(logo)
    icons["SPOTIFY"] = S.encode(S.normalize(logo, box=box))
    waves = [("f", 0, contours[:-1])]
    disc = [("f", 0, contours[-1:])]
    if len(contours) != 4:
        raise SystemExit("expected 4 logo contours, got %d" % len(contours))
    icons["SPOTIFY_WAVES"] = S.encode(S.normalize(waves, box=box))
    icons["SPOTIFY_DISC"] = S.encode(S.normalize(disc, box=box))

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
    body.write("}\n")
    OUT.write_text(body.getvalue(), encoding="utf-8")

    for name in sorted(icons):
        print("%-16s %5d chars" % (name, len(icons[name])))
    print("\nwrote", OUT)


if __name__ == "__main__":
    main()
