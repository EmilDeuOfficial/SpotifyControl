package de.emilo.spotifycontrol.lyrics;

import java.util.Locale;

/**
 * How a word of the lyrics comes and goes. Every animation fades on top of what it does itself;
 * the fade is the same for all of them and lives in {@link WorldLyrics}.
 *
 * <p>The movement of an animation is described once, here, as a {@link Pose}, and both the world
 * lyrics and the preview in the settings read it, so what the preview shows is what the world does.
 * Distances are measured in heights of the text, so the same pose fits a word of any size.</p>
 */
public enum LyricsAnimation {

    FADE("Fade"),
    GROW("Grow"),
    ZOOM("Zoom"),
    POP("Pop"),
    ELASTIC("Elastic"),
    RISE("Rise"),
    DROP("Drop"),
    BOUNCE("Bounce"),
    SLIDE("Slide"),
    SWOOP("Swoop"),
    ROLL("Roll"),
    SPIN("Spin"),
    FLIP("Flip"),
    SWING("Swing"),
    SHAKE("Shake"),
    WAVE("Wave"),
    ZIGZAG("Zigzag");

    /**
     * Where a word is while it moves.
     *
     * @param scale size factor around the middle of the word, 1 is the normal size
     * @param lift  height above its place, in text heights
     * @param slide distance to the right of its place, in text heights
     * @param spin  turn in its own plane, in radians
     * @param flip  turn around its vertical axis, in radians, so 90 degrees shows it edge on
     */
    public record Pose(float scale, float lift, float slide, float spin, float flip) {
        public static final Pose REST = new Pose(1.0f, 0.0f, 0.0f, 0.0f, 0.0f);

        static Pose scaled(float scale) {
            return new Pose(Math.max(0.05f, scale), 0.0f, 0.0f, 0.0f, 0.0f);
        }

        static Pose lifted(float lift) {
            return new Pose(1.0f, lift, 0.0f, 0.0f, 0.0f);
        }

        static Pose slid(float slide) {
            return new Pose(1.0f, 0.0f, slide, 0.0f, 0.0f);
        }
    }

    private final String label;

    LyricsAnimation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The animation of that name, or FADE for a name this version does not know. */
    public static LyricsAnimation of(String name) {
        if (name != null) {
            String wanted = name.trim().toUpperCase(Locale.ROOT);
            for (LyricsAnimation animation : values()) {
                if (animation.name().equals(wanted)) {
                    return animation;
                }
            }
        }
        return FADE;
    }

    /**
     * The pose for a word that is {@code hidden} of the way to being gone: 0 when it is fully
     * there, 1 when it is not there at all. The value is expected to be eased already.
     *
     * @param out true while the word disappears, false while it appears; the movements run in
     *            opposite directions so a word leaves the way it did not come
     */
    public Pose pose(boolean out, float hidden) {
        float h = Math.max(0.0f, Math.min(1.0f, hidden));
        float shown = 1.0f - h;
        float way = out ? 1.0f : -1.0f;
        return switch (this) {
            case FADE -> Pose.REST;
            case GROW -> Pose.scaled(1.0f - 0.5f * h);
            case ZOOM -> Pose.scaled(1.0f + 1.2f * h);
            case POP -> Pose.scaled(out ? 1.0f - h : overshoot(shown));
            case ELASTIC -> Pose.scaled(out ? 1.0f - 0.8f * h : elastic(shown));
            case RISE -> Pose.lifted(way * h);
            case DROP -> Pose.lifted(-way * h);
            case BOUNCE -> Pose.lifted(out ? -1.2f * h : 1.6f * (1.0f - bounce(shown)));
            case SLIDE -> Pose.slid(way * 2.0f * h);
            case SWOOP -> new Pose(1.0f, -way * h, way * 2.5f * h, 0.0f, 0.0f);
            case ROLL -> new Pose(1.0f, 0.0f, way * 2.5f * h, (float) (-way * Math.PI * 2.0 * h), 0.0f);
            case SPIN -> new Pose(Math.max(0.05f, 1.0f - 0.6f * h), 0.0f, 0.0f,
                    (float) (-way * Math.PI * 2.0 * h), 0.0f);
            case FLIP -> new Pose(1.0f, 0.0f, 0.0f, 0.0f, (float) (-way * Math.PI * 0.5 * h));
            case SWING -> new Pose(1.0f, 0.0f, 0.0f, (float) (Math.sin(h * 10.0) * 0.5 * h), 0.0f);
            case SHAKE -> Pose.slid((float) (Math.sin(h * 40.0) * 0.25 * h));
            case WAVE -> Pose.lifted((float) (Math.sin(h * 9.0) * 0.45 * h));
            case ZIGZAG -> new Pose(1.0f, -way * 0.6f * h, (float) (Math.sin(h * 8.0) * 1.2 * h), 0.0f, 0.0f);
        };
    }

    /** Smooth start and end, the curve every fade and movement follows. */
    public static float ease(float t) {
        float clamped = Math.max(0.0f, Math.min(1.0f, t));
        return clamped * clamped * (3.0f - 2.0f * clamped);
    }

    /** Overshoots the end value a little before settling, which is what makes POP bounce. */
    private static float overshoot(float x) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float t = x - 1.0f;
        return 1.0f + c3 * t * t * t + c1 * t * t;
    }

    /** Swings around the end value and settles, the springy growth of ELASTIC. */
    private static float elastic(float x) {
        if (x <= 0.0f) {
            return 0.0f;
        }
        if (x >= 1.0f) {
            return 1.0f;
        }
        return (float) (Math.pow(2.0, -10.0 * x) * Math.sin((x * 10.0 - 0.75) * (2.0 * Math.PI / 3.0)) + 1.0);
    }

    /** A ball dropped on the floor: reaches the end value, bounces back up a little and settles. */
    private static float bounce(float x) {
        float n = 7.5625f;
        float d = 2.75f;
        if (x < 1.0f / d) {
            return n * x * x;
        }
        if (x < 2.0f / d) {
            float t = x - 1.5f / d;
            return n * t * t + 0.75f;
        }
        if (x < 2.5f / d) {
            float t = x - 2.25f / d;
            return n * t * t + 0.9375f;
        }
        float t = x - 2.625f / d;
        return n * t * t + 0.984375f;
    }
}
