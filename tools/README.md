# Icon-Konverter

`src/main/java/de/emilo/spotifycontrol/gui/raster/VectorIcons.java` wird aus den
SVG-Dateien in `img/` erzeugt und nicht von Hand bearbeitet. Nach einer Aenderung
an `img/` neu generieren, aus dem Projektstamm:

    python tools/gen_icons.py

`svgconv.py` liest die SVGs (Pfade, Rechtecke, Kreise, Ellipsen, Linien,
Polygone), loest verschachtelte Transforms auf, rechnet Arcs und quadratische
Segmente in kubische um und normalisiert jedes Icon seitenverhaeltnistreu und
zentriert in eine Einheitsbox. Weisse Hintergrundflaechen und `fill="none"`
werden verworfen.

`gen_icons.py` bildet die Dateien auf die Icon-Namen ab. Der zweite Wert je
Eintrag ist ein optischer Rand innerhalb der Einheitsbox, mit dem sich einzelne
Icons gegen den Rest des Satzes auswiegen lassen. `CLOSE`, `CHECK` und `NOTE`
deckt der SVG-Satz nicht ab; sie stehen als Pfade direkt in `gen_icons.py`,
im selben 24x24-Raum und mit derselben Strichstaerke.

Gezeichnet werden die Daten von `VectorRaster`, das Fuellungen per Scanline mit
fuenf Sub-Zeilen und exakter horizontaler Deckung rastert und Striche ueber den
Abstand zur Polylinie, woraus sich die runden Enden und Ecken ergeben.

Das Logo ist der einzige mehrfarbige Eintrag. `svgconv.load_by_fill` haelt seine
Farbgruppen auseinander, `gen_icons.py` schreibt sie als `LOGO_BARS` und
`LOGO_HEADS` heraus, dazu die Konstanten `LOGO_BARS_COLOR` und
`LOGO_HEADS_COLOR`. Alle Logo-Formen werden gegen dieselbe Bounding-Box
normalisiert, sonst stehen sie uebereinander nicht mehr richtig. Eine Umfaerbung
in `img/logo.svg` erreicht das Spiel dadurch ohne Aenderung an Java-Code.

`gen_logo.py` rendert dasselbe SVG nach
`src/main/resources/assets/spotifycontrol/icon.png`, dem Icon in der Modliste.
Es rastert jede Form einzeln und vereinigt die Deckungen ueber das Maximum:
Balken und Haelse ueberlappen absichtlich, und ein gemeinsamer Even-Odd-Durchlauf
wuerde aus jeder Ueberlappung ein Loch machen.

    python tools/gen_logo.py
