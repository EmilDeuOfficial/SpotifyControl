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
    POP("Pop"),
    RISE("Rise"),
    DROP("Drop"),
    SLIDE("Slide"),
    SPIN("Spin"),
    FLIP("Flip");

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
        return switch (this) {
            case FADE -> Pose.REST;
            case GROW -> new Pose(1.0f - 0.5f * h, 0.0f, 0.0f, 0.0f, 0.0f);
            case POP -> new Pose(out ? Math.max(0.05f, 1.0f - h) : Math.max(0.05f, overshoot(1.0f - h)),
                    0.0f, 0.0f, 0.0f, 0.0f);
            case RISE -> new Pose(1.0f, out ? h : -h, 0.0f, 0.0f, 0.0f);
            case DROP -> new Pose(1.0f, out ? -h : h, 0.0f, 0.0f, 0.0f);
            case SLIDE -> new Pose(1.0f, 0.0f, out ? 2.0f * h : -2.0f * h, 0.0f, 0.0f);
            case SPIN -> new Pose(1.0f - 0.6f * h, 0.0f, 0.0f,
                    (out ? -1.0f : 1.0f) * (float) (Math.PI * 2.0 * h), 0.0f);
            case FLIP -> new Pose(1.0f, 0.0f, 0.0f, 0.0f,
                    (out ? -1.0f : 1.0f) * (float) (Math.PI * 0.5 * h));
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
}
