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
