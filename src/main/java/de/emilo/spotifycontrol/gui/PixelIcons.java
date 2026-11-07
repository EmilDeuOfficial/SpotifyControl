package de.emilo.spotifycontrol.gui;

import net.minecraft.client.gui.GuiGraphics;

public final class PixelIcons {

    private PixelIcons() {
    }

    public static void play(GuiGraphics graphics, int cx, int cy, int size, int color) {
        triangleRight(graphics, cx - size / 3, cy, size, color);
    }

    public static void pause(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        int barWidth = Math.max(2, size / 4);
        graphics.fill(cx - half, cy - half, cx - half + barWidth, cy + half, color);
        graphics.fill(cx + half - barWidth, cy - half, cx + half, cy + half, color);
    }

    public static void next(GuiGraphics graphics, int cx, int cy, int size, int color) {
        triangleRight(graphics, cx - size / 2, cy, size - 2, color);
        graphics.fill(cx + size / 2 - 2, cy - size / 2, cx + size / 2, cy + size / 2, color);
    }

    public static void previous(GuiGraphics graphics, int cx, int cy, int size, int color) {
        triangleLeft(graphics, cx + size / 2, cy, size - 2, color);
        graphics.fill(cx - size / 2, cy - size / 2, cx - size / 2 + 2, cy + size / 2, color);
    }

    public static void triangleRight(GuiGraphics graphics, int left, int cy, int size, int color) {
        for (int i = 0; i < size; i++) {
            int height = size - i;
            graphics.fill(left + i, cy - height / 2, left + i + 1, cy + height / 2 + height % 2, color);
        }
    }

    public static void triangleLeft(GuiGraphics graphics, int right, int cy, int size, int color) {
        for (int i = 0; i < size; i++) {
            int height = size - i;
            graphics.fill(right - i - 1, cy - height / 2, right - i, cy + height / 2 + height % 2, color);
        }
    }

    public static void triangleDown(GuiGraphics graphics, int cx, int top, int size, int color) {
        for (int i = 0; i < size; i++) {
            int width = size - i;
            graphics.fill(cx - width / 2, top + i, cx + width / 2 + width % 2, top + i + 1, color);
        }
    }

    public static void shuffle(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        line(graphics, cx - half, cy - half + 1, cx + half - 3, cy + half - 1, color);
        line(graphics, cx - half, cy + half - 1, cx + half - 3, cy - half + 1, color);
        triangleRight(graphics, cx + half - 3, cy - half + 1, 3, color);
        triangleRight(graphics, cx + half - 3, cy + half - 1, 3, color);
    }

    public static void repeat(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        graphics.fill(cx - half, cy - half, cx + half - 2, cy - half + 1, color);
        graphics.fill(cx - half + 2, cy + half - 1, cx + half, cy + half, color);
        graphics.fill(cx + half - 1, cy - half, cx + half, cy + 1, color);
        graphics.fill(cx - half, cy - 1, cx - half + 1, cy + half, color);
        triangleDown(graphics, cx + half - 1, cy + 1, 4, color);
        triangleRight(graphics, cx - half - 1, cy - half - 1, 3, color);
    }

    public static void heart(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int radius = Math.max(2, size / 4);
        Theme.circle(graphics, cx - radius, cy - radius / 2, radius, color);
        Theme.circle(graphics, cx + radius, cy - radius / 2, radius, color);
        int rows = size / 2 + 1;
        for (int i = 0; i < rows; i++) {
            int width = size - i * 2;
            if (width <= 0) {
                break;
            }
            graphics.fill(cx - width / 2, cy - radius / 2 + i, cx + width / 2, cy - radius / 2 + i + 1, color);
        }
    }

    public static void search(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int radius = Math.max(3, size / 2 - 2);
        ring(graphics, cx - 1, cy - 1, radius, color);
        line(graphics, cx + radius - 2, cy + radius - 2, cx + radius + 1, cy + radius + 1, color);
    }

    public static void home(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        for (int i = 0; i <= half; i++) {
            graphics.fill(cx - i, cy - half + i, cx + i + 1, cy - half + i + 1, color);
        }
        graphics.fill(cx - half + 2, cy, cx + half - 1, cy + half, color);
    }

    public static void library(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        graphics.fill(cx - half, cy - half, cx - half + 2, cy + half, color);
        graphics.fill(cx - half + 4, cy - half, cx - half + 6, cy + half, color);
        graphics.fill(cx + 1, cy - half + 2, cx + 3, cy + half, color);
        graphics.fill(cx + 4, cy - half + 3, cx + 6, cy + half, color);
    }

    public static void speaker(GuiGraphics graphics, int cx, int cy, int size, int level, int color) {
        int half = size / 2;
        graphics.fill(cx - half, cy - 2, cx - half + 3, cy + 2, color);
        triangleLeft(graphics, cx - half + 3, cy, half, color);
        if (level > 0) {
            graphics.fill(cx + 1, cy - 2, cx + 2, cy + 2, color);
        }
        if (level > 1) {
            graphics.fill(cx + 4, cy - 4, cx + 5, cy + 4, color);
        }
        if (level > 2) {
            graphics.fill(cx + 7, cy - 6, cx + 8, cy + 6, color);
        }
    }

    public static void devices(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        Theme.outline(graphics, cx - half, cy - half, size, size - 3, color);
        graphics.fill(cx - 3, cy + half - 2, cx + 4, cy + half - 1, color);
    }

    public static void plus(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        graphics.fill(cx - half, cy - 1, cx + half, cy + 1, color);
        graphics.fill(cx - 1, cy - half, cx + 1, cy + half, color);
    }

    public static void close(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        line(graphics, cx - half, cy - half, cx + half, cy + half, color);
        line(graphics, cx - half, cy + half, cx + half, cy - half, color);
    }

    public static void queue(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        graphics.fill(cx - half, cy - half, cx + half, cy - half + 2, color);
        graphics.fill(cx - half, cy - 1, cx + half, cy + 1, color);
        graphics.fill(cx - half, cy + half - 2, cx + 2, cy + half, color);
        triangleRight(graphics, cx + 4, cy + half - 1, 4, color);
    }

    /** A lyrics sheet: a page with two text lines and a note in its lower corner. */
    public static void lyrics(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int half = size / 2;
        int left = cx - half;
        int top = cy - half;
        int right = cx + half - 3;
        int bottom = cy + half - 2;
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(left, bottom - 1, cx - 1, bottom, color);
        graphics.fill(right - 1, top, right, cy - 2, color);
        graphics.fill(left + 3, cy - 2, cx + 1, cy - 1, color);
        graphics.fill(left + 3, cy + 1, cx - 1, cy + 2, color);
        graphics.fill(cx + 3, cy, cx + 4, cy + half, color);
        graphics.fill(cx + 4, cy, cx + 6, cy + 1, color);
        graphics.fill(cx + 1, cy + half - 2, cx + 4, cy + half + 1, color);
    }

    public static void people(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int radius = Math.max(2, size / 5);
        Theme.circle(graphics, cx - radius - 1, cy - radius, radius, color);
        Theme.circle(graphics, cx + radius + 1, cy - radius, radius, color);
        Theme.rounded(graphics, cx - size / 2, cy + 1, size, size / 2, 2, color);
    }

    public static void gear(GuiGraphics graphics, int cx, int cy, int size, int color) {
        int radius = Math.max(3, size / 2 - 1);
        ring(graphics, cx, cy, radius, color);
        graphics.fill(cx - 1, cy - radius - 2, cx + 1, cy - radius + 1, color);
        graphics.fill(cx - 1, cy + radius - 1, cx + 1, cy + radius + 2, color);
        graphics.fill(cx - radius - 2, cy - 1, cx - radius + 1, cy + 1, color);
        graphics.fill(cx + radius - 1, cy - 1, cx + radius + 2, cy + 1, color);
    }

    public static void ring(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        int inner = Math.max(1, radius - 2);
        for (int dy = -radius; dy <= radius; dy++) {
            int outer = (int) Math.sqrt((double) radius * radius - (double) dy * dy);
            if (Math.abs(dy) > inner) {
                graphics.fill(cx - outer, cy + dy, cx + outer, cy + dy + 1, color);
                continue;
            }
            int hole = (int) Math.sqrt((double) inner * inner - (double) dy * dy);
            graphics.fill(cx - outer, cy + dy, cx - hole, cy + dy + 1, color);
            graphics.fill(cx + hole, cy + dy, cx + outer, cy + dy + 1, color);
        }
    }

    public static void line(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int error = dx - dy;
        int x = x1;
        int y = y1;
        for (int guard = 0; guard < 512; guard++) {
            graphics.fill(x, y, x + 1, y + 1, color);
            if (x == x2 && y == y2) {
                return;
            }
            int doubled = error * 2;
            if (doubled > -dy) {
                error -= dy;
                x += sx;
            }
            if (doubled < dx) {
                error += dx;
                y += sy;
            }
        }
    }
}
