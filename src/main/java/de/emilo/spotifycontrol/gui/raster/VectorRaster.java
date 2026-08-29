package de.emilo.spotifycontrol.gui.raster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rasterizes the outline data in {@link VectorIcons} into anti-aliased pixels.
 *
 * <p>Filled parts are scan converted with five sub-scanlines per row and exact
 * horizontal span coverage. Stroked parts are resolved as the distance to the
 * flattened polyline, which yields the round caps and joins the sources ask
 * for. Parts of one icon are combined by taking the larger coverage so
 * overlapping shapes never darken each other.
 */
public final class VectorRaster {

    static final int FILL_NONZERO = 0;
    static final int FILL_EVENODD = 1;
    static final int STROKE = 2;

    private static final int SUBSAMPLES = 5;
    private static final double SEGMENT_LENGTH = 1.5;
    private static final int MAX_SEGMENTS = 96;

    private static final Map<String, Icon> CACHE = new HashMap<>();

    private VectorRaster() {
    }

    public static int[] rasterize(String data, int pixels, int color) {
        return rasterize(data, pixels, color, false);
    }

    /**
      * {@code sharp} rounds coverage to fully on or fully off. Anti-aliasing at a low resolution
      * turns into grey mush once it is stretched back up; pixel art wants hard edges instead.
      */
    public static int[] rasterize(String data, int pixels, int color, boolean sharp) {
        Icon icon = icon(data);
        float[] coverage = new float[pixels * pixels];
        float[] scratch = new float[pixels * pixels];
        for (Part part : icon.parts) {
            Arrays.fill(scratch, 0.0f);
            List<double[]> polylines = flatten(part, pixels);
            if (part.kind == STROKE) {
                stroke(scratch, polylines, part.halfWidth * pixels, pixels);
            } else {
                fill(scratch, polylines, pixels, part.kind == FILL_EVENODD);
            }
            for (int i = 0; i < coverage.length; i++) {
                if (scratch[i] > coverage[i]) {
                    coverage[i] = scratch[i];
                }
            }
        }
        int alpha = color >>> 24;
        int rgb = color & 0xFFFFFF;
        int[] argb = new int[pixels * pixels];
        for (int i = 0; i < argb.length; i++) {
            float c = coverage[i];
            if (c > 1.0f) {
                c = 1.0f;
            }
            if (sharp) {
                c = c >= 0.5f ? 1.0f : 0.0f;
            }
            argb[i] = (Math.round(alpha * c) << 24) | rgb;
        }
        return argb;
    }

    // ------------------------------------------------------------------ fill

    private static void fill(float[] coverage, List<double[]> polylines, int pixels, boolean evenOdd) {
        int total = 0;
        for (double[] points : polylines) {
            total += points.length / 2;
        }
        double[] ax = new double[total];
        double[] ay = new double[total];
        double[] bx = new double[total];
        double[] by = new double[total];
        int edges = 0;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] points : polylines) {
            int count = points.length / 2;
            if (count < 2) {
                continue;
            }
            for (int i = 0; i < count; i++) {
                int j = i + 1 == count ? 0 : i + 1;
                double y0 = points[i * 2 + 1];
                double y1 = points[j * 2 + 1];
                if (y0 == y1) {
                    continue;
                }
                ax[edges] = points[i * 2];
                ay[edges] = y0;
                bx[edges] = points[j * 2];
                by[edges] = y1;
                edges++;
                minY = Math.min(minY, Math.min(y0, y1));
                maxY = Math.max(maxY, Math.max(y0, y1));
            }
        }
        if (edges == 0) {
            return;
        }
        double[] crossings = new double[edges];
        int[] directions = new int[edges];
        float weight = 1.0f / SUBSAMPLES;
        int firstRow = Math.max(0, (int) minY);
        int lastRow = Math.min(pixels - 1, (int) Math.ceil(maxY));
        for (int row = firstRow; row <= lastRow; row++) {
            for (int sub = 0; sub < SUBSAMPLES; sub++) {
                double y = row + (sub + 0.5) / SUBSAMPLES;
                int found = 0;
                for (int e = 0; e < edges; e++) {
                    double y0 = ay[e];
                    double y1 = by[e];
                    if (y0 <= y ? y >= y1 : y < y1) {
                        continue;
                    }
                    double t = (y - y0) / (y1 - y0);
                    double x = ax[e] + t * (bx[e] - ax[e]);
                    int at = found++;
                    while (at > 0 && crossings[at - 1] > x) {
                        crossings[at] = crossings[at - 1];
                        directions[at] = directions[at - 1];
                        at--;
                    }
                    crossings[at] = x;
                    directions[at] = y1 > y0 ? 1 : -1;
                }
                int winding = 0;
                for (int i = 0; i + 1 < found; i++) {
                    winding += directions[i];
                    boolean inside = evenOdd ? (i & 1) == 0 : winding != 0;
                    if (inside) {
                        span(coverage, row, pixels, crossings[i], crossings[i + 1], weight);
                    }
                }
            }
        }
    }

    private static void span(float[] coverage, int row, int pixels, double from, double to, float weight) {
        if (from < 0.0) {
            from = 0.0;
        }
        if (to > pixels) {
            to = pixels;
        }
        if (to <= from) {
            return;
        }
        int base = row * pixels;
        int first = (int) from;
        int last = (int) Math.ceil(to) - 1;
        if (last >= pixels) {
            last = pixels - 1;
        }
        if (first == last) {
            coverage[base + first] += (float) ((to - from) * weight);
            return;
        }
        coverage[base + first] += (float) ((first + 1 - from) * weight);
        for (int i = first + 1; i < last; i++) {
            coverage[base + i] += weight;
        }
        coverage[base + last] += (float) ((to - last) * weight);
    }

    // ---------------------------------------------------------------- stroke

    private static void stroke(float[] coverage, List<double[]> polylines, double halfWidth, int pixels) {
        double[] distance = new double[pixels * pixels];
        Arrays.fill(distance, Double.MAX_VALUE);
        double reach = halfWidth + 1.0;
        for (double[] points : polylines) {
            int count = points.length / 2;
            if (count == 1) {
                capsule(distance, pixels, points[0], points[1], points[0], points[1], reach);
                continue;
            }
            for (int i = 0; i + 1 < count; i++) {
                capsule(distance, pixels, points[i * 2], points[i * 2 + 1],
                        points[i * 2 + 2], points[i * 2 + 3], reach);
            }
        }
        for (int i = 0; i < coverage.length; i++) {
            double d = distance[i];
            if (d == Double.MAX_VALUE) {
                continue;
            }
            double c = 0.5 + halfWidth - d;
            coverage[i] = (float) (c <= 0.0 ? 0.0 : Math.min(1.0, c));
        }
    }

    private static void capsule(double[] distance, int pixels, double ax, double ay, double bx, double by,
                                double reach) {
        int fromX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach));
        int toX = Math.min(pixels - 1, (int) Math.ceil(Math.max(ax, bx) + reach));
        int fromY = Math.max(0, (int) Math.floor(Math.min(ay, by) - reach));
        int toY = Math.min(pixels - 1, (int) Math.ceil(Math.max(ay, by) + reach));
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        for (int py = fromY; py <= toY; py++) {
            double y = py + 0.5;
            int base = py * pixels;
            for (int px = fromX; px <= toX; px++) {
                double x = px + 0.5;
                double t = lengthSquared == 0.0 ? 0.0 : ((x - ax) * dx + (y - ay) * dy) / lengthSquared;
                if (t < 0.0) {
                    t = 0.0;
                } else if (t > 1.0) {
                    t = 1.0;
                }
                double ox = x - (ax + t * dx);
                double oy = y - (ay + t * dy);
                double d = Math.sqrt(ox * ox + oy * oy);
                if (d < distance[base + px]) {
                    distance[base + px] = d;
                }
            }
        }
    }

    // --------------------------------------------------------------- flatten

    private static List<double[]> flatten(Part part, int pixels) {
        List<double[]> out = new ArrayList<>(part.contours.length);
        for (Contour contour : part.contours) {
            double[] stream = contour.stream;
            Points points = new Points();
            double cx = stream[0] * pixels;
            double cy = stream[1] * pixels;
            points.add(cx, cy);
            int i = 2;
            while (i < stream.length) {
                if (stream[i++] == 1.0) {
                    cx = stream[i++] * pixels;
                    cy = stream[i++] * pixels;
                    points.add(cx, cy);
                } else {
                    double x1 = stream[i++] * pixels;
                    double y1 = stream[i++] * pixels;
                    double x2 = stream[i++] * pixels;
                    double y2 = stream[i++] * pixels;
                    double x = stream[i++] * pixels;
                    double y = stream[i++] * pixels;
                    cubic(points, cx, cy, x1, y1, x2, y2, x, y);
                    cx = x;
                    cy = y;
                }
            }
            if (contour.closed) {
                points.add(stream[0] * pixels, stream[1] * pixels);
            }
            out.add(points.toArray());
        }
        return out;
    }

    private static void cubic(Points points, double x0, double y0, double x1, double y1, double x2, double y2,
                              double x3, double y3) {
        double hull = distance(x0, y0, x1, y1) + distance(x1, y1, x2, y2) + distance(x2, y2, x3, y3);
        int steps = (int) Math.ceil(hull / SEGMENT_LENGTH);
        if (steps < 2) {
            steps = 2;
        } else if (steps > MAX_SEGMENTS) {
            steps = MAX_SEGMENTS;
        }
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double u = 1.0 - t;
            double a = u * u * u;
            double b = 3.0 * u * u * t;
            double c = 3.0 * u * t * t;
            double d = t * t * t;
            points.add(a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3);
        }
    }

    private static double distance(double x0, double y0, double x1, double y1) {
        return Math.hypot(x1 - x0, y1 - y0);
    }

    // ----------------------------------------------------------------- parse

    static Icon icon(String data) {
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(data, VectorRaster::parse);
        }
    }

    private static Icon parse(String data) {
        List<Part> parts = new ArrayList<>();
        for (String chunk : data.split("\\|")) {
            int colon = chunk.indexOf(':');
            String head = chunk.substring(0, colon);
            int kind;
            double halfWidth = 0.0;
            if (head.charAt(0) == 's') {
                kind = STROKE;
                halfWidth = Double.parseDouble(head.substring(1));
            } else {
                kind = head.charAt(1) == '1' ? FILL_EVENODD : FILL_NONZERO;
            }
            parts.add(new Part(kind, halfWidth, contours(chunk.substring(colon + 1))));
        }
        return new Icon(parts.toArray(new Part[0]));
    }

    private static Contour[] contours(String path) {
        List<Contour> out = new ArrayList<>();
        Numbers stream = new Numbers();
        boolean closed = false;
        int i = 0;
        int length = path.length();
        while (i < length) {
            char c = path.charAt(i);
            if (c == 'M') {
                if (stream.size > 0) {
                    out.add(new Contour(stream.toArray(), closed));
                    stream = new Numbers();
                }
                closed = false;
                i++;
                i = numbers(path, i, stream, 2);
            } else if (c == 'L') {
                stream.add(1.0);
                i = numbers(path, i + 1, stream, 2);
            } else if (c == 'C') {
                stream.add(2.0);
                i = numbers(path, i + 1, stream, 6);
            } else if (c == 'Z') {
                closed = true;
                i++;
            } else {
                i++;
            }
        }
        if (stream.size > 0) {
            out.add(new Contour(stream.toArray(), closed));
        }
        return out.toArray(new Contour[0]);
    }

    private static int numbers(String path, int i, Numbers out, int count) {
        int length = path.length();
        for (int n = 0; n < count; n++) {
            while (i < length && (path.charAt(i) == ' ' || path.charAt(i) == ',')) {
                i++;
            }
            int start = i;
            if (i < length && (path.charAt(i) == '-' || path.charAt(i) == '+')) {
                i++;
            }
            while (i < length) {
                char c = path.charAt(i);
                if ((c < '0' || c > '9') && c != '.') {
                    break;
                }
                i++;
            }
            out.add(Double.parseDouble(path.substring(start, i)));
        }
        return i;
    }

    static final class Icon {
        final Part[] parts;

        Icon(Part[] parts) {
            this.parts = parts;
        }
    }

    static final class Part {
        final int kind;
        final double halfWidth;
        final Contour[] contours;

        Part(int kind, double halfWidth, Contour[] contours) {
            this.kind = kind;
            this.halfWidth = halfWidth;
            this.contours = contours;
        }
    }

    static final class Contour {
        final double[] stream;
        final boolean closed;

        Contour(double[] stream, boolean closed) {
            this.stream = stream;
            this.closed = closed;
        }
    }

    private static final class Numbers {
        double[] values = new double[32];
        int size;

        void add(double value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        double[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    private static final class Points {
        double[] values = new double[64];
        int size;

        void add(double x, double y) {
            if (size + 2 > values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = x;
            values[size++] = y;
        }

        double[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
