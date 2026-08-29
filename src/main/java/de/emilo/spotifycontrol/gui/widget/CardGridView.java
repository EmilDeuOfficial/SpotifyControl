package de.emilo.spotifycontrol.gui.widget;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.gui.Anim;
import de.emilo.spotifycontrol.gui.Icons;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.spotify.model.MediaItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;

public class CardGridView {

    private static final int CARD_WIDTH = 96;
    private static final int CARD_HEIGHT = 122;
    private static final int GAP = 8;

    /** How far a card rises off the grid while the cursor rests on it. */
    private static final float HOVER_LIFT = 2.0f;

    /** How far below its place a card starts out when the grid has just been filled. */
    private static final float ENTRY_SLIDE = 10.0f;

    /** How far under the artwork the play button waits until the cursor calls it up. */
    private static final float PLAY_RISE = 6.0f;

    /**
      * Where the play button starts as it comes up from behind the artwork, and where its triangle
      * starts. Only the starting sizes are named: the ends follow from PLAY_STEPS below, so the two
      * cannot be given a number of steps that does not reach the size they were meant to end at.
      * The disc runs 7 to 11 and the triangle 5 to 9, both in two steps.
      */
    private static final int PLAY_RADIUS_MIN = 7;
    private static final int PLAY_ICON_MIN = 5;

    /** The size the triangle ends at, which is what fixes how many steps the pair takes. */
    private static final int PLAY_ICON = 9;

    /**
      * How far a pressed card draws inside its own edges. A card is a large surface, and one pixel
      * on something this size is a movement the user has to go looking for.
      */
    private static final int PRESS_DEPTH = 2;

    /** Placeholder rows while the library is empty. Enough to show a grid, not a fake collection. */
    private static final int SKELETON_ROWS = 2;

    private final ScrollArea scroll = new ScrollArea();
    private List<MediaItem> items = List.of();

    /** When the current contents arrived, which is what the staggered entrance counts from. */
    private long entryStamp;

    public ScrollArea scroll() {
        return scroll;
    }

    /**
      * The library view calls this every frame, so the scroll position may only be given up when the
      * contents actually change - resetting unconditionally made scrolling impossible there. The
      * same comparison decides when the cards are worth animating in: a page that is merely being
      * redrawn must not restart its entrance.
      */
    public void setItems(List<MediaItem> items) {
        List<MediaItem> next = items == null ? List.of() : items;
        if (!this.items.equals(next)) {
            scroll.reset();
            entryStamp = System.currentTimeMillis();
        }
        this.items = next;
    }

    public List<MediaItem> items() {
        return items;
    }

    private int columns(int width) {
        return Math.max(1, (width - GAP) / (CARD_WIDTH + GAP));
    }

    public void render(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int columns = columns(width);
        int rows = (items.size() + columns - 1) / columns;
        scroll.setViewportHeight(height);
        scroll.setContentHeight(rows * (CARD_HEIGHT + GAP));

        Theme.scissor(graphics, x, y, x + width, y + height);
        if (items.isEmpty()) {
            renderSkeletons(graphics, x, y, height, columns);
        } else {
            renderCards(graphics, font, x, y, height, mouseX, mouseY, columns);
        }
        Theme.endScissor(graphics);
        scroll.renderScrollbar(graphics, x + width - 4, y, height);
    }

    /**
      * The grid itself. A card can be moving in three ways at once - arriving, lifting under the
      * cursor, plopping from a click - so the first two become one whole pixel offset and the third
      * moves the card's own edges, and the card is drawn once at the result.
      */
    private void renderCards(GuiGraphics graphics, Font font, int x, int y, int height,
                             int mouseX, int mouseY, int columns) {
        int offset = scroll.offset();
        for (int index = 0; index < items.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            int cardX = x + column * (CARD_WIDTH + GAP);
            int cardY = y + row * (CARD_HEIGHT + GAP) - offset;
            String id = "card:" + index;
            if (cardY + CARD_HEIGHT < y || cardY > y + height) {
                // A card that has scrolled out of sight keeps cooling down, so it does not come
                // back warm under a cursor that moved away long ago.
                Anim.hover(id, false);
                continue;
            }
            float entered = Anim.entry(entryStamp, index);
            if (entered <= 0.0f) {
                // Its turn in the stagger has not come round yet, and there is nothing worth
                // showing of a card that has not started to arrive.
                continue;
            }
            boolean hovered = Theme.hovered(mouseX, mouseY, cardX, cardY, CARD_WIDTH, CARD_HEIGHT)
                    && mouseY >= y && mouseY < y + height
                    && !scroll.claims(mouseX, mouseY);
            float hover = Anim.hover(id, hovered);
            float shift = (1.0f - entered) * ENTRY_SLIDE;
            if (Anim.enabled()) {
                // The lift exists only while the interface is allowed to move. With animations off
                // a hovered card has to sit exactly where an idle one sits.
                shift -= HOVER_LIFT * hover;
            }
            renderCard(graphics, font, items.get(index), cardX, cardY + Math.round(shift), hover,
                    Anim.pressStep(id));
        }
    }

    /**
      * The shape of the library while it is still on its way. Two rows are enough to say that a grid
      * belongs here; more would read as a collection the user does not have.
      */
    private void renderSkeletons(GuiGraphics graphics, int x, int y, int height, int columns) {
        if (!Anim.enabled()) {
            return;
        }
        int rows = Math.min(SKELETON_ROWS, Math.max(1, (height + GAP) / (CARD_HEIGHT + GAP)));
        int artSize = CARD_WIDTH - 16;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int cardX = x + column * (CARD_WIDTH + GAP);
                int cardY = y + row * (CARD_HEIGHT + GAP);
                int index = row * columns + column;
                Theme.rounded(graphics, cardX, cardY, CARD_WIDTH, CARD_HEIGHT, 6, Theme.SURFACE);
                Anim.skeleton(graphics, cardX + 8, cardY + 8, artSize, artSize, 5, index);
                Anim.skeleton(graphics, cardX + 8, cardY + artSize + 14, artSize, 6, 3, index + 1);
                Anim.skeleton(graphics, cardX + 8, cardY + artSize + 25, artSize - 24, 6, 3, index + 2);
            }
        }
    }

    private void renderCard(GuiGraphics graphics, Font font, MediaItem item, int x, int y, float hover,
                            int press) {
        // One step for the card and for the button on it, so the outline, the disc and the triangle
        // all move on the same frame. Working each of them out separately put them on thresholds a
        // frame or two apart, and the button came away from the card it sits on.
        int inset = press * PRESS_DEPTH;
        // This tint reaches a cached rounded rectangle and the mask over the artwork below, so the
        // hover fade has to arrive in steps instead of at every value it passes through.
        int background = Anim.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover);
        // A press pulls this outline in and springs it back out again. The artwork and the two lines
        // of text stay put: they are one composition, and neither a picture nor a line of text can
        // be redrawn a pixel smaller without coming apart. The play button below is a button of its
        // own and does give with the card.
        Theme.rounded(graphics, x + inset, y + inset, CARD_WIDTH - 2 * inset, CARD_HEIGHT - 2 * inset, 6,
                background);
        int artSize = CARD_WIDTH - 16;
        Object art = ClientState.artwork().get(item.imageUrl(), artSize);
        if (art != null) {
            Platform.drawTexture(graphics, art, x + 8, y + 8, artSize, artSize);
            // The mask paints the corners back in the card colour, so it has to follow the fade.
            Theme.roundedMask(graphics, x + 8, y + 8, artSize, artSize, 5, background);
        } else {
            Theme.rounded(graphics, x + 8, y + 8, artSize, artSize, 5, Theme.ELEVATED);
            Icons.note(graphics, x + CARD_WIDTH / 2, y + 8 + artSize / 2, 22, Theme.TEXT_DIM);
        }
        Platform.drawString(graphics, font, Theme.fit(font, item.name(), CARD_WIDTH - 16), x + 8, y + artSize + 14,
                Theme.TEXT, false);
        String subtitle = item.kind() == MediaItem.Kind.PLAYLIST && item.trackCount() > 0
                ? item.trackCount() + " tracks"
                : item.subtitle();
        Platform.drawString(graphics, font, Theme.fit(font, subtitle, CARD_WIDTH - 16), x + 8, y + artSize + 25,
                Theme.TEXT_MUTED, false);
        if (hover > 0.0f) {
            int buttonX = x + CARD_WIDTH - 20;
            // Half arrived it is still climbing out from behind the artwork, growing on the way, and
            // it takes the same path back down once the cursor leaves.
            int buttonY = y + artSize - 4 + Math.round((1.0f - hover) * PLAY_RISE);
            // One number for the disc and the triangle on it, multiplied by what each of them
            // counts in. A radius is rasterized at twice itself and an icon is rounded up to an even
            // size, so asking the two separately let the disc grow on steps the triangle could not
            // take, and the mark appeared to shrink inside its own button on the way out.
            int step = Anim.enabled() ? Anim.growSteps(hover, PLAY_STEPS) - press : PLAY_STEPS;
            Theme.circle(graphics, buttonX, buttonY, PLAY_RADIUS_MIN + step * 2, Theme.GREEN_BRIGHT);
            Icons.play(graphics, buttonX + 1, buttonY, PLAY_ICON_MIN + step * Anim.ICON_STEP, Theme.BLACK);
        }
    }

    /** Steps the play button takes on its way out from behind the artwork. */
    private static final int PLAY_STEPS = (PLAY_ICON - PLAY_ICON_MIN) / Anim.ICON_STEP;

    public boolean mouseClicked(double mouseX, double mouseY, int x, int y, int width, int height,
                               Consumer<MediaItem> onSelect) {
        if (!Theme.hovered((int) mouseX, (int) mouseY, x, y, width, height)) {
            return false;
        }
        int columns = columns(width);
        int localX = (int) mouseX - x;
        int localY = (int) mouseY - y + scroll.offset();
        int column = localX / (CARD_WIDTH + GAP);
        int row = localY / (CARD_HEIGHT + GAP);
        if (column >= columns || localX % (CARD_WIDTH + GAP) >= CARD_WIDTH
                || localY % (CARD_HEIGHT + GAP) >= CARD_HEIGHT) {
            return false;
        }
        int index = row * columns + column;
        if (index < 0 || index >= items.size()) {
            return false;
        }
        // The press only draws the card and its play button smaller inside the cell they rest in, so
        // the hit test on that resting cell still lands on exactly what the user is looking at.
        Anim.click("card:" + index);
        onSelect.accept(items.get(index));
        return true;
    }

    public boolean mouseScrolled(double amount) {
        return scroll.scrollBy(amount);
    }
}
