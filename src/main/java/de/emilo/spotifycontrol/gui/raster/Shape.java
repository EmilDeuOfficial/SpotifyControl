package de.emilo.spotifycontrol.gui.raster;

@FunctionalInterface
public interface Shape {

    double distance(double x, double y);

    default Shape union(Shape other) {
        return (x, y) -> Math.min(distance(x, y), other.distance(x, y));
    }

    default Shape subtract(Shape other) {
        return (x, y) -> Math.max(distance(x, y), -other.distance(x, y));
    }

    default Shape outline(double halfWidth) {
        return (x, y) -> {
            double d = distance(x, y);
            return Math.max(d - halfWidth, -d - halfWidth);
        };
    }

    default Shape mirrorX() {
        return (x, y) -> distance(1.0 - x, y);
    }
}
