package de.emilo.spotifycontrol.gui.widget;

import de.emilo.spotifycontrol.gui.Anim;
import de.emilo.spotifycontrol.gui.Theme;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The scroll position and the scrollbar of one list. Every list in the interface owns one.
 *
 * <p>The position is kept twice. {@code target} is where the wheel and the thumb have put the list,
 * {@code shown} is where it is actually drawn and walks after the target a frame at a time. Only the
 * shown value ever leaves this class through {@link #offset()}, so the drawing and the hit testing
 * that works out which row the cursor is over read the same number and cannot disagree.
 *
 * <p>The eased values live in fields rather than in {@link Anim}, which is keyed by name: there are
 * as many ScrollAreas as there are lists, and one shared name would have them all scroll together.
 */
public class ScrollArea {

    /** Where the wheel and the thumb have put the list. */
    private double target;

    /** Where the list is drawn, on its way to {@link #target}. */
    private double shown;

    private int contentHeight;
    private int viewportHeight;

    /**
      * The content height changes outside a frame as well, whenever a list is handed new rows, so
      * this only corrects the bounds. The easing step lives in {@link #setViewportHeight(int)}.
      */
    public void setContentHeight(int contentHeight) {
        this.contentHeight = Math.max(0, contentHeight);
        clamp();
    }

    /**
      * Where the frame is stepped. Every render calls this first and nothing else calls it at all,
      * which makes it the one place per frame where the eased position may move.
      *
      * <p>It has to be a single point like this because {@link #offset()} is asked twice for
      * different reasons: once by the drawing, and once by the click handling that divides it by the
      * row height to find the row under the cursor. Easing inside {@code offset()} would hand a
      * different number to every caller, and the click would land on the row above or below the one
      * the user saw. Stepping here leaves {@code offset()} a plain read that answers the same thing
      * all frame, and between frames it answers what was last drawn, which is exactly what a click
      * should be measured against.
      */
    public void setViewportHeight(int viewportHeight) {
        this.viewportHeight = Math.max(1, viewportHeight);
        clamp();
        easeScroll();
    }

    public int offset() {
        return (int) Math.round(shown);
    }

    /** Snaps both values home. A list that was handed a different page has not been scrolled. */
    public void reset() {
        target = 0.0D;
        shown = 0.0D;
    }

    public boolean scrollBy(double amount) {
        // The wheel turned over this list, so the bar wakes whether or not there was room left to
        // move. A bar that stays dim while the user is scrolling reads as a bar that is not working.
        touchedMillis = System.currentTimeMillis();
        double before = target;
        target -= amount * 24.0D;
        clamp();
        return before != target;
    }

    private void clamp() {
        double max = Math.max(0, contentHeight - viewportHeight);
        target = Math.max(0.0D, Math.min(max, target));
        shown = Math.max(0.0D, Math.min(max, shown));
    }

    public boolean scrollable() {
        return contentHeight > viewportHeight;
    }

    /**
      * The exponential step {@link Anim#to} uses, in the units this class works in. Frame rate
      * independent: the same fraction of what is left is covered per second either way.
      */
    private void easeScroll() {
        if (!Anim.enabled()) {
            shown = target;
            return;
        }
        shown += (target - shown) * (1.0D - Math.exp(-Anim.FAST * Anim.delta()));
        // Half a pixel cannot survive the rounding in offset(), so settle there instead of chasing
        // a remainder that will never be drawn.
        if (Math.abs(target - shown) < 0.5D) {
            shown = target;
        }
    }

    /** The same step for the values that describe the bar itself, which run from 0 to 1. */
    private static float approach(float current, float goal, float speed) {
        float next = current + (goal - current) * (1.0f - (float) Math.exp(-speed * Anim.delta()));
        return Math.abs(goal - next) < 0.0015f ? goal : next;
    }

    /** Width of the drawn bar, and the width the hit test uses whatever the thumb is doing. */
    private static final int BAR_WIDTH = 4;

    /** How wide the thumb grows while the cursor is on it or a drag is running. */
    private static final int BAR_WIDTH_GRABBED = 6;

    /** How far beside the bar a press still counts as grabbing it. */
    private static final int GRAB_PAD = 3;

    /** How much of the bar is left once nothing has touched the list for a while. */
    private static final float IDLE_REVEAL = 0.35f;

    /** How long the bar stays lit after the last movement, so a pause does not blink it out. */
    private static final long AWAKE_MILLIS = 700L;

    private int barX;
    private int barY;
    private int barHeight;
    private boolean dragging;
    private double grabOffset;

    /** 0 while the bar rests at {@link #IDLE_REVEAL}, 1 while it is fully lit. */
    private float reveal;

    /** 0 at the drawn width, 1 at the grabbed width. */
    private float widen;

    /** Whether the cursor was beside the bar when a list last asked, cleared once the bar has read it. */
    private boolean near;

    private long touchedMillis;

    /**
      * Whether the bar claims this position. Lists ask before they light up a row: the bar sits in
      * the last few pixels of the row and reaches a little further still, so without this the row
      * beside it lights up the moment the cursor goes for the bar - and stays lit under the cursor
      * for the whole drag.
      */
    public boolean claims(double mouseX, double mouseY) {
        if (dragging) {
            near = true;
            return true;
        }
        boolean beside = scrollable() && barHeight > 0
                && mouseX >= barX - GRAB_PAD && mouseX <= barX + BAR_WIDTH + GRAB_PAD
                && mouseY >= barY && mouseY < barY + barHeight;
        // Remembered for the drawing. renderScrollbar is handed no cursor and this is the only place
        // the bar hears about one, so a list that never asks simply leaves its bar at rest.
        near |= beside;
        return beside;
    }

    public void renderScrollbar(GuiGraphics graphics, int x, int y, int height) {
        barX = x;
        barY = y;
        barHeight = height;
        // Read what claims() left behind and start the next frame blank, so the bar follows the
        // cursor leaving as well as arriving.
        boolean beside = near;
        near = false;
        if (!scrollable()) {
            dragging = false;
            reveal = 0.0f;
            widen = 0.0f;
            return;
        }
        boolean grabbed = dragging || beside;
        boolean awake = grabbed || shown != target
                || System.currentTimeMillis() - touchedMillis < AWAKE_MILLIS;
        // With the setting off the bar is what it always was: fully lit and four pixels across.
        boolean animated = Anim.enabled();
        reveal = animated ? approach(reveal, awake ? 1.0f : 0.0f, Anim.HOVER) : 1.0f;
        widen = animated ? approach(widen, grabbed ? 1.0f : 0.0f, Anim.HOVER) : 0.0f;

        // Both bar colours end up in the corner cache, which is keyed by the exact colour, so the
        // fade is snapped once here instead of at the two calls below. Snapping the driver rather
        // than the finished alphas keeps the 0.35 scaling exact, so the resting bar and the fully
        // lit bar stay the colours they always were.
        float lit = IDLE_REVEAL + (1.0f - IDLE_REVEAL) * Anim.stepped(reveal);
        int thumbHeight = thumbHeight();
        int barWidth = BAR_WIDTH + Math.round(widen * (BAR_WIDTH_GRABBED - BAR_WIDTH));
        // The bar sits against the right edge of its list, so thickening has to go inwards; growing
        // the other way would push it over whatever sits beside the list. The track thickens with
        // the thumb rather than staying behind it - widening the thumb alone left it hanging two
        // pixels out to the left of its own track, which reads as the thumb having slipped sideways.
        int barLeft = x + BAR_WIDTH - barWidth;
        Theme.rounded(graphics, barLeft, y, barWidth, height, 2, Theme.alpha(Theme.TRACK, 0.35f * lit));
        Theme.rounded(graphics, barLeft, thumbY(thumbHeight), barWidth, thumbHeight, barWidth / 2,
                Theme.alpha(Theme.THUMB, lit));
    }

    private int thumbHeight() {
        return Math.max(20, Math.min(barHeight, barHeight * viewportHeight / Math.max(1, contentHeight)));
    }

    /**
      * Follows the drawn position rather than the target, so the thumb glides along with the list.
      * The press below reads it too, which is what keeps a grab landing on the thumb the user sees.
      */
    private int thumbY(int thumbHeight) {
        int maxScroll = Math.max(1, contentHeight - viewportHeight);
        return barY + (int) ((barHeight - thumbHeight) * (shown / maxScroll));
    }

    /**
      * Grabs the scrollbar. The hit area is a little wider than the drawn bar, which is only four
      * pixels across. A press beside the thumb jumps to that spot and keeps dragging from there.
      */
    public boolean mouseClicked(double mouseX, double mouseY) {
        // A fresh press decides from scratch. claims() reports a running drag as its own, and a
        // release that never arrived must not leave the bar unreachable.
        dragging = false;
        if (!claims(mouseX, mouseY)) {
            return false;
        }
        touchedMillis = System.currentTimeMillis();
        int thumbHeight = thumbHeight();
        int thumbY = thumbY(thumbHeight);
        if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
            grabOffset = mouseY - thumbY;
        } else {
            grabOffset = thumbHeight / 2.0;
            // The target lands on the pressed spot exactly, but the drawn position is left to catch
            // up by itself, which turns the jump into a glide.
            moveThumbTo(mouseY - grabOffset, thumbHeight, false);
        }
        dragging = true;
        return true;
    }

    public boolean mouseDragged(double mouseY) {
        if (!dragging) {
            return false;
        }
        touchedMillis = System.currentTimeMillis();
        // A drag is the one case that must not ease. The thumb is under the cursor, and any lag
        // there reads as the bar slipping out of the user's grip.
        moveThumbTo(mouseY - grabOffset, thumbHeight(), true);
        return true;
    }

    public void mouseReleased() {
        // Only a bar that was actually being held counts as touched. A release is handed to every
        // scroll area in the window, not just the one under the cursor, so stamping this
        // unconditionally woke the sidebar, the list and the settings bar together for the better
        // part of a second on any click anywhere at all.
        if (dragging) {
            touchedMillis = System.currentTimeMillis();
        }
        dragging = false;
    }

    private void moveThumbTo(double top, int thumbHeight, boolean snap) {
        int travel = Math.max(1, barHeight - thumbHeight);
        double max = Math.max(0, contentHeight - viewportHeight);
        target = (top - barY) / travel * max;
        if (snap) {
            shown = target;
        }
        clamp();
    }
}
