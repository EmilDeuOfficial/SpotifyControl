package de.emilo.spotifycontrol.gui.widget;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.gui.Anim;
import de.emilo.spotifycontrol.gui.Icons;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.gui.View;
import de.emilo.spotifycontrol.spotify.model.MediaItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;

public class Sidebar {

    public static final int WIDTH = 148;

    private static final int NAV_HEIGHT = 26;
    private static final int PLAYLIST_ROW = 24;

    /** Height of the bar marking the current page. Short enough to read as a mark, not as a border. */

    /** How many placeholder rows stand in for a library that has not arrived yet. */
    private static final int SKELETON_ROWS = 5;

    private final ScrollArea scroll = new ScrollArea();

    public ScrollArea scroll() {
        return scroll;
    }

    private int listY;
    private int listHeight;

    public void render(GuiGraphics graphics, int x, int y, int height, int mouseX, int mouseY, View active) {
        Font font = Minecraft.getInstance().font;
        Theme.rounded(graphics, x, y, WIDTH, height, Theme.PANEL_RADIUS, Theme.BLACK,
                Theme.CORNER_TOP_LEFT);

        drawLogo(graphics, font, x, y);

        int navY = y + 38;
        drawNav(graphics, font, x, navY, mouseX, mouseY, active == View.HOME, Lang.plain("gui.home"), 0);
        drawNav(graphics, font, x, navY + NAV_HEIGHT, mouseX, mouseY, active == View.SEARCH,
                Lang.plain("gui.search"), 1);
        drawNav(graphics, font, x, navY + NAV_HEIGHT * 2, mouseX, mouseY, active == View.LIBRARY,
                Lang.plain("gui.library"), 2);
        drawNav(graphics, font, x, navY + NAV_HEIGHT * 3, mouseX, mouseY, active == View.PARTY,
                Lang.plain("gui.party"), 3);
        drawNav(graphics, font, x, navY + NAV_HEIGHT * 4, mouseX, mouseY, active == View.SETTINGS,
                Lang.plain("gui.settings"), 4);

        int dividerY = navY + NAV_HEIGHT * 5 + 4;
        graphics.fill(x + 12, dividerY, x + WIDTH - 12, dividerY + 1, Theme.BORDER);
        Platform.drawString(graphics, font, Lang.plain("gui.playlists"), x + 12, dividerY + 8, Theme.TEXT_MUTED, false);

        listY = dividerY + 22;
        listHeight = Math.max(20, y + height - listY - 10);
        List<MediaItem> playlists = ClientState.session().playlists();
        scroll.setViewportHeight(listHeight);
        scroll.setContentHeight((playlists.size() + 1) * PLAYLIST_ROW);

        Theme.scissor(graphics, x, listY, x + WIDTH, listY + listHeight);
        int offset = scroll.offset();
        drawPlaylistRow(graphics, font, x, listY - offset, mouseX, mouseY, Lang.plain("gui.liked"), "", true, 0);
        for (int index = 0; index < playlists.size(); index++) {
            MediaItem item = playlists.get(index);
            int rowY = listY + (index + 1) * PLAYLIST_ROW - offset;
            if (rowY + PLAYLIST_ROW < listY || rowY > listY + listHeight) {
                continue;
            }
            drawPlaylistRow(graphics, font, x, rowY, mouseX, mouseY, item.name(), item.imageUrl(), false, index + 1);
        }
        if (playlists.isEmpty()) {
            drawSkeletons(graphics, x, listY - offset);
        }
        Theme.endScissor(graphics);
        scroll.renderScrollbar(graphics, x + WIDTH - 6, listY, listHeight);
    }

    /**
     * The mark at the top of the column. Connecting has no progress worth showing, so the logo
     * itself breathes instead: slow and shallow enough to read as a heartbeat rather than a blink,
     * and perfectly still again the moment the session is up.
     */
    private void drawLogo(GuiGraphics graphics, Font font, int x, int y) {
        int centerX = x + 18;
        int centerY = y + 18;
        int size = ClientState.session().connecting() ? Anim.grownSize(9, Anim.pulse(2.4f), 1) : 9;
        Icons.spotify(graphics, centerX, centerY, size);
        Platform.drawString(graphics, font, "SpotifyControl", x + 32, y + 14, Theme.TEXT, false);
    }

    private void drawNav(GuiGraphics graphics, Font font, int x, int y, int mouseX, int mouseY, boolean active,
                         String label, int icon) {
        int rowWidth = WIDTH - 12;
        int rowHeight = NAV_HEIGHT - 2;
        boolean hovered = Theme.hovered(mouseX, mouseY, x + 6, y, rowWidth, rowHeight);
        String id = "side.nav:" + icon;
        float hover = Anim.hover(id, hovered);

        // A nav row is shaped like a row but reads as a button, so its contents give way with it
        // rather than hanging in the air: the icon is asked for again a pixel smaller instead of
        // being resampled, and the label, which cannot shrink without going soft, settles into the
        // row by that pixel instead. Both come back out on the rebound, where the inset is negative.
        int inset = Anim.pressInset(id, 1);
        int pressX = x + 6 + inset;
        int pressY = y + inset;
        int pressWidth = rowWidth - inset * 2;
        int pressHeight = rowHeight - inset * 2;

        if (active) {
            Theme.rounded(graphics, pressX, pressY, pressWidth, pressHeight, 4, Theme.SURFACE_HOVER);
        } else if (hover > 0.004f) {
            // The panel behind the row is solid black, so a mix towards it lands on exactly the
            // colour the old hard switch produced once the fade has run its course.
            Theme.rounded(graphics, pressX, pressY, pressWidth, pressHeight, 4,
                    Anim.mix(Theme.BLACK, Theme.SURFACE, hover));
        }

        // Two colours out of one fade: the icon is rasterized once per colour and has to land on
        // a step the cache has seen before, while the label is drawn glyph by glyph and never
        // cached, so it can keep every step of the same fade.
        int color = active ? Theme.TEXT : Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, hover);
        int iconColor = active ? Theme.TEXT : Anim.mix(Theme.TEXT_MUTED, Theme.TEXT, hover);
        int iconX = x + 20;
        int iconY = y + rowHeight / 2;
        // The row gives one pixel, the icon on it two: an icon is rasterized to an even number of
        // pixels, so one would land on the same picture and it would sit still while the row moved.
        int iconSize = Anim.grownSize(12, hover, Anim.ICON_STEP) - Anim.iconPress(id);
        switch (icon) {
            case 0 -> Icons.home(graphics, iconX, iconY, iconSize, iconColor);
            case 1 -> Icons.search(graphics, iconX, iconY, iconSize, iconColor);
            case 2 -> Icons.library(graphics, iconX, iconY, iconSize, iconColor);
            case 3 -> Icons.people(graphics, iconX, iconY, iconSize, iconColor);
            default -> Icons.gear(graphics, iconX, iconY, iconSize, iconColor);
        }
        Platform.drawString(graphics, font, Theme.fit(font, label, WIDTH - 46), x + 34,
                y + (rowHeight - 8) / 2 + inset, color, false);
    }

    private void drawPlaylistRow(GuiGraphics graphics, Font font, int x, int y, int mouseX, int mouseY, String name,
                                 String imageUrl, boolean liked, int index) {
        int rowWidth = WIDTH - 14;
        int rowHeight = PLAYLIST_ROW - 2;
        boolean hovered = Theme.hovered(mouseX, mouseY, x + 6, y, rowWidth, rowHeight)
                && mouseY >= listY && mouseY < listY + listHeight
                && !scroll.claims(mouseX, mouseY);
        String id = "side.pl:" + index;
        float hover = Anim.hover(id, hovered);

        int inset = Anim.pressInset(id, 1);

        // The cover sits on top of this very colour, so both take it from one value: a mask cut from
        // a shade the row has already moved past would draw a ring around the artwork.
        int background = Anim.mix(Theme.BLACK, Theme.SURFACE, hover);
        if (hover > 0.004f) {
            Theme.rounded(graphics, x + 6 + inset, y + inset, rowWidth - inset * 2, rowHeight - inset * 2, 4,
                    background);
        }
        // The row highlight is two pixels shorter than the row, so the contents follow it
        // instead of the full pitch, otherwise they sit a pixel low inside the highlight.
        int artSize = PLAYLIST_ROW - 8;
        int artX = x + 12;
        int artY = y + (rowHeight - artSize) / 2;
        if (liked) {
            Theme.rounded(graphics, artX, artY, artSize, artSize, 3, Theme.GREEN_DARK);
            Icons.heart(graphics, artX + artSize / 2, artY + artSize / 2, 7, Theme.TEXT);
        } else {
            Object art = ClientState.artwork().get(imageUrl, artSize);
            if (art != null) {
                Platform.drawTexture(graphics, art, artX, artY, artSize, artSize);
                Theme.roundedMask(graphics, artX, artY, artSize, artSize, 3, background);
            } else {
                Theme.rounded(graphics, artX, artY, artSize, artSize, 3, Theme.ELEVATED);
                Icons.note(graphics, artX + artSize / 2, artY + artSize / 2, 9, Theme.TEXT_DIM);
            }
        }
        Platform.drawString(graphics, font, Theme.fit(font, name, WIDTH - 44), artX + artSize + 6,
                y + (rowHeight - 8) / 2, Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, hover), false);
    }

    /**
     * Stand-ins for the rows the library is about to fill. Only while the account is connected: with
     * no session nothing is on its way, and an empty column is then the honest answer.
     */
    private void drawSkeletons(GuiGraphics graphics, int x, int topY) {
        if (!Anim.enabled() || !ClientState.session().connected()) {
            return;
        }
        int artSize = PLAYLIST_ROW - 8;
        for (int i = 0; i < SKELETON_ROWS; i++) {
            int rowY = topY + (i + 1) * PLAYLIST_ROW;
            if (rowY + PLAYLIST_ROW - 2 > listY + listHeight) {
                break;
            }
            Anim.skeleton(graphics, x + 12, rowY + (PLAYLIST_ROW - 2 - artSize) / 2, artSize, artSize, 3, i);
            // Uneven bars, because a column of identical ones reads as a pattern rather than as
            // names that have not come in yet.
            Anim.skeleton(graphics, x + 12 + artSize + 6, rowY + (PLAYLIST_ROW - 2 - 6) / 2,
                    88 - (i % 3) * 14, 6, 2, i);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int x, int y, int height, Consumer<View> onNavigate,
                                Runnable onLiked, Consumer<MediaItem> onPlaylist) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (!Theme.hovered(mx, my, x, y, WIDTH, height)) {
            return false;
        }
        int navY = y + 38;
        View[] views = {View.HOME, View.SEARCH, View.LIBRARY, View.PARTY, View.SETTINGS};
        for (int i = 0; i < views.length; i++) {
            if (Theme.hovered(mx, my, x + 6, navY + NAV_HEIGHT * i, WIDTH - 12, NAV_HEIGHT - 2)) {
                Anim.click("side.nav:" + i);
                onNavigate.accept(views[i]);
                return true;
            }
        }
        if (my < listY || my >= listY + listHeight) {
            return true;
        }
        if (mx < x + 6 || mx >= x + WIDTH - 8) {
            return true;
        }
        int index = (my - listY + scroll.offset()) / PLAYLIST_ROW;
        if (index == 0) {
            Anim.click("side.pl:0");
            onLiked.run();
            return true;
        }
        List<MediaItem> playlists = ClientState.session().playlists();
        int playlistIndex = index - 1;
        if (playlistIndex >= 0 && playlistIndex < playlists.size()) {
            Anim.click("side.pl:" + index);
            onPlaylist.accept(playlists.get(playlistIndex));
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount, int x) {
        if (mouseX < x || mouseX > x + WIDTH || mouseY < listY || mouseY > listY + listHeight) {
            return false;
        }
        return scroll.scrollBy(amount);
    }
}
