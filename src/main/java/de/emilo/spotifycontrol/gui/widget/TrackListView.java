package de.emilo.spotifycontrol.gui.widget;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.gui.Anim;
import de.emilo.spotifycontrol.gui.Icons;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.spotify.model.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrackListView {

    public static final int ROW_HEIGHT = 30;

    /** How many rows before the end of the loaded range the next page is requested. */
    private static final int PREFETCH_ROWS = 8;

    /** How far below its place a row starts when the list arrives. */
    private static final int ENTRY_RISE = 8;

    /** Seconds for one breath of the row that is playing. Slow enough to be found, not watched. */
    private static final float PLAYING_BREATH = 2.6f;

    /**
      * Title widths of the placeholder rows, in pixels rather than as a share of the list.
      *
      * <p>A share of the width was wrong: on a wide window it drew title bars running most of the
      * way across the page, which no song title does, and the wait looked nothing like the list it
      * was standing in for. Real titles are a few dozen to a couple of hundred pixels whatever the
      * window is doing, so these are too, clamped to whatever room there happens to be.
      */
    private static final int[] SKELETON_TITLES = {104, 68, 142, 86, 120, 62, 96, 132, 74, 110};

    /** The same for the artist line, which is always the shorter of the two. */
    private static final int[] SKELETON_ARTISTS = {58, 44, 76, 50, 66, 40, 54, 70, 46, 62};

    /**
      * How far the cursor has to travel before a press counts as a drag. Without it every ordinary
      * click would end as a one pixel reorder, because a mouse never presses perfectly still.
      */
    private static final int DRAG_THRESHOLD = 4;

    /**
      * How far a drag has to travel before a listing that cannot be rearranged says so. Well past
      * DRAG_THRESHOLD, because a hand that shifts a few pixels while clicking is not asking a
      * question and should not be answered with a message.
      */
    private static final int REFUSE_DISTANCE = 12;

    /** How close to the top or bottom edge a drag starts pulling the list along with it. */
    private static final int DRAG_EDGE = 18;

    /** Pixels per second the list travels while a drag rests against an edge. */
    private static final float DRAG_SCROLL_SPEED = 260.0f;

    public interface Handler {
        void play(int index, Track track);

        void toggleLike(Track track);

        void context(Track track, double mouseX, double mouseY);

        /** Opens the page of one artist of a track. */
        void openArtist(String artistId, String name);

        /**
          * Whether this listing may be rearranged. Only a playlist the user owns can be, and only
          * while every row still sits where Spotify has it.
          */
        boolean reorderable();

        /** Moves the row at {@code from} to {@code to}, both indices into the loaded list. */
        void reorder(int from, int to);

        /** A drag started on a listing that cannot be rearranged, so it can be explained. */
        void reorderRefused();
    }

    private final ScrollArea scroll = new ScrollArea();
    private final List<Track> tracks = new ArrayList<>();
    private String currentUri = "";

    private boolean moreAvailable;
    private boolean loadingMore;
    private Runnable loadMore = () -> {
    };

    /** When the contents last arrived, for the staggered entrance. Zero means nothing to play. */
    private long entryStartMillis;

    /** How long the listing is going to be, so the wait can be the right length. 0 when unknown. */
    private int expectedCount;

    // Dragging a row is decided across three events. mouseClicked only remembers which row went
    // down, mouseDragged decides whether the movement is a drag, and mouseReleased either plays the
    // row or moves it. Playing on the press would start the song the moment a drag began.
    private int pressIndex = -1;
    private double pressMouseY;
    private boolean dragging;

    /** Where the held row would land. Equal to pressIndex until the cursor has passed a row. */
    private int dropIndex = -1;

    /** Cursor height while a drag runs, so the lifted row can follow it. */
    private int dragMouseY;

    /** Whether this press has already been told the list cannot be rearranged. */
    private boolean refused;

    /** Geometry of the last render, so a drag knows the list it is inside of. */
    private int viewY;
    private int viewHeight;

    public ScrollArea scroll() {
        return scroll;
    }

    /** Replaces the contents and scrolls back to the top. */
    public void setTracks(List<Track> replacement) {
        tracks.clear();
        if (replacement != null) {
            tracks.addAll(replacement);
        }
        // An empty replacement opens a paged listing, it is not a list arriving. Stamping it here
        // would let the entrance run out while the first request is still in flight, and the rows
        // would then appear without it.
        entryStartMillis = tracks.isEmpty() ? 0L : System.currentTimeMillis();
        moreAvailable = false;
        loadingMore = false;
        cancelDrag();
        // Position five now belongs to a different song, so whatever position five was in the
        // middle of doing has nothing to do with what is drawn there next. Every name a row owns
        // starts with "row", and all of them are keyed by position: a hover left standing would
        // open the next playlist with a row already lit under a cursor that is not on it.
        Anim.forget("row");
        scroll.reset();
        scroll.setContentHeight(contentHeight());
    }

    /** Adds a freshly loaded page, keeping the current scroll position. */
    public void appendTracks(List<Track> page) {
        if (page != null && !page.isEmpty()) {
            // Only the page that fills an empty list counts as the list arriving. Later pages are
            // appended below rows the user is already reading, and restarting the stagger would
            // make those slide a second time under a cursor that never moved.
            if (tracks.isEmpty()) {
                entryStartMillis = System.currentTimeMillis();
            }
            tracks.addAll(page);
        }
        scroll.setContentHeight(contentHeight());
    }

    /** What the listing says its length is, which the placeholder rows are counted against. */
    public void setExpectedCount(int expectedCount) {
        this.expectedCount = Math.max(0, expectedCount);
    }

    public void setMoreAvailable(boolean moreAvailable) {
        this.moreAvailable = moreAvailable;
        scroll.setContentHeight(contentHeight());
    }

    public void setLoadingMore(boolean loadingMore) {
        this.loadingMore = loadingMore;
    }

    public void setLoadMore(Runnable loadMore) {
        this.loadMore = loadMore == null ? () -> {
        } : loadMore;
    }

    public int loadedCount() {
        return tracks.size();
    }

    public List<Track> tracks() {
        return Collections.unmodifiableList(tracks);
    }

    /**
      * Drops an entry without disturbing the scroll position or the paging state, and reports how
      * many rows went. Spotify removes every occurrence of a uri from a playlist, so a track listed
      * twice takes two rows with it, and the caller's count of what it has loaded has to follow.
      */
    public int removeTrack(String uri) {
        if (uri == null || uri.isEmpty()) {
            return 0;
        }
        int before = tracks.size();
        if (tracks.removeIf(track -> uri.equals(track.uri()))) {
            cancelDrag();
            Anim.forget("row.slot:");
            scroll.setContentHeight(contentHeight());
        }
        return before - tracks.size();
    }

    public void setCurrentUri(String uri) {
        this.currentUri = uri == null ? "" : uri;
    }

    private int contentHeight() {
        return tracks.size() * ROW_HEIGHT + (moreAvailable ? ROW_HEIGHT : 0);
    }

    /** Kept as a local name because it is used a dozen times below. See {@link Anim#stepped}. */
    private static float stepped(float value) {
        return Anim.stepped(value);
    }

    /**
      * A colour of a row that has not fully arrived, pulled back towards the panel it sits on. Text
      * drawn at a low alpha turns muddy against this background, while mixing towards the surface
      * gives the same fade and stays clean.
      */
    private static int arriving(int color, float entry) {
        return entry >= 1.0f ? color : Theme.mix(Theme.SURFACE, color, entry);
    }

    // The row layout is needed twice, once to draw it and once to work out what the cursor is on.
    // Every place where the two drifted apart has cost a bug, so both sides read it from here.

    /**
      * The gutter in front of a row, which is only there to hold the track number. It was wide
      * enough for a number twice the length any playlist reaches, and the whole row read as though
      * it had come loose from the panel edge it stands on.
      */
    private static final int NUMBER_X = 10;

    private static int artX(int x) {
        return x + 20;
    }

    private static int artSize() {
        return ROW_HEIGHT - 8;
    }

    private static int textX(int x) {
        return artX(x) + artSize() + 8;
    }

    private static int likeX(Font font, Track track, int x, int width) {
        return x + width - 12 - font.width(track.formattedDuration()) - 44;
    }

    private static int textWidth(Font font, Track track, int x, int width) {
        return likeX(font, track, x, width) - textX(x) - 12;
    }

    public void render(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        viewY = y;
        viewHeight = height;
        if (dragging) {
            dragEdgeScroll();
        }
        scroll.setViewportHeight(height);
        scroll.setContentHeight(contentHeight());

        Theme.scissor(graphics, x, y, x + width, y + height);
        int offset = scroll.offset();
        int first = Math.max(0, offset / ROW_HEIGHT);
        int last = Math.min(tracks.size(), (offset + height) / ROW_HEIGHT + 1);
        // A drag pushes rows a whole row out of their usual range, so both ends reach one further or
        // the row that just stepped aside would be missing from the screen.
        if (dragging) {
            first = Math.max(0, first - 1);
            last = Math.min(tracks.size(), last + 1);
        }

        for (int index = first; index < last; index++) {
            if (dragging && index == pressIndex) {
                // Drawn afterwards so it passes over its neighbours rather than under them.
                continue;
            }
            Track track = tracks.get(index);
            int rowY = y + Math.round(slot(index) * ROW_HEIGHT) - offset;
            boolean hovered = !dragging
                    && Theme.hovered(mouseX, mouseY, x, rowY, width, ROW_HEIGHT)
                    && mouseY >= y && mouseY < y + height
                    && !scroll.claims(mouseX, mouseY);
            boolean playing = !currentUri.isEmpty() && currentUri.equals(track.uri());
            // Staggered by the place on screen, not by the place in the list: scrolling a long
            // playlist to row four hundred should not wait out four hundred steps of the entrance.
            float entry = Anim.entry(entryStartMillis, index - first);
            if (entry > 0.0f) {
                renderRow(graphics, font, track, index, x, rowY, width, hovered, playing, mouseX, mouseY, entry,
                        false);
            }
        }

        if (dragging && pressIndex >= 0 && pressIndex < tracks.size()) {
            renderHeldRow(graphics, font, x, y, width, height, mouseX, mouseY);
        }

        if (tracks.isEmpty() && (moreAvailable || loadingMore) && Anim.enabled()) {
            renderSkeletonRows(graphics, x, y, width, height);
        } else if (moreAvailable) {
            renderLoadingRow(graphics, font, x, y + tracks.size() * ROW_HEIGHT - offset, width);
        }
        Theme.endScissor(graphics);
        scroll.renderScrollbar(graphics, x + width - 4, y, height);

        if (moreAvailable && !loadingMore && last >= tracks.size() - PREFETCH_ROWS) {
            loadingMore = true;
            loadMore.run();
        }
    }

    /**
      * Where row {@code index} is drawn, counted in rows. While a drag runs, the rows between where
      * the held row came from and where it is headed step aside by one, and that gap is what tells
      * the user where it will land. Eased, so the gap opens and closes instead of jumping.
      */
    private float slot(int index) {
        int target = index;
        if (dragging && pressIndex >= 0 && dropIndex >= 0) {
            if (pressIndex < dropIndex && index > pressIndex && index <= dropIndex) {
                target = index - 1;
            } else if (dropIndex < pressIndex && index >= dropIndex && index < pressIndex) {
                target = index + 1;
            }
        }
        return Anim.to("row.slot:" + index, target, Anim.FAST);
    }

    /** A track without an id cannot be saved; local files are the usual case. */
    private static boolean likeable(Track track) {
        return !track.id().isEmpty();
    }

    private void renderLoadingRow(GuiGraphics graphics, Font font, int x, int y, int width) {
        String label = Lang.plain("gui.loading_more");
        if (!Anim.enabled()) {
            int textX = x + (width - font.width(label)) / 2;
            Platform.drawString(graphics, font, label, textX, y + ROW_HEIGHT / 2 - 4, Theme.TEXT_DIM, false);
            return;
        }
        // Every translation of this line ends in a written out ellipsis. Three dots that actually
        // hop say the same thing better, so the printed ones step aside instead of standing next to
        // them.
        String text = label.endsWith("...") ? label.substring(0, label.length() - 3).trim() : label;
        int labelWidth = font.width(text);
        int textX = x + (width - labelWidth - 6 - Anim.dotsWidth()) / 2;
        Platform.drawString(graphics, font, text, textX, y + ROW_HEIGHT / 2 - 4, Theme.TEXT_DIM, false);
        Anim.dots(graphics, textX + labelWidth + 6, y + ROW_HEIGHT / 2, Theme.TEXT_DIM);
    }

    /**
      * The shape of the rows that have not arrived yet. An empty panel says nothing about how long
      * the wait will be, while a list that is already laid out reads as one being filled.
      */
    private void renderSkeletonRows(GuiGraphics graphics, int x, int y, int width, int height) {
        // As many as the page will hold, but never more than the listing is going to have. Opening a
        // playlist of three and being shown a screenful of waiting rows says the wrong thing about
        // what is coming.
        int fits = Math.max(1, (height + ROW_HEIGHT - 1) / ROW_HEIGHT);
        int rows = expectedCount > 0 ? Math.min(expectedCount, fits) : fits;
        int artSize = artSize();
        int textX = textX(x);
        // Measured the way a real row measures it, so the bars end where a title would rather than
        // where the panel does.
        int available = Math.max(40, x + width - 12 - 44 - 12 - textX);
        for (int index = 0; index < rows; index++) {
            int rowY = y + index * ROW_HEIGHT;
            // The same three shapes a real row has, in the same places: the cover with the corners
            // it will have, the title on the line the title sits on, the artist under it.
            Anim.skeleton(graphics, artX(x), rowY + 4, artSize, artSize, 3, index);
            Anim.skeleton(graphics, textX, rowY + 7,
                    Math.min(available, SKELETON_TITLES[index % SKELETON_TITLES.length]), 6, 2, index);
            Anim.skeleton(graphics, textX, rowY + 18,
                    Math.min(available, SKELETON_ARTISTS[index % SKELETON_ARTISTS.length]), 5, 2, index + 1);
        }
    }

    /**
      * The row the cursor is carrying. It gets a raised plate of its own, so it reads as being held
      * above the list rather than as one more row in the column.
      */
    private void renderHeldRow(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                               int mouseX, int mouseY) {
        Track track = tracks.get(pressIndex);
        int rowY = Math.max(y, Math.min(y + height - ROW_HEIGHT, dragMouseY - ROW_HEIGHT / 2));
        Theme.rounded(graphics, x, rowY, width - 6, ROW_HEIGHT, 4, Theme.ELEVATED);
        boolean playing = !currentUri.isEmpty() && currentUri.equals(track.uri());
        renderRow(graphics, font, track, pressIndex, x, rowY, width, false, playing, mouseX, mouseY, 1.0f, true);
    }

    private void renderRow(GuiGraphics graphics, Font font, Track track, int index, int x, int y, int width,
                           boolean hovered, boolean playing, int mouseX, int mouseY, float entry, boolean held) {
        boolean unavailable = !track.playable() && ConfigManager.client().dimUnavailableTracks;
        boolean active = hovered && !unavailable;
        float hover = Anim.hover("row:" + index, active);
        float rasterEntry = stepped(entry);

        // Only the drawing rises into place. Every hit area below still measures from y, so a row
        // cannot slide out from under a cursor that has not moved and flicker on its own edge.
        int top = y + Math.round((1.0f - entry) * ENTRY_RISE);
        int centerY = top + ROW_HEIGHT / 2;

        // The press is the plate drawing inside its own edges, and nothing in the row moves with it.
        // The title, the cover and the icons are all resampled images here, so anything that shifted
        // them by a fraction of a pixel would soften every one of them for as long as the plop lasts.
        int press = held ? 0 : Anim.pressInset("row:" + index, 1);
        float plate = held ? 0.0f : stepped(hover * entry);
        if (plate > 0.0f) {
            Theme.rounded(graphics, x + press, top + press, width - 6 - press * 2, ROW_HEIGHT - press * 2, 4,
                    Theme.alpha(Theme.SURFACE_HOVER, plate));
        }

        int textColor = arriving(unavailable ? Theme.TEXT_DIM
                : playing ? Theme.mix(Theme.GREEN, Theme.GREEN_BRIGHT, Anim.pulse(PLAYING_BREATH))
                : Theme.TEXT, entry);

        int numberX = x + NUMBER_X;
        float icon = stepped(hover);
        if (icon < 1.0f) {
            String number = String.valueOf(index + 1);
            Platform.drawString(graphics, font, number, numberX - font.width(number) / 2, centerY - 4,
                    Theme.alpha(arriving(playing ? Theme.GREEN : Theme.TEXT_DIM, entry), 1.0f - hover), false);
        }
        if (icon > 0.0f) {
            Icons.play(graphics, numberX, centerY, Anim.grownSize(8, hover, Anim.ICON_STEP),
                    Theme.alpha(Theme.TEXT, Math.min(icon, rasterEntry)));
        }

        int artX = artX(x);
        int artSize = artSize();
        int plateColor = held ? Theme.ELEVATED : Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, icon);
        Object art = ClientState.artwork().get(track.artworkUrl(), artSize);
        if (art != null) {
            Platform.drawTexture(graphics, art, artX, top + 4, artSize, artSize);
            if (rasterEntry < 1.0f) {
                // A cover is a texture and has no colour to fade, so the panel is laid over it and
                // lifts away instead. Without this the covers would arrive at full strength while
                // the words around them were still on their way up.
                Theme.rounded(graphics, artX, top + 4, artSize, artSize, 3,
                        Theme.alpha(Theme.SURFACE, 1.0f - rasterEntry));
            }
            Theme.roundedMask(graphics, artX, top + 4, artSize, artSize, 3, plateColor);
        } else {
            Theme.rounded(graphics, artX, top + 4, artSize, artSize, 3, arriving(Theme.ELEVATED, rasterEntry));
            Icons.note(graphics, artX + artSize / 2, top + 4 + artSize / 2, 12,
                    Theme.alpha(Theme.TEXT_DIM, rasterEntry));
        }

        int textX = textX(x);
        int rightEdge = x + width - 12;
        int durationWidth = font.width(track.formattedDuration());
        int likeX = likeX(font, track, x, width);
        int textWidth = textWidth(font, track, x, width);

        Platform.drawString(graphics, font, Theme.fit(font, track.title(), textWidth), textX, top + 6, textColor,
                false);
        // A dimmed row answers to nothing, and an artist name is part of that row. Lighting one up
        // under the cursor would promise a page that the same rule then refuses to open.
        ArtistLine.render(graphics, font, track, "row.artist:" + index + ":", textX, top + 17, textWidth,
                arriving(unavailable ? Theme.TEXT_DIM : Theme.TEXT_MUTED, entry), mouseX, mouseY, y + 17,
                hovered && !held && !unavailable);

        if (likeable(track)) {
            // 14 wide, not 16: at 16 the area ran two pixels into the one next to it and both
            // icons lit up at once.
            boolean likeHovered = !held && Theme.hovered(mouseX, mouseY, likeX - 8, y + 8, 14, 14);
            boolean liked = ClientState.session().isLiked(track.id());
            float likeHover = Anim.hover("row.like:" + index, likeHovered);
            int heartColor = liked ? Theme.GREEN
                    : Theme.mix(unavailable ? Theme.TEXT_DIM : Theme.TEXT_MUTED, Theme.GREEN_BRIGHT,
                    stepped(likeHover));
            int heartSize = Anim.grownSize(10, likeHover, Anim.ICON_STEP)
                    - Anim.iconPress("row.like:" + index);
            Icons.heart(graphics, likeX, centerY, heartSize, liked, Theme.alpha(heartColor, rasterEntry));
        }

        if (hover > 0.0f) {
            boolean menuHovered = active && Theme.hovered(mouseX, mouseY, likeX + 6, y + 8, 14, 14);
            float menuHover = Anim.hover("row.plus:" + index, menuHovered);
            int plusSize = Anim.grownSize(8, menuHover, Anim.ICON_STEP)
                    - Anim.iconPress("row.plus:" + index);
            Icons.plus(graphics, likeX + 13, centerY, plusSize,
                    Theme.alpha(Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, stepped(menuHover)),
                            Math.min(icon, rasterEntry)));
        }

        Platform.drawString(graphics, font, track.formattedDuration(), rightEdge - durationWidth,
                centerY - 4, arriving(unavailable ? Theme.TEXT_DIM : Theme.TEXT_MUTED, entry), false);
    }

    // --------------------------------------------------------------- the artist line

    // ------------------------------------------------------------------- pointer

    public boolean mouseClicked(double mouseX, double mouseY, int button, int x, int y, int width, int height,
                                Handler handler) {
        if (!Theme.hovered((int) mouseX, (int) mouseY, x, y, width, height)) {
            return false;
        }
        int index = (scroll.offset() + (int) mouseY - y) / ROW_HEIGHT;
        if (index < 0 || index >= tracks.size()) {
            return false;
        }
        Track track = tracks.get(index);
        boolean unavailable = !track.playable() && ConfigManager.client().dimUnavailableTracks;
        Font font = Minecraft.getInstance().font;
        int rowY = y + index * ROW_HEIGHT - scroll.offset();
        int likeX = likeX(font, track, x, width);
        // A dimmed row offers no menu: the plus is not drawn, its hit area is gone, and the right
        // click stays shut too. Only the heart keeps working, saving a track is always allowed.
        if (!unavailable) {
            boolean overPlus = Theme.hovered((int) mouseX, (int) mouseY, likeX + 6, rowY + 8, 14, 14);
            if (button == 1 || overPlus) {
                // Only the plus itself plops. A right click elsewhere in the row opens the same menu
                // without anything under the cursor having been pressed.
                if (overPlus) {
                    Anim.click("row.plus:" + index);
                }
                handler.context(track, mouseX, mouseY);
                return true;
            }
        }
        if (button == 0 && likeable(track)
                && Theme.hovered((int) mouseX, (int) mouseY, likeX - 8, rowY + 8, 14, 14)) {
            Anim.click("row.like:" + index);
            handler.toggleLike(track);
            return true;
        }
        if (button == 0 && !unavailable) {
            // An artist name leads to that artist, not to the song. Checked before the row itself,
            // so the name wins over the play area it sits inside of - but not on a dimmed row, where
            // the name is not lit either and a hit area nothing points at is the bug this list has
            // already had once with the plus.
            ArtistLine.Span span = ArtistLine.hit(font, track, textX(x), textWidth(font, track, x, width),
                    mouseX, mouseY, rowY + 17);
            if (span != null) {
                Anim.click("row.artist:" + index + ":" + span.credit());
                handler.openArtist(span.id(), span.name());
                return true;
            }
        }
        if (unavailable || button != 0) {
            return true;
        }
        // Nothing plays yet. The row is only marked as held, because this same press may still turn
        // into a drag, and a song that started the moment a row was picked up would be the wrong
        // answer to someone rearranging their playlist.
        pressIndex = index;
        pressMouseY = mouseY;
        dropIndex = index;
        dragging = false;
        return true;
    }

    /** Follows the cursor while a row is held. Returns whether it took the movement. */
    public boolean mouseDragged(double mouseY, Handler handler) {
        if (pressIndex < 0) {
            return false;
        }
        dragMouseY = (int) mouseY;
        double travelled = Math.abs(mouseY - pressMouseY);
        if (!dragging) {
            if (travelled < DRAG_THRESHOLD) {
                return true;
            }
            if (!handler.reorderable()) {
                // The press stays armed. Nothing here can be rearranged, so that movement was never
                // a drag, and letting go still plays the row exactly as it always did. Only a pull
                // long enough to have been meant as one is worth answering, and only once.
                if (!refused && travelled >= REFUSE_DISTANCE) {
                    refused = true;
                    handler.reorderRefused();
                }
                return false;
            }
            dragging = true;
        }
        dropIndex = rowUnder(mouseY);
        return true;
    }

    public void mouseReleased(Handler handler) {
        if (pressIndex < 0) {
            cancelDrag();
            return;
        }
        int from = pressIndex;
        boolean moved = dragging;
        int to = Math.max(0, Math.min(tracks.size() - 1, dropIndex));
        cancelDrag();
        if (from >= tracks.size()) {
            return;
        }
        if (!moved) {
            Anim.click("row:" + from);
            handler.play(from, tracks.get(from));
            return;
        }
        // Asked again at the release, not only when the drag began. A page can arrive mid drag and
        // drop entries, and from that moment the positions on screen are no longer Spotify's.
        if (from == to || !handler.reorderable()) {
            return;
        }
        // Moved here as well, not only on the server. The next page of this listing is fetched from
        // an offset counting entries Spotify has already handed over, so reloading to see the new
        // order would either lose the user's place or leave the old order on screen until they left.
        tracks.add(to, tracks.remove(from));
        Anim.forget("row.slot:");
        handler.reorder(from, to);
    }

    /** Which row the cursor is over, clamped into the list. */
    private int rowUnder(double mouseY) {
        int row = (scroll.offset() + (int) mouseY - viewY) / ROW_HEIGHT;
        return Math.max(0, Math.min(tracks.size() - 1, row));
    }

    /**
      * Pulls the list along while a drag rests against the top or bottom edge, which is the only way
      * to move a row past the end of the screen in a playlist of any length.
      */
    private void dragEdgeScroll() {
        if (viewHeight <= 0) {
            return;
        }
        int fromTop = dragMouseY - viewY;
        int fromBottom = viewY + viewHeight - dragMouseY;
        float direction = fromTop < DRAG_EDGE ? 1.0f : fromBottom < DRAG_EDGE ? -1.0f : 0.0f;
        if (direction == 0.0f) {
            return;
        }
        // scrollBy counts wheel notches and multiplies by 24, so the distance is divided back out
        // here to end up with the pixels per second this constant is written in.
        scroll.scrollBy(direction * DRAG_SCROLL_SPEED * Anim.delta() / 24.0);
        dropIndex = rowUnder(dragMouseY);
    }

    private void cancelDrag() {
        pressIndex = -1;
        dropIndex = -1;
        dragging = false;
        refused = false;
    }

    public boolean mouseScrolled(double amount) {
        return scroll.scrollBy(amount);
    }
}
