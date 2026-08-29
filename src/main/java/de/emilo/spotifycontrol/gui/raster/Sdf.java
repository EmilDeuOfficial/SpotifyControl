package de.emilo.spotifycontrol.gui.raster;

public final class Sdf {

    private Sdf() {
    }

    public static Shape circle(double cx, double cy, double radius) {
        return (x, y) -> Math.hypot(x - cx, y - cy) - radius;
    }

    public static Shape ring(double cx, double cy, double radius, double halfWidth) {
        return (x, y) -> Math.abs(Math.hypot(x - cx, y - cy) - radius) - halfWidth;
    }

    public static Shape box(double cx, double cy, double halfWidth, double halfHeight, double round) {
        return (x, y) -> {
            double qx = Math.abs(x - cx) - halfWidth + round;
            double qy = Math.abs(y - cy) - halfHeight + round;
            double outside = Math.hypot(Math.max(qx, 0.0), Math.max(qy, 0.0));
            double inside = Math.min(Math.max(qx, qy), 0.0);
            return outside + inside - round;
        };
    }

    public static Shape segment(double ax, double ay, double bx, double by, double halfWidth) {
        double bax = bx - ax;
        double bay = by - ay;
        double lengthSquared = bax * bax + bay * bay;
        return (x, y) -> {
            double pax = x - ax;
            double pay = y - ay;
            double h = lengthSquared == 0.0 ? 0.0 : clamp((pax * bax + pay * bay) / lengthSquared, 0.0, 1.0);
            return Math.hypot(pax - bax * h, pay - bay * h) - halfWidth;
        };
    }

    public static Shape triangle(double ax, double ay, double bx, double by, double cx, double cy, double round) {
        double e0x = bx - ax;
        double e0y = by - ay;
        double e1x = cx - bx;
        double e1y = cy - by;
        double e2x = ax - cx;
        double e2y = ay - cy;
        double sign = Math.signum(e0x * e2y - e0y * e2x);
        return (x, y) -> {
            double v0x = x - ax;
            double v0y = y - ay;
            double v1x = x - bx;
            double v1y = y - by;
            double v2x = x - cx;
            double v2y = y - cy;
            double t0 = clamp((v0x * e0x + v0y * e0y) / (e0x * e0x + e0y * e0y), 0.0, 1.0);
            double t1 = clamp((v1x * e1x + v1y * e1y) / (e1x * e1x + e1y * e1y), 0.0, 1.0);
            double t2 = clamp((v2x * e2x + v2y * e2y) / (e2x * e2x + e2y * e2y), 0.0, 1.0);
            double q0x = v0x - e0x * t0;
            double q0y = v0y - e0y * t0;
            double q1x = v1x - e1x * t1;
            double q1y = v1y - e1y * t1;
            double q2x = v2x - e2x * t2;
            double q2y = v2y - e2y * t2;
            double d0 = q0x * q0x + q0y * q0y;
            double d1 = q1x * q1x + q1y * q1y;
            double d2 = q2x * q2x + q2y * q2y;
            double s0 = sign * (v0x * e0y - v0y * e0x);
            double s1 = sign * (v1x * e1y - v1y * e1x);
            double s2 = sign * (v2x * e2y - v2y * e2x);
            double distance = Math.sqrt(Math.min(d0, Math.min(d1, d2)));
            double side = Math.min(s0, Math.min(s1, s2));
            return -distance * Math.signum(side) - round;
        };
    }

    public static Shape arc(double cx, double cy, double radius, double startDegrees, double endDegrees,
                            double halfWidth) {
        double start = Math.toRadians(startDegrees);
        double end = Math.toRadians(endDegrees);
        double startX = cx + Math.cos(start) * radius;
        double startY = cy + Math.sin(start) * radius;
        double endX = cx + Math.cos(end) * radius;
        double endY = cy + Math.sin(end) * radius;
        double mid = (start + end) / 2.0;
        double halfSpan = (end - start) / 2.0;
        return (x, y) -> {
            double angle = Math.atan2(y - cy, x - cx);
            double delta = Math.atan2(Math.sin(angle - mid), Math.cos(angle - mid));
            if (Math.abs(delta) <= halfSpan) {
                return Math.abs(Math.hypot(x - cx, y - cy) - radius) - halfWidth;
            }
            double toStart = Math.hypot(x - startX, y - startY);
            double toEnd = Math.hypot(x - endX, y - endY);
            return Math.min(toStart, toEnd) - halfWidth;
        };
    }

    public static Shape rotate(Shape shape, double cx, double cy, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return (x, y) -> {
            double dx = x - cx;
            double dy = y - cy;
            return shape.distance(cx + dx * cos + dy * sin, cy - dx * sin + dy * cos);
        };
    }

    public static Shape exact(Shape shape, int resolution) {
        double[] values = new double[resolution * resolution];
        double cell = 1.0 / resolution;
        for (int row = 0; row < resolution; row++) {
            for (int column = 0; column < resolution; column++) {
                values[row * resolution + column] = shape.distance((column + 0.5) * cell, (row + 0.5) * cell);
            }
        }
        double[] boundary = new double[resolution * resolution * 4];
        int count = 0;
        for (int row = 0; row < resolution; row++) {
            for (int column = 0; column < resolution; column++) {
                double here = values[row * resolution + column];
                if (column + 1 < resolution) {
                    double right = values[row * resolution + column + 1];
                    if ((here < 0) != (right < 0)) {
                        double t = here / (here - right);
                        boundary[count++] = (column + 0.5 + t) * cell;
                        boundary[count++] = (row + 0.5) * cell;
                    }
                }
                if (row + 1 < resolution) {
                    double below = values[(row + 1) * resolution + column];
                    if ((here < 0) != (below < 0)) {
                        double t = here / (here - below);
                        boundary[count++] = (column + 0.5) * cell;
                        boundary[count++] = (row + 0.5 + t) * cell;
                    }
                }
            }
        }
        double[] points = java.util.Arrays.copyOf(boundary, count);
        return (x, y) -> {
            double nearest = Double.MAX_VALUE;
            for (int i = 0; i < points.length; i += 2) {
                double dx = points[i] - x;
                double dy = points[i + 1] - y;
                double squared = dx * dx + dy * dy;
                if (squared < nearest) {
                    nearest = squared;
                }
            }
            double distance = Math.sqrt(nearest);
            return shape.distance(x, y) < 0 ? -distance : distance;
        };
    }

    public static Shape empty() {
        return (x, y) -> Double.MAX_VALUE;
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
