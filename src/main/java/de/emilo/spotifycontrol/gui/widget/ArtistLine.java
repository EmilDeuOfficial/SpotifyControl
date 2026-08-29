package de.emilo.spotifycontrol.gui.widget;

import de.emilo.spotifycontrol.gui.Anim;
import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.spotify.model.Track;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The artist line of a track, laid out name by name so that each name can be aimed at.
 *
 * <p>Drawn as one string there is nothing to aim at: a track by three people has three pages behind
 * it, and only the name under the cursor should open one.
 *
 * <p>It lives in its own class because two places show that line - the rows of every listing and the
 * player bar along the bottom - and both have to agree to the pixel about where each name sits.
 * Every time a piece of this interface has worked out the same geometry twice, the two copies have
 * drifted and the thing under the cursor stopped being the thing a click reached.
 */
public final class ArtistLine {

    /** Height of the box a name answers to, a little taller than the line of text itself. */
    public static final int HIT_HEIGHT = 10;

    /** One artist name and where it sits, so the same box can be drawn and clicked. */
    public record Span(int credit, String id, String name, int x, int width) {
    }

    private ArtistLine() {
    }

    /**
      * Where each name falls. Names that no longer fit are left out, and {@link #render} prints an
      * ellipsis in their place rather than cutting one in half.
      */
    public static List<Span> spans(Font font, Track track, int textX, int textWidth) {
        List<Track.Credit> credits = track.artists();
        if (credits.isEmpty() || textWidth <= 0) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>(credits.size());
        int cursor = textX;
        int limit = textX + textWidth;
        int separator = font.width(Track.ARTIST_SEPARATOR);
        for (int i = 0; i < credits.size(); i++) {
            Track.Credit credit = credits.get(i);
            int nameWidth = font.width(credit.name());
            int start = i == 0 ? cursor : cursor + separator;
            if (start + nameWidth > limit) {
                break;
            }
            spans.add(new Span(i, credit.id(), credit.name(), start, nameWidth));
            cursor = start + nameWidth;
        }
        return spans;
    }

    /**
      * The name under the cursor, or null. {@code hitY} is where the line comes to rest, which is not
      * always where it is drawn: a row on its way in is still travelling, and a name must not answer
      * to the cursor somewhere other than where it will settle.
      */
    public static Span hit(Font font, Track track, int textX, int textWidth, double mouseX, double mouseY,
                           int hitY) {
        for (Span span : spans(font, track, textX, textWidth)) {
            if (!span.id().isEmpty()
                    && Theme.hovered((int) mouseX, (int) mouseY, span.x(), hitY, span.width(), HIT_HEIGHT)) {
                return span;
            }
        }
        return null;
    }

    /**
      * Draws the line. {@code namePrefix} is what the hover of each name animates under, so two
      * places showing the same track do not share one another's highlight; pass something that
      * identifies the line, such as {@code "row.artist:4:"}.
      *
      * <p>{@code interactive} is the caller's own rule about whether this line may be aimed at right
      * now - a dimmed row answers to nothing, and a name that lit up there would promise a page that
      * the click then refuses to open.
      */
    public static void render(GuiGraphics graphics, Font font, Track track, String namePrefix, int textX,
                              int textY, int textWidth, int baseColor, int mouseX, int mouseY, int hitY,
                              boolean interactive) {
        List<Span> spans = spans(font, track, textX, textWidth);
        if (spans.isEmpty()) {
            // Either the track carries no artist ids at all, or not even the first name fits. Both
            // fall back to the plain line that was drawn here before any of this existed.
            Platform.drawString(graphics, font, Theme.fit(font, track.artist(), textWidth), textX, textY,
                    baseColor, false);
            return;
        }
        int separator = font.width(Track.ARTIST_SEPARATOR);
        int end = textX;
        for (Span span : spans) {
            if (span.credit() > 0) {
                Platform.drawString(graphics, font, Track.ARTIST_SEPARATOR, span.x() - separator, textY,
                        baseColor, false);
            }
            boolean over = interactive && !span.id().isEmpty()
                    && Theme.hovered(mouseX, mouseY, span.x(), hitY, span.width(), HIT_HEIGHT);
            float lit = Anim.hover(namePrefix + span.credit(), over);
            int color = span.id().isEmpty() ? baseColor : Theme.mix(baseColor, Theme.TEXT, lit);
            Platform.drawString(graphics, font, span.name(), span.x(), textY, color, false);
            if (lit > 0.0f) {
                // The underline grows out of the start of the name, which is the usual sign that a
                // piece of text leads somewhere and is not merely a brighter word.
                graphics.fill(span.x(), textY + 9, span.x() + Math.round(span.width() * lit), textY + 10,
                        Theme.alpha(Theme.TEXT, lit));
            }
            end = span.x() + span.width();
        }
        if (spans.size() < track.artists().size()) {
            Platform.drawString(graphics, font, "...", end, textY, baseColor, false);
        }
    }
}
