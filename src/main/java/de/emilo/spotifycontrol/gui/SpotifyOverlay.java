package de.emilo.spotifycontrol.gui;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.platform.ScreenHost;
import de.emilo.spotifycontrol.gui.raster.RasterCache;
import de.emilo.spotifycontrol.gui.widget.CardGridView;
import de.emilo.spotifycontrol.gui.widget.PartyPanel;
import de.emilo.spotifycontrol.gui.widget.PlayerBar;
import de.emilo.spotifycontrol.gui.widget.ScrollArea;
import de.emilo.spotifycontrol.gui.widget.SettingsPanel;
import de.emilo.spotifycontrol.gui.widget.Sidebar;
import de.emilo.spotifycontrol.gui.widget.TrackListView;
import de.emilo.spotifycontrol.spotify.SpotifySession;
import de.emilo.spotifycontrol.spotify.model.Device;
import de.emilo.spotifycontrol.spotify.model.MediaItem;
import de.emilo.spotifycontrol.spotify.model.SearchResults;
import de.emilo.spotifycontrol.spotify.model.Track;
import de.emilo.spotifycontrol.spotify.model.TrackPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SpotifyOverlay {

    /** Smallest window the layout still fits into, in overlay pixels. */
    private static final int MIN_APP_WIDTH = 340;
    private static final int MIN_APP_HEIGHT = 240;

    private int margin() {
        int wanted = Math.max(0, Math.min(40, ConfigManager.client().appMargin));
        int byWidth = Math.max(0, (width - MIN_APP_WIDTH) / 2);
        int byHeight = Math.max(0, (height - MIN_APP_HEIGHT) / 2);
        return Math.min(wanted, Math.min(byWidth, byHeight));
    }
    private static final int HEADER_HEIGHT = 34;
    private static final int CONTEXT_ENTRY_HEIGHT = 18;
    private static final String[] SEARCH_TABS = {"Songs", "Playlists", "Albums", "Artists"};

    /** How long the panel colour lies over a page that has just been opened, in milliseconds. */
    private static final int VIEW_FADE_MILLIS = 180;

    /** The share of its full height a menu box starts at while it opens. */
    private static final float MENU_START_HEIGHT = 0.6f;

    private final Sidebar sidebar = new Sidebar();
    private final PlayerBar playerBar = new PlayerBar();
    private final TrackListView trackList = new TrackListView();
    private final CardGridView cardGrid = new CardGridView();
    private final PartyPanel partyPanel = new PartyPanel();
    private final SettingsPanel settingsPanel = new SettingsPanel();

    private EditBox searchBox;
    private View view = View.HOME;
    private String detailTitle = "";
    private String detailSubtitle = "";
    private String detailArtUrl = "";
    private String detailContextUri = "";
    private int searchTab;
    private int homeTab;
    private boolean deviceMenuOpen;
    private List<Device> paintedDevices = List.of();
    private long lastSearchMillis;
    private String pendingQuery = "";

    private boolean contextOpen;
    private boolean contextPickerOpen;
    private Track contextTrack = Track.NONE;
    private int contextX;
    private int contextY;
    private final ScrollArea contextScroll = new ScrollArea();
    private String detailOwnedPlaylistId = "";

    // Track listings load one page at a time. detailOffset counts raw entries, not usable tracks,
    // and detailRequestId invalidates pages that arrive after the user opened something else.
    private MediaItem detailItem;
    private boolean detailLiked;
    private int detailOffset;
    private int detailTotal;
    private boolean detailLoading;
    private boolean detailComplete = true;
    private int detailRequestId;
    private long detailRetryAfterMillis;
    private boolean creatingPlaylist;
    private EditBox newPlaylistBox;

    private int appX;
    private int appY;
    private int appWidth;
    private int appHeight;
    private int contentX;
    private int contentY;
    private int contentWidth;
    private int contentHeight;

    // Nothing here survives a frame on its own, so the moments the animations are measured from have
    // to be remembered by the window itself: when a page was opened, when a menu appeared, when the
    // user last changed view.
    private long detailOpenedMillis;
    private long deviceMenuOpenedMillis;
    private long contextOpenedMillis;
    private long viewChangedMillis;
    private float deviceMenuOpenAmount;
    private float contextMenuOpenAmount;

    /** The last toast that was shown, held on to so the box can fade out after the text is gone. */
    private String fadingToast = "";

    private final ScreenHost host;
    private final Font font = Minecraft.getInstance().font;

    private int width;
    private int height;

    private float scale = 1.0f;

    public SpotifyOverlay(ScreenHost host) {
        this(host, null);
    }

    /** Set when the app should open straight on the saved tracks, which is not a View of its own. */
    private boolean openLikedOnInit;
    private boolean startViewApplied;

    public SpotifyOverlay(ScreenHost host, View initialView) {
        this.host = host;
        if (initialView != null) {
            this.view = initialView;
        } else {
            String start = ConfigManager.client().startView;
            openLikedOnInit = "LIKED".equalsIgnoreCase(start == null ? "" : start);
            this.view = openLikedOnInit ? View.HOME : parseView(start);
        }
    }

    private static View parseView(String name) {
        if (name == null || name.isBlank()) {
            return View.HOME;
        }
        try {
            View parsed = View.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            return switch (parsed) {
                case SEARCH, LIBRARY -> parsed;
                default -> View.HOME;
            };
        } catch (IllegalArgumentException e) {
            return View.HOME;
        }
    }

    public void init() {
        layout();
        if (searchBox != null) {
            applyStartView();
            return;
        }
        searchBox = new EditBox(font, contentX + 30, contentY + 10, contentWidth - 60, 16,
                Component.literal("search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(120);
        searchBox.setTextColor(Theme.TEXT);
        searchBox.setHint(Component.literal(Lang.plain("gui.search_placeholder")));
        searchBox.setResponder(value -> {
            pendingQuery = value.trim();
            lastSearchMillis = System.currentTimeMillis();
        });
        newPlaylistBox = new EditBox(font, contentX + 30, contentY + 40, 200, 16,
                Component.literal("playlist name"));
        newPlaylistBox.setBordered(false);
        newPlaylistBox.setMaxLength(80);
        newPlaylistBox.setTextColor(Theme.TEXT);
        newPlaylistBox.setHint(Component.literal(Lang.plain("gui.create_playlist")));
        if (view == View.HOME) {
            loadHome();
        }
        ClientState.session().refreshLibrary();
        applyStartView();
    }

    /**
      * Runs once, after the widgets exist. init() is called again on every resize, so acting more
      * than once would drag the user out of whatever page they had opened by then.
      */
    private void applyStartView() {
        if (startViewApplied) {
            return;
        }
        startViewApplied = true;
        if (view == View.SEARCH && searchBox != null) {
            searchBox.setFocused(true);
        }
        if (openLikedOnInit && ClientState.session().connected()) {
            openLiked();
        }
    }

    private void layout() {
        scale = Theme.computeOverlayScale();
        width = Math.round(host.hostWidth() / scale);
        height = Math.round(host.hostHeight() / scale);
        int margin = margin();
        appX = margin;
        appY = margin;
        appWidth = width - margin * 2;
        appHeight = height - margin * 2;
        contentX = appX + Sidebar.WIDTH;
        contentY = appY;
        contentWidth = appX + appWidth - contentX;
        contentHeight = appHeight - PlayerBar.HEIGHT;
    }

    public void tick() {
        ClientState.session().tick();
        if (view == View.QUEUE && System.currentTimeMillis() - lastQueueRefreshMillis > 5_000L) {
            loadQueue();
        }
        if (view == View.SEARCH && !pendingQuery.isEmpty()
                && System.currentTimeMillis() - lastSearchMillis > 400L) {
            String query = pendingQuery;
            pendingQuery = "";
            ClientState.session().search(query, this::onSearchResults);
        }
    }

    private static boolean sameTracks(List<Track> current, List<Track> next) {
        if (current.size() != next.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            if (!current.get(i).uri().equals(next.get(i).uri())) {
                return false;
            }
        }
        return true;
    }

    private void onSearchResults(SearchResults results) {
        Minecraft.getInstance().execute(() -> applySearchTab(results));
    }

    private void applySearchTab(SearchResults results) {
        if (view != View.SEARCH) {
            return;
        }
        switch (searchTab) {
            case 1 -> cardGrid.setItems(results.playlists());
            case 2 -> cardGrid.setItems(results.albums());
            case 3 -> cardGrid.setItems(results.artists());
            default -> showTracks(results.tracks());
        }
    }

    /** Shows a listing that is already complete, such as search results or the queue. */
    private void showTracks(List<Track> tracks) {
        stopListing();
        detailTotal = tracks.size();
        trackList.setTracks(tracks);
        ClientState.session().refreshLikedStatus(tracks);
    }

    /**
      * Ends an in-flight listing. The track list is shared by every view, so pages that are still on
      * their way must not be able to append into whatever the user opened next.
      */
    private void stopListing() {
        detailItem = null;
        detailLiked = false;
        detailComplete = true;
        detailLoading = false;
        detailRequestId++;
        detailRetryAfterMillis = 0L;
        trackList.setLoadMore(null);
        trackList.setMoreAvailable(false);
    }

    private long lastQueueRefreshMillis;

    private void loadQueue() {
        lastQueueRefreshMillis = System.currentTimeMillis();
        ClientState.session().loadQueue(tracks -> Minecraft.getInstance().execute(() -> {
            if (view == View.QUEUE && !sameTracks(trackList.tracks(), tracks)) {
                detailContextUri = "";
                showTracks(tracks);
            }
        }));
    }

    private void renderQueue(GuiGraphics graphics, Font font, int innerX, int innerWidth, int mouseX, int mouseY) {
        Platform.drawString(graphics, font, Lang.plain("gui.queue"), innerX, contentY + 14, Theme.TEXT, false);
        Track current = ClientState.session().playback().track();
        int headerY = contentY + 30;
        Theme.rounded(graphics, innerX, headerY, innerWidth, 40, 6, Theme.ELEVATED);
        Platform.drawString(graphics, font, Lang.plain("gui.now_playing"), innerX + 10, headerY + 7,
                Theme.TEXT_MUTED, false);
        if (current.isEmpty()) {
            Platform.drawString(graphics, font, Lang.plain("gui.empty"), innerX + 10, headerY + 22, Theme.TEXT_DIM,
                    false);
        } else {
            int artSize = 26;
            Object art = ClientState.artwork().get(current.artworkUrl(), artSize);
            if (art != null) {
                Platform.drawTexture(graphics, art, innerX + innerWidth - artSize - 8, headerY + 7, artSize, artSize);
                Theme.roundedMask(graphics, innerX + innerWidth - artSize - 8, headerY + 7, artSize, artSize, 3,
                        Theme.ELEVATED);
            }
            int textWidth = innerWidth - artSize - 30;
            String shownTitle = Theme.fit(font, current.title(), textWidth);
            Platform.drawString(graphics, font, shownTitle, innerX + 10, headerY + 22, Theme.GREEN, false);
            int artistX = innerX + 10 + font.width(shownTitle) + 8;
            int artistWidth = Math.max(0, innerX + 10 + textWidth - artistX);
            Platform.drawString(graphics, font, Theme.fit(font, current.artist(), artistWidth), artistX,
                    headerY + 22, Theme.TEXT_MUTED, false);
        }
        int bodyY = headerY + 48;
        trackList.setCurrentUri("");
        trackList.render(graphics, innerX, bodyY, innerWidth, contentY + contentHeight - bodyY - 8, mouseX, mouseY);
        if (trackList.tracks().isEmpty()) {
            Platform.drawString(graphics, font, Lang.plain("gui.empty"), innerX, bodyY + 8, Theme.TEXT_DIM, false);
        }
    }

    private boolean clickQueue(double mouseX, double mouseY, int button, int innerX, int innerWidth) {
        int bodyY = contentY + 78;
        return trackList.mouseClicked(mouseX, mouseY, button, innerX, bodyY, innerWidth,
                contentY + contentHeight - bodyY - 8, currentTrackHandler());
    }

    private void loadHome() {
        SpotifySession session = ClientState.session();
        if (homeTab == 0) {
            session.loadRecent(tracks -> Minecraft.getInstance().execute(() -> {
                if (view != View.HOME) {
                    return;
                }
                detailContextUri = "";
                showTracks(dedupe(tracks));
            }));
        } else {
            session.loadTop(tracks -> Minecraft.getInstance().execute(() -> {
                if (view != View.HOME) {
                    return;
                }
                detailContextUri = "";
                showTracks(tracks);
            }));
        }
    }

    private static List<Track> dedupe(List<Track> tracks) {
        List<Track> unique = new ArrayList<>();
        for (Track track : tracks) {
            if (unique.stream().noneMatch(existing -> existing.uri().equals(track.uri()))) {
                unique.add(track);
            }
        }
        return unique;
    }

    private void openDetail(MediaItem item) {
        markViewChanged();
        view = View.DETAIL;
        detailTitle = item.name();
        detailSubtitle = item.subtitle();
        detailArtUrl = item.imageUrl();
        detailContextUri = item.kind() == MediaItem.Kind.ARTIST ? "" : item.uri();
        detailOwnedPlaylistId = item.kind() == MediaItem.Kind.PLAYLIST && item.owned() ? item.id() : "";
        beginListing(item, false, item.trackCount());
    }

    private void openLiked() {
        markViewChanged();
        view = View.DETAIL;
        detailTitle = Lang.plain("gui.liked");
        detailSubtitle = ClientState.session().tokens().displayName;
        detailArtUrl = "";
        // The liked songs are a context of their own, which plays in order from the row clicked.
        String userId = ClientState.session().tokens().userId;
        detailContextUri = userId.isEmpty() ? "" : "spotify:user:" + userId + ":collection";
        detailOwnedPlaylistId = "";
        beginListing(null, true, 0);
    }

    private void beginListing(MediaItem item, boolean liked, int knownTotal) {
        // Both ways into the detail page come through here, so this is the one place that has to
        // know when its header should start sliding in.
        detailOpenedMillis = System.currentTimeMillis();
        detailItem = item;
        detailLiked = liked;
        detailOffset = 0;
        detailTotal = Math.max(0, knownTotal);
        trackList.setExpectedCount(detailTotal);
        detailComplete = false;
        detailLoading = false;
        detailRequestId++;
        detailRetryAfterMillis = 0L;
        trackList.setTracks(List.of());
        trackList.setLoadMore(this::loadNextTrackPage);
        trackList.setMoreAvailable(true);
        loadNextTrackPage();
    }

    private void loadNextTrackPage() {
        if (detailLoading) {
            return;
        }
        if (detailComplete || (detailItem == null && !detailLiked)
                || System.currentTimeMillis() < detailRetryAfterMillis) {
            // Nothing will be requested, so the list must not stay in its loading state or the
            // trigger would never fire again.
            trackList.setLoadingMore(false);
            return;
        }
        detailLoading = true;
        trackList.setLoadingMore(true);
        int request = detailRequestId;
        int offset = detailOffset;
        Consumer<TrackPage> handler = page ->
                Minecraft.getInstance().execute(() -> applyTrackPage(request, page));
        if (detailLiked) {
            ClientState.session().loadLikedPage(offset, handler);
        } else {
            ClientState.session().loadTrackPage(detailItem, offset, handler);
        }
    }

    private void applyTrackPage(int request, TrackPage page) {
        if (request != detailRequestId) {
            return;
        }
        detailLoading = false;
        trackList.setLoadingMore(false);
        if (page.failed()) {
            // Keep the listing open so scrolling can pick it up again, but at most once every few
            // seconds - all Spotify calls share one thread and a permanent failure would flood it.
            detailRetryAfterMillis = System.currentTimeMillis() + 5_000L;
            trackList.setMoreAvailable(true);
            return;
        }
        detailOffset += page.rawCount();
        detailTotal = page.total();
        trackList.setExpectedCount(detailTotal);
        detailComplete = page.complete();
        trackList.appendTracks(page.tracks());
        trackList.setMoreAvailable(!detailComplete);
        if (!detailComplete && page.tracks().isEmpty()) {
            // A page of nothing but local files or removed titles would otherwise stall the list,
            // because no new row appears to bring the prefetch trigger back into range.
            loadNextTrackPage();
        }
    }

    public void render(GuiGraphics graphics, int rawMouseX, int rawMouseY, float partialTick) {
        Anim.beginFrame();
        ClientState.session().markVisible();
        Theme.syncFromConfig();
        layout();
        if (ConfigManager.client().blurBackground) {
            host.hostRenderBackground(graphics, rawMouseX, rawMouseY, partialTick);
        }
        int mouseX = Math.round(rawMouseX / scale);
        int mouseY = Math.round(rawMouseY / scale);
        Theme.setOverlayScale(scale);
        RasterCache.setExtraScale(scale);
        Platform.pushScale(graphics, 0, 0, scale);
        graphics.fill(0, 0, width, height, Theme.shade());

        sidebar.render(graphics, appX, appY, appHeight - PlayerBar.HEIGHT, mouseX, mouseY, view);
        renderContent(graphics, mouseX, mouseY, partialTick);
        renderViewFade(graphics);
        int barY = appY + appHeight - PlayerBar.HEIGHT;
        playerBar.render(graphics, appX, barY, appWidth, mouseX, mouseY);

        // Both menus are driven every frame, open or not. A value that is only asked for while its
        // menu is up would still stand at one when the menu came back, and open with no movement.
        deviceMenuOpenAmount = Anim.to("menu.device", deviceMenuOpen ? 1.0f : 0.0f, Anim.FAST);
        contextMenuOpenAmount = Anim.to("menu.context", contextOpen ? 1.0f : 0.0f, Anim.FAST);
        if (deviceMenuOpen) {
            renderDeviceMenu(graphics, mouseX, mouseY);
        }
        if (contextOpen) {
            renderContextMenu(graphics, mouseX, mouseY);
        }
        renderToast(graphics);
        renderTooltip(graphics, mouseX, mouseY);
        Platform.popMatrix(graphics);
        RasterCache.resetExtraScale();
        Theme.setOverlayScale(1.0f);
    }

    private void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = this.font;
        Theme.rounded(graphics, contentX, contentY, contentWidth, contentHeight, Theme.PANEL_RADIUS, Theme.SURFACE,
                Theme.CORNER_TOP_RIGHT);
        Theme.verticalFadeRounded(graphics, contentX, contentY, contentWidth, 60,
                Theme.alpha(Theme.GREEN_DARK, 0.30f), Theme.alpha(Theme.SURFACE, 0f), Theme.PANEL_RADIUS,
                Theme.CORNER_TOP_RIGHT);

        int innerX = contentX + 14;
        int innerWidth = contentWidth - 28;

        if (showsConnectPrompt() && view != View.SETTINGS && view != View.PARTY) {
            renderConnectPrompt(graphics, font, innerX, innerWidth, mouseX, mouseY);
            return;
        }

        switch (view) {
            case HOME -> {
                renderTabs(graphics, font, innerX, contentY + 12, mouseX, mouseY,
                        new String[]{Lang.plain("gui.recently_played"), Lang.plain("gui.top_tracks")}, homeTab);
                int bodyY = contentY + HEADER_HEIGHT + 4;
                trackList.setCurrentUri(ClientState.session().playback().track().uri());
                trackList.render(graphics, innerX, bodyY, innerWidth, contentY + contentHeight - bodyY - 8,
                        mouseX, mouseY);
            }
            case SEARCH -> {
                Theme.rounded(graphics, innerX, contentY + 8, Math.min(340, innerWidth), 22, 11, Theme.ELEVATED);
                Icons.search(graphics, innerX + 14, contentY + 19, 10, Theme.TEXT_MUTED);
                searchBox.setPosition(innerX + 28, contentY + 15);
                searchBox.setWidth(Math.min(340, innerWidth) - 40);
                searchBox.render(graphics, mouseX, mouseY, partialTick);
                renderTabs(graphics, font, innerX, contentY + 38, mouseX, mouseY, SEARCH_TABS, searchTab);
                int bodyY = contentY + 60;
                int bodyHeight = contentY + contentHeight - bodyY - 8;
                if (searchTab == 0) {
                    trackList.setCurrentUri(ClientState.session().playback().track().uri());
                    trackList.render(graphics, innerX, bodyY, innerWidth, bodyHeight, mouseX, mouseY);
                    if (trackList.tracks().isEmpty()) {
                        Platform.drawString(graphics, font, Lang.plain("gui.empty"), innerX, bodyY + 8, Theme.TEXT_DIM, false);
                    }
                } else {
                    cardGrid.render(graphics, innerX, bodyY, innerWidth, bodyHeight, mouseX, mouseY);
                }
            }
            case LIBRARY -> {
                Platform.drawString(graphics, font, Lang.plain("gui.library"), innerX, contentY + 14, Theme.TEXT, false);
                int buttonX = innerX + innerWidth - 118;
                boolean buttonHovered = Theme.hovered(mouseX, mouseY, buttonX, contentY + 8, 118, 20);
                // Two pixels deep, because a button has more to give than a row does. The label
                // cannot give way by shrinking - a word redrawn a fraction smaller comes back soft -
                // so it rides one pixel down into the face instead and lifts again on the rebound.
                int createInset = Anim.pressInset("lib.create", 2);
                // Spelled out rather than through Anim.hoverColor, whose mix is not stepped: a
                // rounded rectangle is cached by its exact colour, so a fade running through it
                // would build a texture per frame and push the ordinary icons out of the cache.
                Theme.rounded(graphics, buttonX + createInset, contentY + 8 + createInset, 118 - 2 * createInset,
                        20 - 2 * createInset, 10,
                        Anim.mix(Theme.GREEN, Theme.GREEN_BRIGHT, Anim.hover("lib.create", buttonHovered)));
                String label = Lang.plain("gui.create_playlist");
                Platform.drawString(graphics, font, label, buttonX + (118 - font.width(label)) / 2,
                        contentY + 14 + Anim.pressInset("lib.create", 1), Theme.BLACK, false);
                int bodyY = contentY + HEADER_HEIGHT + 4;
                if (creatingPlaylist && newPlaylistBox != null) {
                    Theme.rounded(graphics, innerX, bodyY, Math.min(260, innerWidth), 22, 6, Theme.ELEVATED);
                    newPlaylistBox.setPosition(innerX + 8, bodyY + 7);
                    newPlaylistBox.setWidth(Math.min(260, innerWidth) - 16);
                    newPlaylistBox.render(graphics, mouseX, mouseY, partialTick);
                    bodyY += 28;
                }
                cardGrid.setItems(ClientState.session().playlists());
                cardGrid.render(graphics, innerX, bodyY, innerWidth, contentY + contentHeight - bodyY - 8,
                        mouseX, mouseY);
            }
            case DETAIL -> renderDetail(graphics, font, innerX, innerWidth, mouseX, mouseY);
            case QUEUE -> renderQueue(graphics, font, innerX, innerWidth, mouseX, mouseY);
            case PARTY -> partyPanel.render(graphics, innerX, contentY + 14, innerWidth,
                    contentHeight - HEADER_HEIGHT, mouseX, mouseY);
            case SETTINGS -> settingsPanel.render(graphics, innerX, contentY + 14, innerWidth,
                    contentHeight - HEADER_HEIGHT, mouseX, mouseY, partialTick);
        }
    }

    /** Remembers that the panel now shows something else, so the new page can fade up. */
    private void markViewChanged() {
        viewChangedMillis = System.currentTimeMillis();
    }

    /** Moved to {@link Anim#textFade}, where the other files can reach it too. */
    private static int fadedText(int color, float amount) {
        return Anim.textFade(color, amount);
    }

    /**
      * Lays the panel colour over the content for a moment after the view changed, thinning out as
      * it goes, so a page rises out of the surface instead of being swapped for another one. It is
      * drawn after the content and before the player bar, with the corner the panel itself rounds -
      * a full set of rounded corners here would paint square edges back into the window.
      */
    private void renderViewFade(GuiGraphics graphics) {
        if (!Anim.enabled() || viewChangedMillis <= 0L) {
            return;
        }
        long elapsed = System.currentTimeMillis() - viewChangedMillis;
        if (elapsed < 0L || elapsed >= VIEW_FADE_MILLIS) {
            return;
        }
        float progress = Anim.outCubic(elapsed / (float) VIEW_FADE_MILLIS);
        Theme.rounded(graphics, contentX, contentY, contentWidth, contentHeight, Theme.PANEL_RADIUS,
                Anim.fade(Theme.SURFACE, 1.0f - progress), Theme.CORNER_TOP_RIGHT);
    }

    /**
      * Whether the connect notice replaces the interface. A long throttle counts too: while it
      * lasts every list and button would sit there quietly doing nothing.
      */
    private boolean showsConnectPrompt() {
        return !ClientState.session().connected()
                || ClientState.session().rateLimitedForMillis() > 60_000L;
    }

    private void renderConnectPrompt(GuiGraphics graphics, Font font, int innerX, int innerWidth,
                                     int mouseX, int mouseY) {
        int centerY = contentY + contentHeight / 2;
        boolean connecting = ClientState.session().connecting();
        int logoX = contentX + contentWidth / 2;
        int logoY = centerY - 42;
        // While the login is out there somewhere, the logo breathes, a whole pixel of radius at a
        // time. Two of them, because this argument is a radius and so covers twice that in width.
        float breath = connecting ? Anim.pulse(2.2f) : 0.0f;
        Icons.logo(graphics, logoX, logoY, Anim.grownSize(22, breath, 2));

        long blocked = ClientState.session().rateLimitedForMillis();
        String title = blocked > 0L
                ? Lang.plain("gui.rate_limited_title", "time", SpotifySession.formatDuration(blocked))
                : connecting
                        ? Lang.plain("gui.connecting")
                        : Lang.plain("gui.connect");
        title = Theme.fit(font, title, innerWidth);
        Platform.drawString(graphics, font, title, contentX + (contentWidth - font.width(title)) / 2, centerY - 6,
                Theme.TEXT, false);
        String status = ClientState.session().status();
        if (blocked > 0L) {
            status = Lang.plain("gui.rate_limited_hint");
        } else if (status.isEmpty() && ConfigManager.client().spotifyClientId.isBlank()) {
            status = Lang.plain("auth.no_client_id");
        }
        if (!status.isEmpty()) {
            String fitted = Theme.fit(font, status, innerWidth);
            Platform.drawString(graphics, font, fitted, contentX + (contentWidth - font.width(fitted)) / 2, centerY + 8,
                    Theme.TEXT_MUTED, false);
        }
        if (connecting && Anim.enabled()) {
            // The status line says what is happening, the dots say that it still is.
            Anim.dots(graphics, contentX + (contentWidth - Anim.dotsWidth()) / 2, centerY + 21, Theme.TEXT_MUTED);
        }
        int buttonWidth = 150;
        int buttonX = contentX + (contentWidth - buttonWidth) / 2;
        boolean connectHovered = Theme.hovered(mouseX, mouseY, buttonX, centerY + 26, buttonWidth, 24);
        int connectInset = Anim.pressInset("prompt.connect", 2);
        Theme.rounded(graphics, buttonX + connectInset, centerY + 26 + connectInset, buttonWidth - 2 * connectInset,
                24 - 2 * connectInset, 12,
                Anim.mix(Theme.GREEN, Theme.GREEN_BRIGHT, Anim.hover("prompt.connect", connectHovered)));
        String label = Lang.plain("gui.connect");
        Platform.drawString(graphics, font, label, buttonX + (buttonWidth - font.width(label)) / 2,
                centerY + 34 + Anim.pressInset("prompt.connect", 1), Theme.BLACK, false);

        String settingsHint = Lang.plain("gui.settings");
        int hintWidth = font.width(settingsHint);
        int hintX = contentX + (contentWidth - hintWidth) / 2;
        boolean hintHovered = Theme.hovered(mouseX, mouseY, hintX - 6, centerY + 54, hintWidth + 12, 14);
        float hintFade = Anim.hover("prompt.settings", hintHovered);
        Platform.drawString(graphics, font, settingsHint, hintX, centerY + 58,
                Theme.mix(Theme.TEXT_DIM, Theme.TEXT, hintFade), false);
        if (hintFade > 0.01f) {
            // The rule grows in from nothing rather than switching on, but with animations off the
            // fade is already at either end and the line is exactly the one that was there before.
            // The press lives on the rule alone: this composition is a word, and a word that is
            // redrawn a fraction smaller comes back soft.
            int hintInset = Anim.pressInset("prompt.settings", 1);
            graphics.fill(hintX + hintInset, centerY + 67, hintX + hintWidth - hintInset, centerY + 68,
                    Theme.alpha(Theme.TEXT, hintFade));
        }
    }

    private void renderDetail(GuiGraphics graphics, Font font, int innerX, int innerWidth, int mouseX, int mouseY) {
        int artSize = 72;
        // A newly opened page carries its header up from a little below. The coordinates are moved
        // rather than the matrix, because the title below goes through scrollingText, which opens a
        // scissor of its own - a clip and an extra matrix never agree on where anything is.
        float entered = Anim.entry(detailOpenedMillis, 0);
        int headerY = contentY + 12 + Math.round((1.0f - entered) * 10.0f);
        Object art = ClientState.artwork().get(detailArtUrl, artSize);
        if (art == null && detailLiked) {
            // Saved tracks have no cover of their own; the client shows a heart tile instead.
            Theme.rounded(graphics, innerX, headerY, artSize, artSize, 6, Theme.GREEN_DARK);
            Icons.heart(graphics, innerX + artSize / 2, headerY + artSize / 2, artSize / 2,
                    Theme.TEXT);
        } else if (art != null) {
            Platform.drawTexture(graphics, art, innerX, headerY, artSize, artSize);
            int topTint = Theme.mix(Theme.SURFACE, Theme.GREEN_DARK, 0.24f);
            RasterCache.drawCorner(graphics, RasterCache.TOP_LEFT, innerX, headerY, 6, topTint, true);
            RasterCache.drawCorner(graphics, RasterCache.TOP_RIGHT, innerX + artSize - 6, headerY, 6, topTint,
                    true);
            RasterCache.drawCorner(graphics, RasterCache.BOTTOM_LEFT, innerX, headerY + artSize - 6, 6,
                    Theme.SURFACE, true);
            RasterCache.drawCorner(graphics, RasterCache.BOTTOM_RIGHT, innerX + artSize - 6,
                    headerY + artSize - 6, 6, Theme.SURFACE, true);
        } else {
            Theme.rounded(graphics, innerX, headerY, artSize, artSize, 6, Theme.ELEVATED);
            Icons.note(graphics, innerX + artSize / 2, headerY + artSize / 2, 30, Theme.TEXT_DIM);
        }
        int textX = innerX + artSize + 14;
        Theme.scrollingText(graphics, font, detailTitle, textX, headerY + 22, innerWidth - artSize - 30,
                fadedText(Theme.TEXT, entered), "detail.title");
        int total = detailTotal > 0 ? detailTotal : trackList.loadedCount();
        String subtitle = detailSubtitle.isEmpty()
                ? Lang.plain("gui.tracks", "count", total)
                : detailSubtitle + "  -  " + Lang.plain("gui.tracks", "count", total);
        Platform.drawString(graphics, font, Theme.fit(font, subtitle, innerWidth - artSize - 30), textX, headerY + 36,
                fadedText(Theme.TEXT_MUTED, entered), false);

        // The play button stays where it is while the header arrives: it is what the mouse aims at,
        // and a target that slides out from under the cursor is worse than one that appears.
        boolean playHovered = Theme.hovered(mouseX, mouseY, textX, contentY + 62, 26, 26);
        float playHover = Anim.hover("detail.play", playHovered);
        // Both are cached textures, so growth and press are folded into the size they ask for and
        // every frame is rasterized at it. One number moves the circle and the glyph together,
        // multiplied by what each counts in: a radius is rasterized at twice itself and an icon is
        // rounded to an even size, so asking them separately made them step a frame or two apart.
        boolean animated = Anim.enabled();
        int playStep = animated ? Anim.growSteps(playHover, 1) - Anim.pressStep("detail.play") : 0;
        Theme.circle(graphics, textX + 13, contentY + 75, (animated || !playHovered ? 13 : 14) + playStep,
                playHovered ? Theme.GREEN_BRIGHT : Theme.GREEN);
        Icons.play(graphics, textX + 14, contentY + 75, 12 + playStep * Anim.ICON_STEP, Theme.BLACK);

        int bodyY = contentY + 96;
        trackList.setCurrentUri(ClientState.session().playback().track().uri());
        trackList.render(graphics, innerX, bodyY, innerWidth, contentY + contentHeight - bodyY - 8, mouseX, mouseY);
    }

    /**
      * The tab strip. The white pill of the active tab is one thing that travels between the tabs
      * instead of a colour that jumps from one to the next, which is what makes a strip read as a
      * switch rather than as a row of separate buttons.
      *
      * <p>The eased pill is named after the size of the strip it belongs to. Home shows two tabs and
      * search four, and a name the animation core has not seen before starts at its target - so
      * changing page puts the pill straight where it belongs instead of letting it fly in from
      * wherever the other strip had left it.
      */
    private void renderTabs(GuiGraphics graphics, Font font, int x, int y, int mouseX, int mouseY, String[] labels,
                            int active) {
        if (labels.length == 0) {
            return;
        }
        int[] tabX = new int[labels.length];
        int[] tabWidth = new int[labels.length];
        int cursor = x;
        for (int i = 0; i < labels.length; i++) {
            tabX[i] = cursor;
            tabWidth[i] = font.width(labels[i]) + 20;
            cursor += tabWidth[i] + 6;
        }
        int chosen = Math.max(0, Math.min(labels.length - 1, active));
        // Named after the strip, not just the position. Home shows two tabs and search shows four,
        // and they are drawn by this same method, so a bare position would have the second tab of
        // one page inherit whatever the second tab of the other was in the middle of doing. The
        // marker already carried the strip in its name for exactly this reason.
        String strip = tabStrip(labels.length);

        for (int i = 0; i < labels.length; i++) {
            boolean hovered = Theme.hovered(mouseX, mouseY, tabX[i], y, tabWidth[i], 18);
            int inset = Anim.pressInset(strip + i, 1);
            Theme.rounded(graphics, tabX[i] + inset, y + inset, tabWidth[i] - 2 * inset, 18 - 2 * inset, 9,
                    Anim.mix(Theme.ELEVATED, Theme.SURFACE_HOVER, Anim.hover(strip + i, hovered)));
        }

        String markerId = "tab.marker" + labels.length;
        int markerX = Math.round(Anim.to(markerId + ".x", tabX[chosen], Anim.FAST));
        int markerWidth = Math.round(Anim.to(markerId + ".w", tabWidth[chosen], Anim.FAST));
        int markerInset = Anim.pressInset(strip + chosen, 1);
        Theme.rounded(graphics, markerX + markerInset, y + markerInset, markerWidth - 2 * markerInset,
                18 - 2 * markerInset, 9, Theme.TEXT);

        for (int i = 0; i < labels.length; i++) {
            // The label darkens as the pill arrives under it, which is the same movement seen twice.
            // Named after the strip like the pill, so both are in place together on a new page. It
            // settles a pixel into its own pill while that is pressed rather than shrinking with it,
            // since a word redrawn a fraction smaller comes back soft. A pill is only eighteen
            // pixels tall, so it gives a row's single pixel and not a button's two.
            float on = Anim.to(markerId + ".on:" + i, i == chosen ? 1.0f : 0.0f, Anim.FAST);
            Platform.drawString(graphics, font, labels[i], tabX[i] + 10, y + 5 + Anim.pressInset(strip + i, 1),
                    Theme.mix(Theme.TEXT_MUTED, Theme.BLACK, on), false);
        }
    }

    /** The name a tab strip animates under. See {@link #renderTabs}. */
    private static String tabStrip(int tabs) {
        return "tab" + tabs + ":";
    }

    /** Centres the device menu on its own button, kept inside the window. */
    private int deviceMenuX(int menuWidth) {
        int anchor = playerBar.deviceButtonX();
        if (anchor < 0) {
            return appX + appWidth - menuWidth - 60;
        }
        return Math.max(appX + 4, Math.min(appX + appWidth - menuWidth - 4, anchor - menuWidth / 2));
    }

    private void renderDeviceMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Device> devices = ClientState.session().devices();
        paintedDevices = devices;
        int menuWidth = 190;
        int rowHeight = 22;
        int menuHeight = Math.max(rowHeight, devices.size() * rowHeight) + 26;
        int menuX = deviceMenuX(menuWidth);
        int menuY = appY + appHeight - PlayerBar.HEIGHT - menuHeight - 6;
        // The box unrolls downward from its title. Whole pixels and no matrix: the menu is laid out
        // in the coordinates the click test uses, and both have to keep agreeing while it opens.
        int drawnHeight = Math.max(1,
                Math.round(menuHeight * (MENU_START_HEIGHT + (1.0f - MENU_START_HEIGHT) * deviceMenuOpenAmount)));
        Theme.roundedBorder(graphics, menuX, menuY, menuWidth, drawnHeight, 6, Theme.BORDER, Theme.ELEVATED);
        Platform.drawString(graphics, font, Lang.plain("gui.devices"), menuX + 10, menuY + 8,
                fadedText(Theme.TEXT_MUTED, deviceMenuOpenAmount), false);
        if (devices.isEmpty()) {
            if (drawnHeight >= 34) {
                // Held back until the box is tall enough to hold the line, for want of a clip.
                Platform.drawString(graphics, font, Lang.plain("gui.no_device"), menuX + 10, menuY + 26,
                        fadedText(Theme.TEXT_DIM, deviceMenuOpenAmount), false);
            }
            return;
        }
        for (int index = 0; index < devices.size(); index++) {
            Device device = devices.get(index);
            int rowY = menuY + 24 + index * rowHeight;
            if (rowY + rowHeight - 2 > menuY + drawnHeight) {
                // Still behind the edge the box has not reached yet, and there is no clip to hide it.
                continue;
            }
            float arrived = Anim.entry(deviceMenuOpenedMillis, index);
            if (arrived <= 0.0f) {
                // Its turn in the stagger has not come round yet.
                continue;
            }
            int lift = Math.round((1.0f - arrived) * 4.0f);
            boolean hovered = Theme.hovered(mouseX, mouseY, menuX + 4, rowY, menuWidth - 8, rowHeight - 2);
            float fade = Anim.hover("device.row:" + index, hovered) * arrived;
            int press = Anim.pressInset("device.row:" + index, 1);
            if (fade > 0.01f) {
                Theme.rounded(graphics, menuX + 4 + press, rowY + lift + press, menuWidth - 8 - 2 * press,
                        rowHeight - 2 - 2 * press, 4, Anim.fade(Theme.ELEVATED, fade));
            }
            // An entry is a button, not a list row: the icon keeps its centre and gives way with the
            // plate, and the name settles into it rather than being redrawn smaller. The icon counts
            // in its own steps, because an odd size is rounded back to the even one it started from.
            Icons.devices(graphics, menuX + 16, rowY + lift + 10,
                    10 - Anim.iconPress("device.row:" + index),
                    Anim.fade(device.active() ? Theme.GREEN : Theme.TEXT_MUTED, arrived));
            Platform.drawString(graphics, font, Theme.fit(font, device.name(), menuWidth - 46), menuX + 30,
                    rowY + lift + 6 + press, fadedText(device.active() ? Theme.GREEN : Theme.TEXT, arrived), false);
        }
    }

    private List<String> contextEntries() {
        List<String> entries = new ArrayList<>();
        entries.add(Lang.plain("gui.queue"));
        entries.add(Lang.plain("gui.add_to_playlist"));
        if (view == View.DETAIL && !detailOwnedPlaylistId.isEmpty()) {
            entries.add(Lang.plain("gui.remove_from_playlist"));
        }
        return entries;
    }

    private List<MediaItem> ownedPlaylists() {
        List<MediaItem> owned = new ArrayList<>();
        for (MediaItem item : ClientState.session().playlists()) {
            if (item.owned()) {
                owned.add(item);
            }
        }
        return owned;
    }

    private int contextMenuWidth() {
        return contextPickerOpen ? 190 : 160;
    }

    private int contextMenuHeight() {
        int rows = contextPickerOpen
                ? Math.min(8, Math.max(1, ownedPlaylists().size()))
                : contextEntries().size();
        return rows * CONTEXT_ENTRY_HEIGHT + 8;
    }

    private int contextMenuX() {
        return Math.max(appX, Math.min(contextX, appX + appWidth - contextMenuWidth()));
    }

    private int contextMenuY() {
        return Math.max(appY, Math.min(contextY, appY + appHeight - contextMenuHeight()));
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int width = contextMenuWidth();
        int height = contextMenuHeight();
        int x = contextMenuX();
        int y = contextMenuY();
        // Same unrolling as the device menu, and for the same reason kept to whole pixels: the
        // playlist picker below opens a scissor, which no matrix push may be active around.
        int drawnHeight = Math.max(1,
                Math.round(height * (MENU_START_HEIGHT + (1.0f - MENU_START_HEIGHT) * contextMenuOpenAmount)));
        Theme.roundedBorder(graphics, x, y, width, drawnHeight, 6, Theme.BORDER, Theme.SURFACE_HOVER);

        if (!contextPickerOpen) {
            List<String> entries = contextEntries();
            for (int index = 0; index < entries.size(); index++) {
                int rowY = y + 4 + index * CONTEXT_ENTRY_HEIGHT;
                if (rowY + CONTEXT_ENTRY_HEIGHT > y + drawnHeight) {
                    continue;
                }
                float arrived = Anim.entry(contextOpenedMillis, index);
                int lift = Math.round((1.0f - arrived) * 4.0f);
                boolean hovered = Theme.hovered(mouseX, mouseY, x + 3, rowY, width - 6, CONTEXT_ENTRY_HEIGHT);
                float fade = Anim.hover("ctx.row:" + index, hovered) * arrived;
                int press = Anim.pressInset("ctx.row:" + index, 1);
                if (fade > 0.01f) {
                    Theme.rounded(graphics, x + 3 + press, rowY + lift + press, width - 6 - 2 * press,
                            CONTEXT_ENTRY_HEIGHT - 2 * press, 4, Anim.fade(Theme.ELEVATED, fade));
                }
                Platform.drawString(graphics, font, entries.get(index), x + 10, rowY + lift + 5 + press,
                        fadedText(Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, fade), arrived), false);
            }
            return;
        }

        List<MediaItem> owned = ownedPlaylists();
        int viewHeight = height - 8;
        contextScroll.setViewportHeight(viewHeight);
        contextScroll.setContentHeight(owned.size() * CONTEXT_ENTRY_HEIGHT);
        // The clip follows the growing box so no half drawn row hangs out of it. Nothing below may
        // push a matrix: the clip is in the coordinates the menu was laid out in and would not
        // follow one - which is why these rows fade and slide but do not plop. Picking a playlist
        // closes the menu on the spot anyway, so there would be nothing left to see it on.
        int clipBottom = Math.max(y + 4, Math.min(y + 4 + viewHeight, y + drawnHeight - 4));
        Theme.scissor(graphics, x, y + 4, x + width, clipBottom);
        if (owned.isEmpty()) {
            Platform.drawString(graphics, font, Lang.plain("gui.empty"), x + 10, y + 9, Theme.TEXT_DIM, false);
        }
        int offset = contextScroll.offset();
        for (int index = 0; index < owned.size(); index++) {
            int rowY = y + 4 + index * CONTEXT_ENTRY_HEIGHT - offset;
            if (rowY + CONTEXT_ENTRY_HEIGHT < y || rowY > y + height) {
                continue;
            }
            float arrived = Anim.entry(contextOpenedMillis, index);
            int lift = Math.round((1.0f - arrived) * 4.0f);
            boolean hovered = Theme.hovered(mouseX, mouseY, x + 3, rowY, width - 6, CONTEXT_ENTRY_HEIGHT)
                    && mouseY >= y + 4 && mouseY < y + 4 + viewHeight
                    && !contextScroll.claims(mouseX, mouseY);
            float fade = Anim.hover("ctx.pick:" + index, hovered) * arrived;
            if (fade > 0.01f) {
                Theme.rounded(graphics, x + 3, rowY + lift, width - 6, CONTEXT_ENTRY_HEIGHT, 4,
                        Anim.fade(Theme.ELEVATED, fade));
            }
            Platform.drawString(graphics, font, Theme.fit(font, owned.get(index).name(), width - 20), x + 10,
                    rowY + lift + 5, fadedText(Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, fade), arrived), false);
        }
        Theme.endScissor(graphics);
        contextScroll.renderScrollbar(graphics, x + width - 6, y + 4, viewHeight);
    }

    private boolean handleContextClick(double mouseX, double mouseY) {
        int width = contextMenuWidth();
        int height = contextMenuHeight();
        int x = contextMenuX();
        int y = contextMenuY();
        if (!Theme.hovered((int) mouseX, (int) mouseY, x, y, width, height)) {
            contextOpen = false;
            contextPickerOpen = false;
            return false;
        }
        SpotifySession session = ClientState.session();
        Track track = contextTrack;
        if (!contextPickerOpen) {
            List<String> entries = contextEntries();
            int index = Math.floorDiv((int) mouseY - y - 4, CONTEXT_ENTRY_HEIGHT);
            if (index < 0 || index >= entries.size()) {
                return true;
            }
            Anim.click("ctx.row:" + index);
            switch (index) {
                case 0 -> {
                    session.run(() -> session.api().queue(track.uri()));
                    ClientState.toast(track.title());
                    contextOpen = false;
                }
                case 1 -> {
                    contextPickerOpen = true;
                    // The picker is a second list in the same box, so it gets its own arrival.
                    contextOpenedMillis = System.currentTimeMillis();
                    contextScroll.reset();
                }
                default -> {
                    String playlistId = detailOwnedPlaylistId;
                    session.run(() -> {
                        if (session.api().removeFromPlaylist(playlistId, track.uri())) {
                            Minecraft.getInstance().execute(() -> {
                                // Spotify drops every occurrence of the uri, so a track listed twice
                                // takes two rows. Counting one would leave the loaded count and the
                                // offset disagreeing, and reordering refuses itself from then on.
                                int removed = trackList.removeTrack(track.uri());
                                detailTotal = Math.max(0, detailTotal - removed);
                                detailOffset = Math.max(0, detailOffset - removed);
                            });
                        }
                    });
                    ClientState.toast(track.title());
                    contextOpen = false;
                }
            }
            return true;
        }

        List<MediaItem> owned = ownedPlaylists();
        if ((int) mouseY < y + 4 || (int) mouseY >= y + height - 4) {
            return true;
        }
        int index = Math.floorDiv((int) mouseY - y - 4 + contextScroll.offset(), CONTEXT_ENTRY_HEIGHT);
        if (index >= 0 && index < owned.size()) {
            MediaItem playlist = owned.get(index);
            session.run(() -> session.api().addToPlaylist(playlist.id(), track.uri()));
            ClientState.toast(playlist.name());
            contextOpen = false;
            contextPickerOpen = false;
        }
        return true;
    }

    private void createPlaylist() {
        String name = newPlaylistBox == null ? "" : newPlaylistBox.getValue().trim();
        if (name.isEmpty()) {
            creatingPlaylist = false;
            return;
        }
        SpotifySession session = ClientState.session();
        session.run(() -> {
            if (session.api().createPlaylist(name, false) != null) {
                session.refreshLibrary();
            }
        });
        ClientState.toast(name);
        creatingPlaylist = false;
        newPlaylistBox.setValue("");
        newPlaylistBox.setFocused(false);
    }

    private void renderToast(GuiGraphics graphics) {
        // The timed toast wins: it carries errors and the throttle warning and is gone after a
        // few seconds anyway, while the starting notice may stand for a minute and a half.
        String toast = ClientState.toast();
        if (toast.isEmpty() && ClientState.session().startingSpotify()) {
            toast = Lang.plain("device.launching");
        }
        if (!toast.isEmpty()) {
            // Held on to, because the box outlives the message it carries by the length of its own
            // way out.
            fadingToast = toast;
        }
        float shown = Anim.to("toast", toast.isEmpty() ? 0.0f : 1.0f, Anim.HOVER);
        if (shown <= 0.05f || fadingToast.isEmpty()) {
            return;
        }
        int width = font.width(fadingToast) + 24;
        int x = appX + (appWidth - width) / 2;
        int y = appY + appHeight - PlayerBar.HEIGHT - 34 + Math.round((1.0f - shown) * 8.0f);
        Theme.rounded(graphics, x, y, width, 22, 11, Anim.fade(Theme.SURFACE_HOVER, shown));
        Platform.drawString(graphics, font, fadingToast, x + 12, y + 7, fadedText(Theme.TEXT, shown), false);
    }

    private void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        String tooltip = playerBar.tooltip(mouseX, mouseY);
        if (tooltip.isEmpty()) {
            return;
        }
        int width = font.width(tooltip) + 10;
        Theme.rounded(graphics, mouseX + 8, mouseY - 16, width, 14, 3, Theme.BLACK);
        Platform.drawString(graphics, font, tooltip, mouseX + 13, mouseY - 13, Theme.TEXT, false);
    }

    public boolean onMouseClick(double rawMouseX, double rawMouseY, int button) {
        // Quantized exactly the way render() does it, and not merely divided. Drawing rounds the
        // scaled cursor while every hit test floors it, so at an app scale that is not a whole
        // number - the default of 2 inside a game running at 3 or 4 - the two disagree by an overlay
        // pixel. On the outermost pixel of something as small as the heart in a track row that meant
        // the icon lit up under the cursor while the click went past it and started the song.
        double mouseX = Math.round((int) rawMouseX / scale);
        double mouseY = Math.round((int) rawMouseY / scale);
        if (contextOpen && contextPickerOpen && contextScroll.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        if (contextOpen && handleContextClick(mouseX, mouseY)) {
            return true;
        }
        if (deviceMenuOpen) {
            if (!handleDeviceMenuClick(mouseX, mouseY)) {
                deviceMenuOpen = false;
            }
            return true;
        }
        if (sidebar.scroll().mouseClicked(mouseX, mouseY)) {
            return true;
        }
        ScrollArea grabbed = activeScroll();
        if (grabbed != null && grabbed.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        if (playerBar.mouseClicked(mouseX, mouseY, () -> {
            ClientState.session().refreshLibrary();
            deviceMenuOpen = true;
            deviceMenuOpenedMillis = System.currentTimeMillis();
        }, () -> navigate(View.QUEUE), this::openArtist)) {
            return true;
        }
        if (sidebar.mouseClicked(mouseX, mouseY, appX, appY, appHeight - PlayerBar.HEIGHT,
                this::navigate, this::openLiked, this::openDetail)) {
            return true;
        }
        // Same condition as renderContent: whatever the notice covers must not be clickable,
        // while the connect button and the settings link on the notice itself stay live.
        if (showsConnectPrompt() && view != View.SETTINGS && view != View.PARTY) {
            int buttonWidth = 150;
            int buttonX = contentX + (contentWidth - buttonWidth) / 2;
            int centerY = contentY + contentHeight / 2;
            if (Theme.hovered((int) mouseX, (int) mouseY, buttonX, centerY + 26, buttonWidth, 24)) {
                Anim.click("prompt.connect");
                if (ConfigManager.client().spotifyClientId.isBlank()) {
                    navigate(View.SETTINGS);
                } else {
                    ClientState.session().login();
                }
                return true;
            }
            String settingsHint = Lang.plain("gui.settings");
            int hintWidth = font.width(settingsHint);
            int hintX = contentX + (contentWidth - hintWidth) / 2;
            if (Theme.hovered((int) mouseX, (int) mouseY, hintX - 6, centerY + 54, hintWidth + 12, 14)) {
                Anim.click("prompt.settings");
                navigate(View.SETTINGS);
                return true;
            }
            return true;
        }
        int innerX = contentX + 14;
        int innerWidth = contentWidth - 28;
        return switch (view) {
            case HOME -> clickHome(mouseX, mouseY, button, innerX, innerWidth);
            case SEARCH -> clickSearch(mouseX, mouseY, button, innerX, innerWidth);
            case LIBRARY -> clickLibrary(mouseX, mouseY, button, innerX, innerWidth);
            case DETAIL -> clickDetail(mouseX, mouseY, button, innerX, innerWidth);
            case QUEUE -> clickQueue(mouseX, mouseY, button, innerX, innerWidth);
            case PARTY -> partyPanel.mouseClicked(mouseX, mouseY);
            case SETTINGS -> settingsPanel.mouseClicked(mouseX, mouseY, button);
        };
    }

    private void navigate(View target) {
        markViewChanged();
        if (target != View.DETAIL) {
            stopListing();
        }
        if (target == View.SEARCH || target == View.QUEUE) {
            trackList.setTracks(List.of());
        }
        view = target;
        if (target == View.QUEUE) {
            loadQueue();
        }
        if (target != View.LIBRARY) {
            creatingPlaylist = false;
            if (newPlaylistBox != null) {
                newPlaylistBox.setFocused(false);
            }
        }
        if (target == View.HOME) {
            loadHome();
        }
        if (target == View.SEARCH && searchBox != null) {
            searchBox.setFocused(true);
        }
    }

    private boolean clickHome(double mouseX, double mouseY, int button, int innerX, int innerWidth) {
        String[] labels = {Lang.plain("gui.recently_played"), Lang.plain("gui.top_tracks")};
        int tab = tabAt(mouseX, mouseY, innerX, contentY + 12, labels);
        if (tab >= 0) {
            Anim.click(tabStrip(labels.length) + tab);
            homeTab = tab;
            loadHome();
            return true;
        }
        int bodyY = contentY + HEADER_HEIGHT + 4;
        return trackList.mouseClicked(mouseX, mouseY, button, innerX, bodyY, innerWidth,
                contentY + contentHeight - bodyY - 8, currentTrackHandler());
    }

    private boolean clickSearch(double mouseX, double mouseY, int button, int innerX, int innerWidth) {
        if (searchBox != null && Theme.hovered((int) mouseX, (int) mouseY, innerX, contentY + 8,
                Math.min(340, innerWidth), 22)) {
            Platform.click(searchBox, mouseX, mouseY, button);
            searchBox.setFocused(true);
            return true;
        }
        int tab = tabAt(mouseX, mouseY, innerX, contentY + 38, SEARCH_TABS);
        if (tab >= 0) {
            Anim.click(tabStrip(SEARCH_TABS.length) + tab);
            searchTab = tab;
            applySearchTab(ClientState.session().searchResults());
            return true;
        }
        int bodyY = contentY + 60;
        int bodyHeight = contentY + contentHeight - bodyY - 8;
        if (searchTab == 0) {
            return trackList.mouseClicked(mouseX, mouseY, button, innerX, bodyY, innerWidth, bodyHeight,
                    currentTrackHandler());
        }
        return cardGrid.mouseClicked(mouseX, mouseY, innerX, bodyY, innerWidth, bodyHeight, this::openDetail);
    }

    private boolean clickLibrary(double mouseX, double mouseY, int button, int innerX, int innerWidth) {
        int buttonX = innerX + innerWidth - 118;
        if (Theme.hovered((int) mouseX, (int) mouseY, buttonX, contentY + 8, 118, 20)) {
            Anim.click("lib.create");
            creatingPlaylist = !creatingPlaylist;
            if (creatingPlaylist && newPlaylistBox != null) {
                newPlaylistBox.setFocused(true);
            }
            return true;
        }
        int bodyY = contentY + HEADER_HEIGHT + 4;
        if (creatingPlaylist && newPlaylistBox != null) {
            if (Theme.hovered((int) mouseX, (int) mouseY, innerX, bodyY, Math.min(260, innerWidth), 22)) {
                Platform.click(newPlaylistBox, mouseX, mouseY, button);
                newPlaylistBox.setFocused(true);
                return true;
            }
            bodyY += 28;
        }
        return cardGrid.mouseClicked(mouseX, mouseY, innerX, bodyY, innerWidth,
                contentY + contentHeight - bodyY - 8, this::openDetail);
    }

    private boolean clickDetail(double mouseX, double mouseY, int button, int innerX, int innerWidth) {
        int textX = innerX + 86;
        if (Theme.hovered((int) mouseX, (int) mouseY, textX, contentY + 62, 26, 26)) {
            Anim.click("detail.play");
            if (!trackList.tracks().isEmpty()) {
                playFrom(0, detailContextUri);
            }
            return true;
        }
        int bodyY = contentY + 96;
        return trackList.mouseClicked(mouseX, mouseY, button, innerX, bodyY, innerWidth,
                contentY + contentHeight - bodyY - 8, currentTrackHandler());
    }

    private TrackListView.Handler trackHandler(String contextUri) {
        return new TrackListView.Handler() {
            @Override
            public void play(int index, Track track) {
                playFrom(index, contextUri);
            }

            @Override
            public void toggleLike(Track track) {
                ClientState.session().toggleLiked(track);
            }

            @Override
            public void context(Track track, double mouseX, double mouseY) {
                contextTrack = track;
                contextX = (int) mouseX;
                contextY = (int) mouseY;
                contextOpen = true;
                contextPickerOpen = false;
                contextOpenedMillis = System.currentTimeMillis();
                contextScroll.reset();
            }

            @Override
            public void openArtist(String artistId, String name) {
                SpotifyOverlay.this.openArtist(artistId, name);
            }

            @Override
            public boolean reorderable() {
                return canReorderListing();
            }

            @Override
            public void reorder(int from, int to) {
                moveInPlaylist(from, to);
            }

            @Override
            public void reorderRefused() {
                // Only where rearranging is a reasonable thing to have expected. Pulling a row about
                // in search results or on the home page was never going to move anything, and
                // saying so would be answering a question nobody asked.
                if (view == View.QUEUE) {
                    ClientState.toast(Lang.plain("gui.queue_fixed"));
                } else if (view == View.DETAIL && detailOwnedPlaylistId.isEmpty() && !detailLiked) {
                    ClientState.toast(Lang.plain("gui.reorder_only_own"));
                }
            }
        };
    }

    /** The handler the current page needs, so a release can act on the same listing a press did. */
    private TrackListView.Handler currentTrackHandler() {
        return trackHandler(view == View.DETAIL ? detailContextUri : "");
    }

    /**
      * Opens an artist from a name clicked in a track row. The page itself already exists - it is
      * what a search result opens - but a track only carries the artist's id, so the name and the
      * picture for the header have to be fetched before it can be shown.
      */
    private void openArtist(String artistId, String name) {
        if (artistId.isEmpty()) {
            return;
        }
        SpotifySession session = ClientState.session();
        // Every page change bumps this, so it doubles as a note of where the user was standing when
        // they asked. A lookup that comes back after they have moved on has been overtaken and must
        // not drag them back.
        int request = detailRequestId;
        session.run(() -> {
            MediaItem artist = session.api().artist(artistId);
            // Falling back to the name from the track keeps the click working when the lookup
            // fails; the listing under the header is fetched separately anyway.
            MediaItem opened = artist != null ? artist
                    : new MediaItem(MediaItem.Kind.ARTIST, "spotify:artist:" + artistId, artistId, name, "", "",
                            0, false);
            Minecraft.getInstance().execute(() -> {
                if (request == detailRequestId) {
                    openDetail(opened);
                }
            });
        });
    }

    /**
      * Whether the listing on screen may be rearranged.
      *
      * <p>Two conditions, and the second is the subtle one. Positions sent to Spotify count every
      * entry of the playlist, while the list on screen has had the unusable ones dropped - local
      * files, titles taken down since. {@code detailOffset} counts what Spotify handed over and
      * {@code loadedCount} what survived, so while those agree nothing has been dropped and the two
      * numberings are the same. If they ever differ a drag would move the wrong song, and refusing
      * is the only safe answer.
      */
    private boolean canReorderListing() {
        return view == View.DETAIL && !detailOwnedPlaylistId.isEmpty()
                && detailOffset == trackList.loadedCount();
    }

    /**
      * Sends a move the list has already made. On failure the page is opened again rather than
      * quietly left showing an order Spotify does not have.
      */
    private void moveInPlaylist(int from, int to) {
        String playlistId = detailOwnedPlaylistId;
        MediaItem item = detailItem;
        SpotifySession session = ClientState.session();
        session.run(() -> {
            if (session.api().reorderPlaylist(playlistId, from, to)) {
                return;
            }
            String error = session.api().lastError();
            Minecraft.getInstance().execute(() -> {
                ClientState.toast(error.isEmpty() ? Lang.plain("gui.reorder_failed")
                        : ClientState.describeApiError(error));
                if (view == View.DETAIL && playlistId.equals(detailOwnedPlaylistId) && item != null) {
                    openDetail(item);
                }
            });
        });
    }

    private void playFrom(int index, String context) {
        SpotifySession session = ClientState.session();
        List<Track> tracks = trackList.tracks();
        if (index < 0 || index >= tracks.size()) {
            return;
        }
        if (!context.isEmpty()) {
            String startUri = tracks.get(index).uri();
            session.withDevice(deviceId -> session.api().play(context, List.of(), startUri, deviceId));
            return;
        }
        List<String> uris = new ArrayList<>();
        for (int i = index; i < tracks.size() && uris.size() < 50; i++) {
            uris.add(tracks.get(i).uri());
        }
        session.withDevice(deviceId -> session.api().play("", uris, "", deviceId));
    }

    private boolean handleDeviceMenuClick(double mouseX, double mouseY) {
        List<Device> devices = paintedDevices;
        int menuWidth = 190;
        int rowHeight = 22;
        int menuHeight = Math.max(rowHeight, devices.size() * rowHeight) + 26;
        int menuX = deviceMenuX(menuWidth);
        int menuY = appY + appHeight - PlayerBar.HEIGHT - menuHeight - 6;
        if (!Theme.hovered((int) mouseX, (int) mouseY, menuX, menuY, menuWidth, menuHeight)) {
            return false;
        }
        for (int index = 0; index < devices.size(); index++) {
            int rowY = menuY + 24 + index * rowHeight;
            if (Theme.hovered((int) mouseX, (int) mouseY, menuX + 4, rowY, menuWidth - 8, rowHeight - 2)) {
                Anim.click("device.row:" + index);
                Device device = devices.get(index);
                SpotifySession session = ClientState.session();
                session.runThenPoll(() -> session.api().transfer(device.id(), true));
                deviceMenuOpen = false;
                return true;
            }
        }
        return true;
    }

    private int tabAt(double mouseX, double mouseY, int x, int y, String[] labels) {
        int cursor = x;
        for (int i = 0; i < labels.length; i++) {
            int width = font.width(labels[i]) + 20;
            if (Theme.hovered((int) mouseX, (int) mouseY, cursor, y, width, 18)) {
                return i;
            }
            cursor += width + 6;
        }
        return -1;
    }

    public boolean onMouseDrag(double rawMouseX, double rawMouseY, int button) {
        double mouseX = rawMouseX / scale;
        double mouseY = rawMouseY / scale;
        if (contextScroll.mouseDragged(mouseY) || sidebar.scroll().mouseDragged(mouseY)) {
            return true;
        }
        ScrollArea active = activeScroll();
        if (active != null && active.mouseDragged(mouseY)) {
            return true;
        }
        // After the scrollbars, so grabbing one keeps winning over the row it happens to cross.
        // Left button only: a row is picked up with the left button and nothing else may carry it.
        if (button == 0 && trackList.mouseDragged(mouseY, currentTrackHandler())) {
            return true;
        }
        if (playerBar.mouseDragged(mouseX)) {
            return true;
        }
        return view == View.SETTINGS && settingsPanel.mouseDragged(mouseX);
    }

    /** The scroll area the current view shows a bar for, or null if it shows none. */
    private ScrollArea activeScroll() {
        return switch (view) {
            case PARTY -> partyPanel.scroll();
            case SETTINGS -> settingsPanel.scroll();
            case LIBRARY -> cardGrid.scroll();
            case SEARCH -> searchTab == 0 ? trackList.scroll() : cardGrid.scroll();
            default -> trackList.scroll();
        };
    }

    /**
      * {@code button} is the one being let go, which is not necessarily the one that started
      * anything. Minecraft reports a release per button, so a right click while the left button is
      * still down on a row arrives here as a release too - and that release must not be the one the
      * row acts on, or it would play a song, or commit a drag to Spotify, that was never let go.
      */
    public void onMouseRelease(int button) {
        // A row plays on the release, not on the press, because that same press may still have been
        // the beginning of a drag. This is where the two are told apart.
        if (button == 0) {
            trackList.mouseReleased(currentTrackHandler());
        }
        playerBar.mouseReleased();
        settingsPanel.mouseReleased();
        contextScroll.mouseReleased();
        sidebar.scroll().mouseReleased();
        ScrollArea active = activeScroll();
        if (active != null) {
            active.mouseReleased();
        }
    }

    public boolean onMouseScroll(double rawMouseX, double rawMouseY, double scrollY) {
        double mouseX = rawMouseX / scale;
        double mouseY = rawMouseY / scale;
        if (contextOpen && contextPickerOpen) {
            return contextScroll.scrollBy(scrollY);
        }
        if (sidebar.mouseScrolled(mouseX, mouseY, scrollY, appX)) {
            return true;
        }
        return switch (view) {
            case PARTY -> partyPanel.mouseScrolled(scrollY);
            case SETTINGS -> settingsPanel.mouseScrolled(scrollY);
            case LIBRARY -> cardGrid.mouseScrolled(scrollY);
            case SEARCH -> searchTab == 0 ? trackList.mouseScrolled(scrollY) : cardGrid.mouseScrolled(scrollY);
            default -> trackList.mouseScrolled(scrollY);
        };
    }

    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        if (contextOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            contextOpen = false;
            contextPickerOpen = false;
            return true;
        }
        if (view == View.LIBRARY && creatingPlaylist && newPlaylistBox != null && newPlaylistBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                createPlaylist();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                creatingPlaylist = false;
                newPlaylistBox.setFocused(false);
                return true;
            }
            if (Platform.keyPress(newPlaylistBox, keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        if (view == View.SETTINGS && settingsPanel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (view == View.SEARCH && searchBox != null && searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setFocused(false);
                return true;
            }
            if (Platform.keyPress(searchBox, keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE && !typing()) {
            ClientState.session().togglePlayback();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F && !typing() && Platform.controlDown()) {
            navigate(View.SEARCH);
            return true;
        }
        return false;
    }

    public boolean onCharTyped(int codepoint, int modifiers) {
        if (view == View.LIBRARY && creatingPlaylist && newPlaylistBox != null && newPlaylistBox.isFocused()
                && Platform.charTyped(newPlaylistBox, codepoint, modifiers)) {
            return true;
        }
        if (view == View.SETTINGS && settingsPanel.charTyped(codepoint, modifiers)) {
            return true;
        }
        if (view == View.SEARCH && searchBox != null && searchBox.isFocused()
                && Platform.charTyped(searchBox, codepoint, modifiers)) {
            return true;
        }
        return false;
    }

    public GuiEventListener focusedWidget() {
        if (view == View.LIBRARY && creatingPlaylist && newPlaylistBox != null && newPlaylistBox.isFocused()) {
            return newPlaylistBox;
        }
        if (view == View.SEARCH && searchBox != null && searchBox.isFocused()) {
            return searchBox;
        }
        if (view == View.SETTINGS) {
            return settingsPanel.focusedBox();
        }
        return null;
    }

    private boolean typing() {
        if (view == View.LIBRARY && creatingPlaylist && newPlaylistBox != null && newPlaylistBox.isFocused()) {
            return true;
        }
        if (view == View.SETTINGS) {
            return settingsPanel.textFieldFocused();
        }
        return view == View.SEARCH && searchBox != null && searchBox.isFocused();
    }

    public void onClosed() {
        ConfigManager.saveClient();
    }
}
