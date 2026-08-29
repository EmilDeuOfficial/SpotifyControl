package de.emilo.spotifycontrol.gui;

import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The animation clock for the whole interface.
 *
 * <p>Every moving value lives here under a name, not in the widget that draws
 * it. Widgets are redrawn from scratch every frame and keep no state between
 * them, so a fade needs somewhere outside to remember where it had got to. The
 * name is that place: a widget asks for {@code hover("nav:2", isHovered)} and
 * gets back a number that walks towards its target over the following frames.
 *
 * <p>Names are per position, not per content - the fifth row of a list is
 * always {@code "track:5"}. Naming them after the track instead would leave a
 * value behind for every song ever scrolled past, and the interface would end
 * up remembering how a row felt about the mouse an hour ago.
 *
 * <p>The easing is exponential, which is what makes it independent of the frame
 * rate: the value covers the same fraction of the remaining distance per
 * second, whether that second held sixty frames or six.
 *
 * <p><b>Nothing that is drawn is ever scaled by a matrix.</b> Everything on
 * screen here is either a rasterized texture - icons, circles, the corners of
 * every rounded rectangle - or text out of a glyph atlas, and this platform
 * samples both without smoothing. A matrix does not redraw a shape at a new
 * size, it resamples the picture it already has: bigger repeats rows of pixels,
 * smaller drops them, and half a pixel of travel slides the sampling off its own
 * grid. All three read as the thing going ragged while it moves, which is
 * exactly what a browser avoids by re-rendering vectors and text at their final
 * size. So growth goes through {@link #grownSize}, which asks for the shape
 * again at a new whole pixel size, a press goes through {@link #pressInset},
 * which moves edges and leaves the contents alone, and travel goes through
 * {@link #pushShift} or plain integer arithmetic. There is deliberately no
 * method here that scales.
 *
 * <p>The one scale that remains anywhere is the overlay scale itself, pushed
 * once around the whole window, and the raster cache is told about that one so
 * every shape is rasterized to match it.
 */
public final class Anim {

    /**
      * Longest step the easing may take. Without the cap a hitch in the game - a chunk load, a
      * screenshot - would hand the easing a delta big enough to snap every animation to its target
      * at once, and the interface would visibly jolt.
      */
    private static final float MAX_DELTA = 0.05f;

    /**
      * Speed of an ordinary hover fade, as a fraction of the remaining distance per second.
      *
      * <p>Slow enough that the eye follows the change rather than being handed the result. Faster
      * than this and a hover reads as a switch being thrown; much slower and the interface feels
      * like it is thinking about whether to answer.
      */
    public static final float HOVER = 11.0f;

    /** For anything that should feel immediate: a pressed state, a knob following the cursor. */
    public static final float FAST = 26.0f;

    /** For long travel, where the eye should be able to follow the movement itself. */
    public static final float SLOW = 9.0f;

    /** How long a list keeps staggering its rows in after it was filled. */
    private static final long ENTRY_MILLIS = 230L;

    /** Delay added per row of a staggered entry, so the list unrolls instead of appearing at once. */
    private static final long ENTRY_STEP_MILLIS = 22L;

    /** Rows past this one all start together; a long list should not take a minute to arrive. */
    private static final int ENTRY_MAX_STEPS = 14;

    private static final class Value {
        float current;
        long frame;
    }

    private static final Map<String, Value> VALUES = new HashMap<>();
    private static final Map<String, Long> CLICKS = new HashMap<>();

    private static long lastNanos;
    private static long frame;
    private static float delta;

    private Anim() {
    }

    public static boolean enabled() {
        return ConfigManager.client().animations;
    }

    /**
      * Opens a frame. Call once at the top of a root renderer, before anything asks for a value.
      * The app and the small overlay are never on screen at the same time, so both may call it.
      */
    public static void beginFrame() {
        long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
        }
        delta = Math.min(MAX_DELTA, (now - lastNanos) / 1_000_000_000.0f);
        lastNanos = now;
        frame++;
        if ((frame & 511L) == 0L) {
            sweep();
        }
    }

    /** Seconds since the previous frame, capped. */
    public static float delta() {
        return delta;
    }

    /**
      * Forgets values nothing has drawn for a while. Names are reused by position, so the map stays
      * small on its own - this only clears out the pages the user has left.
      */
    private static void sweep() {
        Iterator<Map.Entry<String, Value>> values = VALUES.entrySet().iterator();
        while (values.hasNext()) {
            if (frame - values.next().getValue().frame > 600L) {
                values.remove();
            }
        }
        long stale = System.nanoTime() - 3_000_000_000L;
        CLICKS.entrySet().removeIf(entry -> entry.getValue() < stale);
    }

    /**
      * Walks the value named {@code id} towards {@code target} and returns where it now stands.
      * A name that is new starts at its target, so an element that appears already hovered does not
      * fade in from nothing.
      */
    public static float to(String id, float target, float speed) {
        if (!enabled()) {
            return target;
        }
        Value value = VALUES.get(id);
        if (value == null) {
            value = new Value();
            value.current = target;
            VALUES.put(id, value);
        }
        value.frame = frame;
        value.current += (target - value.current) * (1.0f - (float) Math.exp(-speed * delta));
        if (Math.abs(target - value.current) < 0.0015f) {
            value.current = target;
        }
        return value.current;
    }

    /** 0 while the cursor is away, 1 while it is on the element, and the way between. */
    public static float hover(String id, boolean hovered) {
        return to(id, hovered ? 1.0f : 0.0f, HOVER);
    }

    /**
      * A colour that follows the hover of the same name, at full precision.
      *
      * <p>For TEXT only. Text is drawn glyph by glyph and never cached, so it can afford every step
      * of the fade. Handing this to a rounded rectangle or an icon would build a texture a frame;
      * {@link #hoverShade} is that one.
      */
    public static int hoverColor(String id, boolean hovered, int idle, int active) {
        return Theme.mix(idle, active, hover(id, hovered));
    }

    /** The same, snapped to steps, for a colour that is about to reach a cached drawing call. */
    public static int hoverShade(String id, boolean hovered, int idle, int active) {
        return mix(idle, active, hover(id, hovered));
    }

    // ------------------------------------------------------------------ the plop

    // Given room to be felt. The press is a whole pixel either way, so how it reads is entirely a
    // question of time: too short and it registers as a flicker rather than as the button giving.
    private static final float CLICK_DOWN = 0.075f;
    private static final float CLICK_RISE = 0.135f;
    private static final float CLICK_SETTLE = 0.215f;

    /** How far the element is squashed at the bottom of the press. */
    private static final float CLICK_SQUASH = 0.945f;

    /**
      * How far past its own size it springs back afterwards. That overshoot is the plop.
      *
      * <p>Deliberately small. The movement only has to be felt, not watched: at anything more the
      * interface starts bouncing at the user rather than answering them, and on something the size
      * of a list row a press that visibly rebounds looks like a fault.
      */
    private static final float CLICK_PEAK = 1.030f;

    /** Starts the press animation for a name. Call it where the click is acted on. */
    public static void click(String id) {
        if (enabled()) {
            CLICKS.put(id, System.nanoTime());
        }
    }

    /**
      * The scale factor of a running press, or 1 when there is none. Down hard, back up past the
      * original size, then settle - the shape of something being pressed and letting go.
      */
    public static float clickScale(String id) {
        Long start = CLICKS.get(id);
        if (start == null) {
            return 1.0f;
        }
        if (!enabled()) {
            CLICKS.remove(id);
            return 1.0f;
        }
        float t = Math.max(0.0f, (System.nanoTime() - start) / 1_000_000_000.0f);
        if (t < CLICK_DOWN) {
            return lerp(1.0f, CLICK_SQUASH, smooth(t / CLICK_DOWN));
        }
        t -= CLICK_DOWN;
        if (t < CLICK_RISE) {
            return lerp(CLICK_SQUASH, CLICK_PEAK, smooth(t / CLICK_RISE));
        }
        t -= CLICK_RISE;
        if (t < CLICK_SETTLE) {
            return lerp(CLICK_PEAK, 1.0f, smooth(t / CLICK_SETTLE));
        }
        CLICKS.remove(id);
        return 1.0f;
    }

    /** Whether a press is still running, for callers that want to skip the work. */
    public static boolean clicking(String id) {
        return CLICKS.containsKey(id);
    }

    // -------------------------------------------------------- movement in whole pixels

    /**
      * The whole pixel size to draw a growing icon or circle at.
      *
      * <p>This is how growth is done here, and a matrix is not. These shapes are rasterized into
      * textures and this platform samples textures without any smoothing, so a matrix that makes one
      * bigger does not draw it bigger - it repeats rows of pixels, and the shape visibly breaks into
      * steps. Asking for a bigger icon instead rasterizes the outline again at that size, the way a
      * browser re-renders a vector, and every frame is drawn properly.
      *
      * <p>The price is that the growth happens in whole pixels rather than continuously, because
      * whole pixels are what the texture cache is keyed by. Two or three crisp steps read better
      * than a smooth slide through a shape that is coming apart.
      */
    public static int grownSize(int size, float amount, int grow) {
        if (!enabled()) {
            return size;
        }
        return Math.max(1, size + Math.round(grow * Math.max(0.0f, Math.min(1.0f, amount))));
    }

    /**
      * Whole pixels an icon grows by, and gives up while pressed.
      *
      * <p>Two, and that is arithmetic rather than taste. An icon is rasterized to an even number of
      * pixels so its centre lands on a whole one, which makes a size of eleven and a size of twelve
      * the same picture. A step of one is therefore invisible on any icon whose resting size is
      * even and obvious on any whose size is odd - the interface would animate in some places and
      * quietly refuse in others, for no reason a user could ever work out.
      */
    public static final int ICON_STEP = 2;

    /** The press of an icon, in the steps an icon can actually take. See {@link #ICON_STEP}. */
    public static int iconPress(String id) {
        return pressInset(id, ICON_STEP);
    }

    /**
      * How many whole steps of a growth have been taken, from none to {@code steps}.
      *
      * <p>For a control made of more than one shape. A circle and the glyph sitting on it count in
      * different units - a circle is asked for a radius and rasterized at twice it, while an icon is
      * rounded up to an even size - so asking each of them separately how much bigger it should be
      * makes them cross their thresholds at different moments, and the button visibly comes apart
      * while it moves. Taking one number here and multiplying it by whatever each shape counts in
      * keeps them on the same frame.
      */
    public static int growSteps(float amount, int steps) {
        if (!enabled()) {
            return 0;
        }
        return Math.round(steps * Math.max(0.0f, Math.min(1.0f, amount)));
    }

    /** A press as one step, so each part of a compound button can take its own share of it. */
    public static int pressStep(String id) {
        return pressInset(id, 1);
    }

    /**
      * How far a pressed element draws inside its own edges, in whole pixels. Negative while it
      * rebounds, which is the moment it stands a pixel proud of where it rests.
      *
      * <p>The press used to be a matrix that squashed and sprang back, which had the same problem as
      * a grown icon and one of its own: it scaled the text inside a row as well, and text pulled
      * through a matrix at a fraction of its size comes out soft. Moving the edges by whole pixels
      * instead leaves everything inside untouched and perfectly sharp.
      */
    public static int pressInset(String id, int depth) {
        float scale = clickScale(id);
        if (scale == 1.0f) {
            return 0;
        }
        float span = scale < 1.0f ? 1.0f - CLICK_SQUASH : CLICK_PEAK - 1.0f;
        return Math.round(depth * (1.0f - scale) / span);
    }

    /**
      * Moves what is drawn next, without scaling it.
      *
      * <p>Do not open a scissor rectangle while this is active. {@link Theme#scissor} works in the
      * coordinates the overlay was laid out in and knows nothing about a translate pushed on top, so
      * the clip would land somewhere other than the drawing.
      *
      * <p>Rounded to whole pixels on purpose. Half a pixel of travel is not a shape drawn half a
      * pixel further along - it is the same texture sampled off its own grid, which softens edges
      * and shimmers as it moves. A slide in whole pixels stays sharp the whole way.
      */
    public static void pushShift(GuiGraphics graphics, float dx, float dy) {
        Platform.pushScale(graphics, Math.round(dx), Math.round(dy), 1.0f);
    }

    public static void pop(GuiGraphics graphics) {
        Platform.popMatrix(graphics);
    }

    // ------------------------------------------------------------- waveforms

    /** A 0 to 1 and back again wave off the wall clock, for anything that breathes on its own. */
    public static float pulse(float seconds) {
        if (!enabled()) {
            return 0.0f;
        }
        return (1.0f - (float) Math.cos(sweep(seconds) * Math.PI * 2.0)) * 0.5f;
    }

    /** A 0 to 1 ramp that restarts, for anything that travels in one direction. */
    public static float sweep(float seconds) {
        if (!enabled() || seconds <= 0.0f) {
            return 0.0f;
        }
        long period = (long) (seconds * 1000.0f);
        return (System.currentTimeMillis() % period) / (float) period;
    }

    /** The rise and fall of one of {@code count} loading dots, offset against its neighbours. */
    public static float bounce(int index, int count, float seconds) {
        if (!enabled()) {
            return 0.0f;
        }
        float phase = sweep(seconds) - (float) index / Math.max(1, count) * 0.5f;
        if (phase < 0.0f) {
            phase += 1.0f;
        }
        // Only the first part of the cycle moves; the rest is the rest between hops.
        if (phase > 0.5f) {
            return 0.0f;
        }
        return (float) Math.sin(phase * Math.PI * 2.0);
    }

    // ------------------------------------------------------------ things that wait

    private static final int DOT_SIZE = 3;
    private static final int DOT_GAP = 3;
    private static final int DOT_COUNT = 3;

    /** Width of the three dots, so a caller can centre them. */
    public static int dotsWidth() {
        return DOT_COUNT * DOT_SIZE + (DOT_COUNT - 1) * DOT_GAP;
    }

    /**
      * Three dots hopping in turn, drawn from {@code x} with {@code cy} as their resting line. The
      * ordinary sign that something is on its way.
      */
    public static void dots(GuiGraphics graphics, int x, int cy, int color) {
        for (int i = 0; i < DOT_COUNT; i++) {
            float lift = bounce(i, DOT_COUNT, 0.9f);
            int dotX = x + i * (DOT_SIZE + DOT_GAP);
            int dotY = cy - DOT_SIZE / 2 - Math.round(lift * 3.0f);
            Theme.rounded(graphics, dotX, dotY, DOT_SIZE, DOT_SIZE, 1,
                    Theme.alpha(color, 0.45f + 0.55f * lift));
        }
    }

    /**
      * A placeholder block that breathes, for the space a row will take once it has arrived.
      * {@code index} staggers neighbouring blocks so the list ripples instead of blinking.
      */
    public static void skeleton(GuiGraphics graphics, int x, int y, int width, int height, int radius, int index) {
        float phase = pulse(1.4f) - index * 0.12f;
        if (phase < 0.0f) {
            phase += 1.0f;
        }
        // Through mix, not Theme.mix: a rounded corner is rasterized once per colour and kept under
        // it, and a breath that passed through every shade would build a texture a frame. Snapped,
        // the whole animation costs nine.
        Theme.rounded(graphics, x, y, width, height, radius,
                mix(Theme.ELEVATED, Theme.SURFACE_HOVER, Math.min(1.0f, phase)));
    }

    // --------------------------------------------------------------- entrances

    /**
      * How far item {@code index} has come in, for a list that was filled at {@code startMillis}.
      * Returns 1 once it has arrived, so a caller can draw normally without checking first.
      */
    public static float entry(long startMillis, int index) {
        if (!enabled() || startMillis <= 0L) {
            return 1.0f;
        }
        long elapsed = System.currentTimeMillis() - startMillis
                - Math.min(index, ENTRY_MAX_STEPS) * ENTRY_STEP_MILLIS;
        if (elapsed <= 0L) {
            return 0.0f;
        }
        if (elapsed >= ENTRY_MILLIS) {
            return 1.0f;
        }
        return outCubic(elapsed / (float) ENTRY_MILLIS);
    }

    /** Whether any item of that list is still on its way, so the caller knows to keep redrawing. */
    public static boolean entering(long startMillis, int count) {
        return enabled() && startMillis > 0L && entry(startMillis, count) < 1.0f;
    }

    // -------------------------------------------------- values headed for a cached texture

    /**
      * Steps a fade is snapped to before it is allowed to reach a rounded rectangle or an icon.
      *
      * <p>Both are rasterized once per colour and kept in a cache keyed by that colour, so a value
      * that moved every frame would build a new texture every frame and push everything else out.
      * Eight steps across a fade of a fifth of a second are below what the eye picks up, and the
      * ends are exact: a value of 0 or 1 comes back as 0 or 1, so a colour that is not moving is
      * still the colour it always was.
      */
    public static float stepped(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return Math.round(clamped * CACHE_STEPS) / (float) CACHE_STEPS;
    }

    private static final int CACHE_STEPS = 8;

    /** {@link Theme#mix} for a value that is moving. Use this everywhere a shape is being tinted. */
    public static int mix(int from, int to, float t) {
        return Theme.mix(from, to, stepped(t));
    }

    /** {@link Theme#alpha} for a value that is moving. */
    public static int fade(int color, float t) {
        return Theme.alpha(color, stepped(t));
    }

    /**
      * Fades a colour meant for a line of text.
      *
      * <p>Two reasons this is not {@link #fade}. Text is drawn glyph by glyph and never cached, so
      * it can keep every step of the fade. And the font renderer reads an alpha below four as no
      * alpha having been given at all and draws the line fully opaque, so a string on its way out
      * has to stop just short of nothing rather than reach it - which at five percent is nothing
      * anyone can see anyway.
      */
    public static int textFade(int color, float t) {
        return Theme.alpha(color, Math.max(0.05f, Math.min(1.0f, t)));
    }

    /**
      * Drops every value whose name starts with {@code prefix}. Needed when names stop meaning what
      * they meant: they are per position, so a list that reorders itself hands position five to a
      * different row, and whatever position five was in the middle of doing no longer applies.
      */
    public static void forget(String prefix) {
        VALUES.keySet().removeIf(name -> name.startsWith(prefix));
        CLICKS.keySet().removeIf(name -> name.startsWith(prefix));
    }

    // ----------------------------------------------------------------- easing

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** Slow at both ends. The default for anything that starts and stops. */
    public static float smooth(float t) {
        float x = Math.max(0.0f, Math.min(1.0f, t));
        return x * x * (3.0f - 2.0f * x);
    }

    /** Fast away, gentle arrival. The default for something entering. */
    public static float outCubic(float t) {
        float x = 1.0f - Math.max(0.0f, Math.min(1.0f, t));
        return 1.0f - x * x * x;
    }
}
