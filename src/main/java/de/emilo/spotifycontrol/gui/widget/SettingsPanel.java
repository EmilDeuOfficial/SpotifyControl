package de.emilo.spotifycontrol.gui.widget;

import de.emilo.spotifycontrol.client.AlbumArtCache;
import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.client.KeyBindings;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.gui.Anim;
import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.config.ClientConfig;
import de.emilo.spotifycontrol.lyrics.LyricsAnimation;
import de.emilo.spotifycontrol.lyrics.LyricsColor;
import de.emilo.spotifycontrol.lyrics.LyricsFont;
import de.emilo.spotifycontrol.spotify.SpotifyAuth;
import de.emilo.spotifycontrol.spotify.SpotifySession;
import de.emilo.spotifycontrol.spotify.model.Device;
import de.emilo.spotifycontrol.voice.AudioDevices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import de.emilo.spotifycontrol.platform.Platform;

public class SettingsPanel {

    private static final int ROW_HEIGHT = 26;

    private static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";

    /** Distance from the panel top to the client id box when the three step guide is shown. */
    private static final int BOXES_OFFSET = 74;

    /** Distance without the guide, used when the overlay is too short to spare the room. */
    private static final int BOXES_OFFSET_COMPACT = 32;

    /** Below this panel height the guide is dropped so the row list keeps its space. */
    private static final int GUIDE_MIN_HEIGHT = 250;

    /** Width of the value printed beside a slider, reserved so it cannot be clipped away. */
    private static final int SLIDER_VALUE_WIDTH = 48;

    private static final int CONTROL_WIDTH = 160;
    private static final String[] LANGUAGES = {"en", "de", "ru", "es", "fr", "ja", "zh"};
    private static final String[] CORNERS = {"TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"};
    private static final String[] MODIFIERS = {"CONTROL", "SHIFT", "ALT", "NONE"};
    private static final String[] START_VIEWS = {"HOME", "SEARCH", "LIBRARY", "LIKED"};
    private static final String[] KEEP_MODES = {"LINE", "COUNT", "SECONDS"};

    /** A list of choices takes this many ordinary rows, so the rows around it keep their rhythm. */
    private static final int PICKER_SPAN = 5;
    /** The list of fonts is longer, and its names wider, than the one of animations. */
    private static final int FONT_PICKER_SPAN = 7;
    private static final int CHIP_HEIGHT = 17;
    /** A colour field takes this many ordinary rows. */
    private static final int COLOR_SPAN = 4;
    private static final int COLOR_FIELD_WIDTH = 120;
    private static final int COLOR_BAR_WIDTH = 14;

    /** The wiki page that explains how to create the Spotify app and where its client ID is. */
    private static final String HELP_URL =
            "https://github.com/EmilDeuOfficial/SpotifyControl/wiki/Installation#creating-the-spotify-app";
    private static final String HELP_LABEL = "? Help";

    private interface Row {
        String label();
    }

    private record ToggleRow(String label, BooleanSupplier get, Consumer<Boolean> set) implements Row {
    }

    private record CycleRow(String label, Supplier<String> get, Runnable next) implements Row {
    }

    /** What a list of choices lets the user pick. */
    private enum PickerKind {
        IN_ANIMATION, OUT_ANIMATION, FONT, COLOR
    }

    private static int spanOf(PickerKind kind) {
        return kind == PickerKind.FONT ? FONT_PICKER_SPAN : PICKER_SPAN;
    }

    /** How many ordinary rows a row covers: a list of choices and a colour field are taller than one. */
    private static int spanOfRow(Row row) {
        if (row instanceof PickerRow listed) {
            return spanOf(listed.kind());
        }
        return row instanceof ColorRow ? COLOR_SPAN : 1;
    }

    /**
     * A field to pick a colour in, a square for saturation and brightness beside a bar for the hue.
     * {@code which} is the colour of the setting it edits, 0 for the first and 1 for the second.
     * It covers {@link #COLOR_SPAN} rows, the first of which is this one and the rest
     * {@link PickerFiller}s.
     */
    private record ColorRow(String label, int which) implements Row {
    }

    /**
     * A list of choices with a preview. It is drawn over the rows it takes, see {@link #spanOf},
     * the first of which is this one and the rest {@link PickerFiller}s that only redirect clicks.
     */
    private record PickerRow(String label, PickerKind kind, Supplier<String> get, Consumer<String> set)
            implements Row {
    }

    private record PickerFiller(int slot) implements Row {
        @Override
        public String label() {
            return "";
        }
    }

    private record SliderRow(String label, IntSupplier get, IntConsumer set, int min, int max, int step,
                             String suffix) implements Row {
    }

    private record ActionRow(String label, Supplier<String> button, Runnable action) implements Row {
    }

    private record InfoRow(String label, Supplier<String> value) implements Row {
    }

    /** A section heading. Carries no control and swallows clicks. */
    private record HeaderRow(String label) implements Row {
    }

    /** Shows a value and puts it on the clipboard when the row is clicked. */
    private record CopyRow(String label, Supplier<String> value) implements Row {
    }

    private final ScrollArea scroll = new ScrollArea();

    public ScrollArea scroll() {
        return scroll;
    }
    private final List<Row> rows = new ArrayList<>();
    private EditBox clientIdBox;
    private EditBox portBox;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int rowsY;
    private int boxesY;
    private int draggingSlider = -1;
    /** The row of the colour field that is being dragged in, and which part of it: 0 square, 1 hue bar. */
    private int draggingColor = -1;
    private int draggingColorPart;
    private boolean capturingKey;

    public SettingsPanel() {
        rows.add(new HeaderRow("Account"));
        rows.add(new ActionRow("Spotify account",
                () -> ClientState.session().connected()
                        ? Lang.plain("gui.disconnect")
                        : Lang.plain("gui.connect"),
                () -> {
                    if (ClientState.session().connected()) {
                        ClientState.session().logout();
                    } else {
                        ClientState.session().login();
                    }
                }));
        rows.add(new InfoRow("Plan", SettingsPanel::planLabel));
        rows.add(new CopyRow("Redirect URI", () -> SpotifyAuth.redirectUri(ConfigManager.client().callbackPort)));
        rows.add(new CopyRow("Developer dashboard", () -> DASHBOARD_URL));
        rows.add(new ActionRow("Open in browser", () -> Lang.plain("gui.open"),
                () -> Platform.openUri(DASHBOARD_URL)));

        rows.add(new HeaderRow("App"));
        rows.add(new ActionRow("App key",
                () -> capturingKey ? Lang.plain("gui.press_a_key") : KeyBindings.openCombination(),
                () -> capturingKey = true));
        rows.add(new CycleRow("Modifier key", () -> ConfigManager.client().modifierKey,
                () -> {
                    ConfigManager.client().modifierKey = cycle(MODIFIERS, ConfigManager.client().modifierKey);
                    ConfigManager.client().requireModifierKey =
                            !"NONE".equals(ConfigManager.client().modifierKey);
                    ConfigManager.saveClient();
                }));
        rows.add(new CycleRow("Language", () -> languageLabel(ConfigManager.client().language),
                () -> {
                    ConfigManager.client().language = cycle(LANGUAGES, ConfigManager.client().language);
                    Lang.load(ConfigManager.client().language);
                    ConfigManager.saveClient();
                }));
        rows.add(new CycleRow("App scale", () -> overlayScaleLabel(ConfigManager.client().overlayGuiScale),
                () -> {
                    ConfigManager.client().overlayGuiScale = (ConfigManager.client().overlayGuiScale + 1) % 4;
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Screen margin", () -> ConfigManager.client().appMargin,
                value -> {
                    ConfigManager.client().appMargin = value;
                    ConfigManager.saveClient();
                }, 0, 40, 2, " px"));
        rows.add(new CycleRow("Start page", () -> startViewLabel(ConfigManager.client().startView),
                () -> {
                    ConfigManager.client().startView = cycle(START_VIEWS, ConfigManager.client().startView);
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Background dim", () -> ConfigManager.client().backgroundDim,
                value -> {
                    ConfigManager.client().backgroundDim = value;
                    ConfigManager.saveClient();
                }, 0, 100, 5, "%"));
        rows.add(new ToggleRow("Blur the game behind", () -> ConfigManager.client().blurBackground,
                value -> {
                    ConfigManager.client().blurBackground = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Pause singleplayer while open", () -> ConfigManager.client().pauseGameOnOverlay,
                value -> {
                    ConfigManager.client().pauseGameOnOverlay = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Poll interval", () -> ConfigManager.client().playbackPollMillis,
                value -> {
                    ConfigManager.client().playbackPollMillis = value;
                    ConfigManager.saveClient();
                }, 1000, 5000, 50, " ms"));

        rows.add(new HeaderRow("Customization"));
        rows.add(new CycleRow("Accent colour",
                () -> Theme.ACCENT_NAMES[Math.floorMod(ConfigManager.client().accentColor,
                        Theme.ACCENT_NAMES.length)],
                () -> {
                    ConfigManager.client().accentColor =
                            Math.floorMod(ConfigManager.client().accentColor + 1, Theme.ACCENT_NAMES.length);
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Animations", () -> ConfigManager.client().animations,
                value -> {
                    ConfigManager.client().animations = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Smooth graphics", () -> ConfigManager.client().smoothGraphics,
                value -> {
                    ConfigManager.client().smoothGraphics = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Scrolling title", () -> ConfigManager.client().scrollingTitles,
                value -> {
                    ConfigManager.client().scrollingTitles = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Album art", () -> ConfigManager.client().showAlbumArt,
                value -> {
                    ConfigManager.client().showAlbumArt = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Dim unavailable tracks", () -> ConfigManager.client().dimUnavailableTracks,
                value -> {
                    ConfigManager.client().dimUnavailableTracks = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Track time", () -> ConfigManager.client().showTrackTime,
                value -> {
                    ConfigManager.client().showTrackTime = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Shuffle and repeat", () -> ConfigManager.client().showShuffleRepeat,
                value -> {
                    ConfigManager.client().showShuffleRepeat = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Volume slider", () -> ConfigManager.client().showVolumeSlider,
                value -> {
                    ConfigManager.client().showVolumeSlider = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Queue button", () -> ConfigManager.client().showQueueButton,
                value -> {
                    ConfigManager.client().showQueueButton = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Lyrics button", () -> ConfigManager.client().showLyricsButton,
                value -> {
                    ConfigManager.client().showLyricsButton = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Device button", () -> ConfigManager.client().showDeviceButton,
                value -> {
                    ConfigManager.client().showDeviceButton = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Like button", () -> ConfigManager.client().showLikeButton,
                value -> {
                    ConfigManager.client().showLikeButton = value;
                    ConfigManager.saveClient();
                }));

        rows.add(new HeaderRow("Overlay"));
        rows.add(new ToggleRow("Show overlay", () -> ConfigManager.client().showNowPlayingHud,
                value -> {
                    ConfigManager.client().showNowPlayingHud = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new CycleRow("Corner", () -> cornerLabel(ConfigManager.client().hudCorner),
                () -> {
                    ConfigManager.client().hudCorner = cycle(CORNERS, ConfigManager.client().hudCorner);
                    ConfigManager.saveClient();
                }));
        rows.add(new CycleRow("Overlay scale", () -> overlayScaleLabel(ConfigManager.client().hudGuiScale),
                () -> {
                    ConfigManager.client().hudGuiScale = (ConfigManager.client().hudGuiScale + 1) % 4;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Album art", () -> ConfigManager.client().hudShowAlbumArt,
                value -> {
                    ConfigManager.client().hudShowAlbumArt = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Progress bar", () -> ConfigManager.client().hudShowProgress,
                value -> {
                    ConfigManager.client().hudShowProgress = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Artist line", () -> ConfigManager.client().hudShowArtist,
                value -> {
                    ConfigManager.client().hudShowArtist = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Only while playing", () -> ConfigManager.client().hudOnlyWhilePlaying,
                value -> {
                    ConfigManager.client().hudOnlyWhilePlaying = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Screen margin", () -> ConfigManager.client().hudMargin,
                value -> {
                    ConfigManager.client().hudMargin = value;
                    ConfigManager.saveClient();
                }, 0, 80, 2, " px"));

        rows.add(new HeaderRow("Sound"));
        rows.add(new ToggleRow("Mute Minecraft music while listening",
                () -> ConfigManager.client().muteMinecraftMusicWhileListening,
                value -> {
                    ConfigManager.client().muteMinecraftMusicWhileListening = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Volume step", () -> ConfigManager.client().overlayVolumeStep,
                value -> {
                    ConfigManager.client().overlayVolumeStep = value;
                    ConfigManager.saveClient();
                }, 1, 25, 1, "%"));
        rows.add(new CycleRow("Audio capture device", SettingsPanel::captureDeviceLabel,
                SettingsPanel::cycleCaptureDevice));
        rows.add(new SliderRow("Capture volume", () -> ConfigManager.client().captureVolumePercent,
                value -> {
                    ConfigManager.client().captureVolumePercent = value;
                    ConfigManager.saveClient();
                }, 0, 200, 5, "%"));

        rows.add(new HeaderRow("Lyrics"));
        rows.add(new ToggleRow("Lyrics in the world", () -> ConfigManager.client().worldLyrics,
                value -> {
                    ConfigManager.client().worldLyrics = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Words per block", () -> ConfigManager.client().lyricsWordsPerPiece,
                value -> {
                    ConfigManager.client().lyricsWordsPerPiece = value;
                    ConfigManager.saveClient();
                }, 1, 6, 1, ""));
        rows.add(new SliderRow("Nearest distance", () -> ConfigManager.client().lyricsMinDistance,
                value -> {
                    ConfigManager.client().lyricsMinDistance = value;
                    ConfigManager.client().lyricsMaxDistance =
                            Math.max(value, ConfigManager.client().lyricsMaxDistance);
                    ConfigManager.saveClient();
                }, 2, 30, 1, " blocks"));
        rows.add(new SliderRow("Farthest distance", () -> ConfigManager.client().lyricsMaxDistance,
                value -> {
                    ConfigManager.client().lyricsMaxDistance = value;
                    ConfigManager.client().lyricsMinDistance =
                            Math.min(value, ConfigManager.client().lyricsMinDistance);
                    ConfigManager.saveClient();
                }, 2, 30, 1, " blocks"));
        rows.add(new SliderRow("Sprint distance bonus", () -> ConfigManager.client().lyricsSprintExtraDistance,
                value -> {
                    ConfigManager.client().lyricsSprintExtraDistance = value;
                    ConfigManager.saveClient();
                }, 0, 30, 1, " blocks"));
        rows.add(new ToggleRow("Tilted words", () -> ConfigManager.client().lyricsTilt,
                value -> {
                    ConfigManager.client().lyricsTilt = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Text size", () -> ConfigManager.client().lyricsTextSize,
                value -> {
                    ConfigManager.client().lyricsTextSize = value;
                    ConfigManager.saveClient();
                }, 25, 300, 5, "%"));
        addPicker("Lyrics font", PickerKind.FONT, () -> ConfigManager.client().lyricsFont,
                value -> {
                    ConfigManager.client().lyricsFont = value;
                    ConfigManager.saveClient();
                });
        addPicker("Lyrics colour", PickerKind.COLOR, () -> ConfigManager.client().lyricsColor,
                value -> {
                    ConfigManager.client().lyricsColor = value;
                    ConfigManager.saveClient();
                    syncColorRows();
                });
        rows.add(new ToggleRow("Bold text", () -> ConfigManager.client().lyricsBold,
                value -> {
                    ConfigManager.client().lyricsBold = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Fade in time", () -> ConfigManager.client().lyricsFadeInMillis,
                value -> {
                    ConfigManager.client().lyricsFadeInMillis = value;
                    ConfigManager.saveClient();
                }, 0, 2000, 50, " ms"));
        addPicker("Fade in animation", PickerKind.IN_ANIMATION, () -> ConfigManager.client().lyricsInStyle,
                value -> {
                    ConfigManager.client().lyricsInStyle = value;
                    ConfigManager.saveClient();
                });
        rows.add(new SliderRow("Fade out time", () -> ConfigManager.client().lyricsFadeOutMillis,
                value -> {
                    ConfigManager.client().lyricsFadeOutMillis = value;
                    ConfigManager.saveClient();
                }, 0, 4000, 100, " ms"));
        addPicker("Fade out animation", PickerKind.OUT_ANIMATION, () -> ConfigManager.client().lyricsOutStyle,
                value -> {
                    ConfigManager.client().lyricsOutStyle = value;
                    ConfigManager.saveClient();
                });
        rows.add(new CycleRow("Fade out trigger", () -> keepModeLabel(ConfigManager.client().lyricsKeepMode),
                () -> {
                    ConfigManager.client().lyricsKeepMode = cycle(KEEP_MODES, ConfigManager.client().lyricsKeepMode);
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Lyrics seconds", () -> ConfigManager.client().lyricsSeconds,
                value -> {
                    ConfigManager.client().lyricsSeconds = value;
                    ConfigManager.saveClient();
                }, 1, 30, 1, " s"));
        rows.add(new ToggleRow("Visible through blocks", () -> ConfigManager.client().lyricsSeeThrough,
                value -> {
                    ConfigManager.client().lyricsSeeThrough = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new SliderRow("Sync offset", () -> ConfigManager.client().lyricsOffsetMillis,
                value -> {
                    ConfigManager.client().lyricsOffsetMillis = value;
                    ConfigManager.saveClient();
                }, -3000, 3000, 50, " ms"));
        rows.add(new SliderRow("Lyrics kept", () -> ConfigManager.client().lyricsKept,
                value -> {
                    ConfigManager.client().lyricsKept = value;
                    ConfigManager.saveClient();
                }, 8, 128, 8, ""));

        rows.add(new HeaderRow("Spotify app"));
        rows.add(new ToggleRow("Start Spotify automatically", () -> ConfigManager.client().autoLaunchSpotify,
                value -> {
                    ConfigManager.client().autoLaunchSpotify = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Start Spotify minimized", () -> ConfigManager.client().minimizeSpotifyWindow,
                value -> {
                    ConfigManager.client().minimizeSpotifyWindow = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Pause when leaving a world", () -> ConfigManager.client().pauseOnLeave,
                value -> {
                    ConfigManager.client().pauseOnLeave = value;
                    ConfigManager.saveClient();
                }));
        rows.add(new ToggleRow("Close Spotify with Minecraft", () -> ConfigManager.client().closeSpotifyOnQuit,
                value -> {
                    ConfigManager.client().closeSpotifyOnQuit = value;
                    ConfigManager.saveClient();
                }));

        rows.add(new HeaderRow("Diagnostics"));
        rows.add(new InfoRow("Album art", () -> {
            AlbumArtCache art = ClientState.artwork();
            String summary = art.loadedCount() + " loaded, " + art.failureCount() + " failed";
            return art.lastError().isEmpty() ? summary : summary + ": " + art.lastError();
        }));
        rows.add(new InfoRow("Playback devices", () -> {
            List<Device> found = ClientState.session().devices();
            if (found.isEmpty()) {
                return "none found";
            }
            StringBuilder names = new StringBuilder();
            for (Device device : found) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append(device.name());
                if (device.active()) {
                    names.append(" (active)");
                }
            }
            return names.toString();
        }));
        rows.add(new InfoRow("Spotify limit", () -> {
            long blocked = ClientState.session().rateLimitedForMillis();
            return blocked > 0L ? "blocked for " + SpotifySession.formatDuration(blocked) : "none";
        }));
        rows.add(new InfoRow("Voice chat",
                () -> SpotifyControl.voicechatPresent() ? "installed" : "not installed"));
        syncColorRows();
    }

    private void ensureBoxes(int x, int y, int width) {
        Font font = Minecraft.getInstance().font;
        int boxWidth = Math.max(40, Math.min(260, width - 196));
        if (clientIdBox == null) {
            clientIdBox = new EditBox(font, x + 120, y, boxWidth, 16, Component.literal("client id"));
            clientIdBox.setMaxLength(64);
            clientIdBox.setBordered(false);
            clientIdBox.setTextColor(Theme.TEXT);
            clientIdBox.setValue(ConfigManager.client().spotifyClientId);
            clientIdBox.setResponder(value -> {
                ConfigManager.client().spotifyClientId = value.trim();
                ConfigManager.saveClient();
            });
        } else {
            clientIdBox.setPosition(x + 120, y);
            clientIdBox.setWidth(boxWidth);
        }
        if (portBox == null) {
            portBox = new EditBox(font, x + 120, y + 26, 60, 16, Component.literal("port"));
            portBox.setMaxLength(5);
            portBox.setBordered(false);
            portBox.setTextColor(Theme.TEXT);
            portBox.setValue(String.valueOf(ConfigManager.client().callbackPort));
            portBox.setResponder(value -> {
                try {
                    int port = Integer.parseInt(value.trim());
                    if (port > 1024 && port < 65535) {
                        ConfigManager.client().callbackPort = port;
                        ConfigManager.saveClient();
                    }
                } catch (NumberFormatException ignored) {
                    return;
                }
            });
        } else {
            portBox.setPosition(x + 120, y + 26);
        }
    }

    public void render(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY,
                       float partialTick) {
        Font font = Minecraft.getInstance().font;
        panelX = x;
        panelY = y;
        panelWidth = width;
        panelHeight = height;

        Platform.drawString(graphics, font, Lang.plain("gui.settings"), x, y, Theme.TEXT, false);
        boolean guide = height >= GUIDE_MIN_HEIGHT;
        if (guide) {
            Platform.drawString(graphics, font, Theme.fit(font, Lang.plain("gui.setup_title"), width - 8), x, y + 15,
                    Theme.TEXT_MUTED, false);
            int openStep = openSetupStep();
            for (int step = 0; step < 3; step++) {
                String number = String.valueOf(step + 1);
                int badgeX = x + 5;
                int badgeY = y + 33 + step * 12;
                int badge = Theme.ELEVATED;
                if (step == openStep) {
                    // The breath starts at the resting colour rather than passing through it, so a
                    // badge with animations off is the same flat circle it has always been. Anim.mix
                    // rather than Theme.mix here and below because every distinct colour handed to a
                    // circle or a rounded rectangle becomes its own cached texture.
                    badge = Anim.mix(Theme.ELEVATED, Theme.GREEN_DARK, Anim.pulse(2.4f) * 0.6f);
                }
                Theme.circle(graphics, badgeX, badgeY, 5, badge);
                // Drawn at three quarter size around the middle of the badge, the full size digit
                // fills a ten pixel circle edge to edge.
                Platform.pushScale(graphics, badgeX, badgeY, 0.75f);
                Platform.drawString(graphics, font, number, -font.width(number) / 2, -4,
                        Theme.TEXT_MUTED, false);
                Platform.popMatrix(graphics);
                Platform.drawString(graphics, font,
                        Theme.fit(font, Lang.plain("gui.setup_step" + number), width - 24), x + 16,
                        y + 29 + step * 12, Theme.TEXT_DIM, false);
            }
        } else {
            Platform.drawString(graphics, font, Theme.fit(font, Lang.plain("gui.client_id_hint"), width - 8), x,
                    y + 14, Theme.TEXT_MUTED, false);
        }

        boxesY = y + (guide ? BOXES_OFFSET : BOXES_OFFSET_COMPACT);
        ensureBoxes(x, boxesY + 8, width);
        Theme.rounded(graphics, x + 114, boxesY, clientBoxWidth(width), 24, 4,
                Anim.mix(Theme.ELEVATED, Theme.SURFACE_HOVER, focusLift("set.box:0", clientIdBox.isFocused())));
        Platform.drawString(graphics, font, "Client ID", x, boxesY + 8, Theme.TEXT_MUTED, false);
        clientIdBox.render(graphics, mouseX, mouseY, partialTick);

        // Straight beside the field the client ID goes into, so whoever does not know where to get
        // one is one click away from the page that explains it.
        int helpWidth = font.width(HELP_LABEL) + 16;
        int helpX = helpButtonX(x, width);
        boolean helpHovered = Theme.hovered(mouseX, mouseY, helpX, boxesY + 3, helpWidth, 18);
        float helpGlow = Anim.hover("set.help", helpHovered);
        int helpInset = Anim.pressInset("set.help.press", 1);
        Theme.rounded(graphics, helpX + helpInset, boxesY + 3 + helpInset, helpWidth - 2 * helpInset,
                18 - 2 * helpInset, 9, Anim.mix(Theme.ELEVATED, Theme.GREEN, helpGlow));
        Platform.drawString(graphics, font, HELP_LABEL, helpX + 8, boxesY + 8,
                Anim.mix(Theme.TEXT, Theme.BLACK, helpGlow), false);

        Theme.rounded(graphics, x + 114, boxesY + 26, 68, 24, 4,
                Anim.mix(Theme.ELEVATED, Theme.SURFACE_HOVER, focusLift("set.box:1", portBox.isFocused())));
        Platform.drawString(graphics, font, "Callback port", x, boxesY + 34, Theme.TEXT_MUTED, false);
        portBox.render(graphics, mouseX, mouseY, partialTick);

        // The row list must never start below the panel, otherwise it is drawn outside the area
        // that accepts clicks and no setting can be reached any more - including the one that
        // caused the cramped layout in the first place.
        rowsY = Math.min(boxesY + 58, Math.max(y + 40, y + height - 2 * ROW_HEIGHT));
        int viewHeight = Math.max(0, y + height - rowsY);
        scroll.setViewportHeight(viewHeight);
        scroll.setContentHeight(rows.size() * ROW_HEIGHT);

        Theme.scissor(graphics, x, rowsY, x + width, rowsY + viewHeight);
        int offset = scroll.offset();
        for (int index = 0; index < rows.size(); index++) {
            int rowY = rowsY + index * ROW_HEIGHT - offset;
            Row current = rows.get(index);
            int span = spanOfRow(current);
            if (current instanceof PickerFiller || rowY + span * ROW_HEIGHT < rowsY || rowY > rowsY + viewHeight) {
                continue;
            }
            renderRow(graphics, font, current, index, x, rowY, width, mouseX, mouseY);
        }
        Theme.endScissor(graphics);
        scroll.renderScrollbar(graphics, x + width - 4, rowsY, viewHeight);
    }

    private void renderRow(GuiGraphics graphics, Font font, Row row, int index, int x, int y, int width, int mouseX,
                           int mouseY) {
        if (row instanceof PickerRow picker) {
            renderPicker(graphics, font, picker, index, x, y, width, mouseX, mouseY);
            return;
        }
        if (row instanceof ColorRow field) {
            renderColorField(graphics, font, field, x, y, width);
            return;
        }
        if (row instanceof HeaderRow header) {
            String label = header.label().toUpperCase(java.util.Locale.ROOT);
            Platform.drawString(graphics, font, label, x + 4, y + 12, Theme.GREEN, false);
            int lineX = x + 12 + font.width(label);
            graphics.fill(lineX, y + 15, x + width - 10, y + 16, Theme.BORDER);
            return;
        }
        boolean hovered = Theme.hovered(mouseX, mouseY, x, y, width - 6, ROW_HEIGHT - 2)
                && !scroll.claims(mouseX, mouseY);
        // Painted with alpha instead of switched on and off, so the highlight has a way out again
        // once the cursor has moved to the next row.
        float lift = Anim.hover("set.row:" + index, hovered);
        // A copy row has nothing of its own to press, so the row itself takes the press. Everything
        // written on it stays where it is, which is the whole reason the edges move instead.
        int rowInset = row instanceof CopyRow ? Anim.pressInset("set.btn:" + index, 1) : 0;
        if (lift > 0.0f) {
            Theme.rounded(graphics, x + rowInset, y + rowInset, width - 6 - 2 * rowInset,
                    ROW_HEIGHT - 2 - 2 * rowInset, 4, Anim.fade(Theme.SURFACE_HOVER, lift));
        }
        Platform.drawString(graphics, font, row.label(), x + 10, y + 8, Theme.TEXT, false);
        int controlX = controlX(x, width);
        int controlWidth = CONTROL_WIDTH;

        if (row instanceof ToggleRow toggle) {
            boolean value = toggle.get().getAsBoolean();
            float on = Anim.to("set.toggle:" + index, value ? 1.0f : 0.0f, Anim.FAST);
            int trackX = x + width - 42;
            Theme.rounded(graphics, trackX, y + 7, 30, 12, 6, Anim.mix(Theme.TRACK, Theme.GREEN, on));
            int knobX = Math.round(Anim.lerp(trackX + 7, trackX + 23, on));
            Theme.circle(graphics, knobX, y + 13, 5 - Anim.pressInset("set.knob:" + index, 1), Theme.TEXT);
        } else if (row instanceof CycleRow cycle) {
            String value = Theme.fit(font, cycle.get().get(), controlWidth - 12);
            boolean buttonHovered = hovered && Theme.hovered(mouseX, mouseY, controlX, y + 4, controlWidth, 16);
            // The cycle button carries no lit state in the still interface, so it is only lifted
            // while there is a fade to carry it in and back out again.
            float glow = Anim.enabled() ? Anim.hover("set.cycle:" + index, buttonHovered) : 0.0f;
            int inset = Anim.pressInset("set.btn:" + index, 2);
            // Two depths off the same press. The plate gives two pixels because a button this size
            // has that much to give, but a label cannot shrink by a pixel without going soft, so it
            // travels instead - one pixel into the button while pressed, one proud on the rebound.
            int drop = Anim.pressInset("set.btn:" + index, 1);
            Theme.rounded(graphics, controlX + inset, y + 4 + inset, controlWidth - 2 * inset, 16 - 2 * inset, 8,
                    Anim.mix(Theme.ELEVATED, Theme.SURFACE_HOVER, glow));
            Platform.drawString(graphics, font, value, controlX + 8, y + 8 + drop,
                    Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, glow), false);
        } else if (row instanceof SliderRow slider) {
            int value = slider.get().getAsInt();
            float fraction = (float) (value - slider.min()) / Math.max(1, slider.max() - slider.min());
            int trackTop = y + 10;
            boolean onTrack = draggingSlider == index
                    || (hovered && Theme.hovered(mouseX, mouseY, controlX - 4, y + 4, sliderTrackWidth() + 8, 18));
            // Neither the wider knob nor the brighter fill exist while the interface stands still,
            // so both stay at zero with animations off.
            float grab = Anim.enabled() ? Anim.to("set.slider:" + index, onTrack ? 1.0f : 0.0f, Anim.HOVER) : 0.0f;
            int filled = Theme.slider(graphics, controlX, trackTop, sliderTrackWidth(), Theme.SLIDER_HEIGHT,
                    fraction, Theme.TRACK, Anim.mix(Theme.GREEN, Theme.GREEN_BRIGHT, grab));
            int knobY = trackTop + Theme.SLIDER_HEIGHT / 2;
            Theme.circle(graphics, controlX + filled, knobY,
                    Anim.grownSize(4, grab, 1) - Anim.pressInset("set.knob:" + index, 1), Theme.TEXT);
            String text = value + slider.suffix();
            Platform.drawString(graphics, font, text, x + width - 12 - font.width(text), y + 8,
                    Theme.TEXT_MUTED, false);
        } else if (row instanceof ActionRow action) {
            String label = action.button().get();
            boolean buttonHovered = Theme.hovered(mouseX, mouseY, controlX, y + 3, controlWidth, 18);
            int inset = Anim.pressInset("set.btn:" + index, 2);
            int drop = Anim.pressInset("set.btn:" + index, 1);
            // Spelled out rather than through Anim.hoverColor, whose mix is not stepped and would
            // hand this rounded rectangle a new colour on every frame of the fade.
            Theme.rounded(graphics, controlX + inset, y + 3 + inset, controlWidth - 2 * inset, 18 - 2 * inset, 9,
                    Anim.mix(Theme.GREEN, Theme.GREEN_BRIGHT, Anim.hover("set.action:" + index, buttonHovered)));
            // Only one row can ask for a key, and it says so on its own button. While it waits the
            // label breathes, because the next key press goes into the setting and not the game.
            boolean waiting = capturingKey && label.equals(Lang.plain("gui.press_a_key"));
            int labelColor = waiting ? Anim.textFade(Theme.BLACK, 1.0f - 0.55f * Anim.pulse(1.6f)) : Theme.BLACK;
            Platform.drawString(graphics, font, label, controlX + (controlWidth - font.width(label)) / 2, y + 8 + drop,
                    labelColor, false);
        } else if (row instanceof InfoRow info) {
            String value = Theme.fit(font, info.value().get(), controlWidth + 40);
            Platform.drawString(graphics, font, value, x + width - 10 - font.width(value), y + 8, Theme.TEXT_MUTED, false);
        } else if (row instanceof CopyRow copy) {
            String value = Theme.fit(font, copy.value().get(), controlWidth + 28);
            int valueX = x + width - 10 - font.width(value);
            Platform.drawString(graphics, font, value, valueX, y + 8,
                    Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, lift), false);
            if (lift > 0.0f) {
                graphics.fill(valueX, y + 17, x + width - 10, y + 18, Theme.alpha(Theme.TEXT_MUTED, lift));
            }
        }
    }

    /**
     * Opens the colour fields under the colour list for the custom choices and closes them for
     * every other one, so the list only holds what the chosen colour can be adjusted with.
     */
    private void syncColorRows() {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i) instanceof ColorRow) {
                rows.subList(i, Math.min(rows.size(), i + COLOR_SPAN)).clear();
            }
        }
        int at = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof PickerRow picker && picker.kind() == PickerKind.COLOR) {
                at = i + spanOf(PickerKind.COLOR);
            }
        }
        if (at < 0) {
            return;
        }
        LyricsColor chosen = LyricsColor.of(ConfigManager.client().lyricsColor);
        List<Row> fields = new ArrayList<>();
        if (chosen == LyricsColor.CUSTOM || chosen == LyricsColor.CUSTOM_GRADIENT) {
            addColorField(fields, new ColorRow(chosen == LyricsColor.CUSTOM ? "Custom colour" : "Gradient start", 0));
        }
        if (chosen == LyricsColor.CUSTOM_GRADIENT) {
            addColorField(fields, new ColorRow("Gradient end", 1));
        }
        rows.addAll(at, fields);
    }

    private static void addColorField(List<Row> target, ColorRow row) {
        target.add(row);
        for (int slot = 1; slot < COLOR_SPAN; slot++) {
            target.add(new PickerFiller(slot));
        }
    }

    private void addPicker(String label, PickerKind kind, Supplier<String> get, Consumer<String> set) {
        rows.add(new PickerRow(label, kind, get, set));
        for (int slot = 1; slot < spanOf(kind); slot++) {
            rows.add(new PickerFiller(slot));
        }
    }

    private static String[] pickerKeys(PickerKind kind) {
        String[] keys;
        if (kind == PickerKind.FONT) {
            LyricsFont[] fonts = LyricsFont.values();
            keys = new String[fonts.length];
            for (int i = 0; i < fonts.length; i++) {
                keys[i] = fonts[i].name();
            }
        } else if (kind == PickerKind.COLOR) {
            LyricsColor[] colors = LyricsColor.values();
            keys = new String[colors.length];
            for (int i = 0; i < colors.length; i++) {
                keys[i] = colors[i].name();
            }
        } else {
            LyricsAnimation[] animations = LyricsAnimation.values();
            keys = new String[animations.length];
            for (int i = 0; i < animations.length; i++) {
                keys[i] = animations[i].name();
            }
        }
        return keys;
    }

    /**
     * The name of a choice. A font is written in the font it stands for and a colour in its own
     * colours, so the list is a preview too.
     */
    private static Component chipText(PickerKind kind, String key) {
        if (kind == PickerKind.FONT) {
            LyricsFont font = LyricsFont.of(key);
            return Component.literal(font.label()).withStyle(font.apply(Style.EMPTY));
        }
        if (kind == PickerKind.COLOR) {
            LyricsColor color = LyricsColor.of(key);
            return LyricsColor.build(color.label(), color.stops(ConfigManager.client(), 0.0f), Style.EMPTY);
        }
        return Component.literal(LyricsAnimation.of(key).label());
    }

    private static int previewWidth(int width) {
        return width >= 380 ? 132 : 0;
    }

    /** Where the choices sit inside the block of a list, as x, y, width and height. They wrap to the next line. */
    private static int[][] chipRects(Font font, PickerKind kind, int x, int y, int width) {
        String[] keys = pickerKeys(kind);
        int preview = previewWidth(width);
        int left = x + 10;
        int right = x + width - 20 - (preview > 0 ? preview + 8 : 0);
        int cx = left;
        int cy = y + 24;
        int[][] rects = new int[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            int w = font.width(chipText(kind, keys[i])) + 14;
            if (cx + w > right && cx > left) {
                cx = left;
                cy += CHIP_HEIGHT + 4;
            }
            rects[i] = new int[] {cx, cy, w, CHIP_HEIGHT};
            cx += w + 4;
        }
        return rects;
    }

    private void renderPicker(GuiGraphics graphics, Font font, PickerRow picker, int index, int x, int y, int width,
                              int mouseX, int mouseY) {
        int blockHeight = spanOf(picker.kind()) * ROW_HEIGHT - 6;
        Theme.rounded(graphics, x + 4, y + 2, width - 14, blockHeight, 6, Theme.alpha(Theme.SURFACE_HOVER, 0.55f));
        Platform.drawString(graphics, font, picker.label(), x + 10, y + 8, Theme.TEXT, false);

        PickerKind kind = picker.kind();
        String selected = picker.get().get();
        String[] keys = pickerKeys(kind);
        int[][] rects = chipRects(font, kind, x, y, width);
        boolean reachable = mouseY >= rowsY && !scroll.claims(mouseX, mouseY);
        String hoveredKey = null;
        for (int i = 0; i < keys.length; i++) {
            int[] r = rects[i];
            boolean over = reachable && Theme.hovered(mouseX, mouseY, r[0], r[1], r[2], r[3]);
            if (over) {
                hoveredKey = keys[i];
            }
            boolean on = keys[i].equalsIgnoreCase(selected);
            float glow = Anim.hover("set.chip:" + index + ":" + i, over);
            int fill = on ? Theme.GREEN : Anim.mix(Theme.ELEVATED, Theme.SURFACE_HOVER, glow);
            Theme.rounded(graphics, r[0], r[1], r[2], r[3], 8, fill);
            Platform.drawText(graphics, font, chipText(kind, keys[i]), r[0] + 7, r[1] + (CHIP_HEIGHT - 7) / 2,
                    on ? Theme.BLACK : Anim.mix(Theme.TEXT_MUTED, Theme.TEXT, glow), false);
        }

        int preview = previewWidth(width);
        if (preview > 0) {
            renderPreview(graphics, font, index, kind, hoveredKey != null ? hoveredKey : selected,
                    x + width - 20 - preview, y + 24, preview, blockHeight - 28);
        }
    }

    /** What each preview showed last, and since when, so it starts again from the beginning on any change. */
    private static final java.util.Map<Integer, String> PREVIEW_SHOWN = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Long> PREVIEW_SINCE = new java.util.HashMap<>();

    /**
     * Shows a word the way the world will show it: for an animation it appears, stays and goes
     * on a loop, with the fade times from the settings and the choice that is under the cursor in
     * place of the saved one; for a font or a colour it is a sample in that font or colour. The
     * loop starts over whenever anything it shows changes, so a changed setting is seen at once
     * from its first moment instead of wherever the loop happened to be.
     */
    private static void renderPreview(GuiGraphics graphics, Font font, int index, PickerKind kind, String key,
                                      int px, int py, int w, int h) {
        ClientConfig config = ConfigManager.client();
        Theme.rounded(graphics, px, py, w, h, 6, Theme.alpha(Theme.BLACK, 0.45f));

        LyricsAnimation in = LyricsAnimation.of(config.lyricsInStyle);
        LyricsAnimation out = LyricsAnimation.of(config.lyricsOutStyle);
        LyricsFont chosen = LyricsFont.of(config.lyricsFont);
        LyricsColor color = LyricsColor.of(config.lyricsColor);
        if (kind == PickerKind.IN_ANIMATION) {
            in = LyricsAnimation.of(key);
        } else if (kind == PickerKind.OUT_ANIMATION) {
            out = LyricsAnimation.of(key);
        } else if (kind == PickerKind.COLOR) {
            color = LyricsColor.of(key);
        } else {
            chosen = LyricsFont.of(key);
        }

        String shown = in + "|" + out + "|" + chosen + "|" + color + "|" + config.lyricsBold + "|"
                + config.lyricsFadeInMillis + "|" + config.lyricsFadeOutMillis + "|" + config.lyricsCustom1Hue + "|"
                + config.lyricsCustom1Saturation + "|" + config.lyricsCustom1Brightness + "|" + config.lyricsCustom2Hue
                + "|" + config.lyricsCustom2Saturation + "|" + config.lyricsCustom2Brightness;
        long now = System.currentTimeMillis();
        if (!shown.equals(PREVIEW_SHOWN.get(index))) {
            PREVIEW_SHOWN.put(index, shown);
            PREVIEW_SINCE.put(index, now);
        }
        long since = now - PREVIEW_SINCE.getOrDefault(index, now);

        boolean animated = kind == PickerKind.IN_ANIMATION || kind == PickerKind.OUT_ANIMATION;
        String sample = kind == PickerKind.FONT ? "Sing along" : "Lyrics";
        int[] stops = color.stops(config, 0.0f);
        Style base = chosen.apply(Style.EMPTY.withBold(config.lyricsBold));
        Component word = LyricsColor.build(sample, stops, base);

        float raw = 0.0f;
        LyricsAnimation active = null;
        boolean leaving = false;
        if (animated) {
            // Never shorter than a third of a second, so even a time of 0 can be seen to do something.
            int inMs = Math.max(Math.max(350, config.lyricsFadeInMillis), in.minMillis());
            int outMs = Math.max(Math.max(350, config.lyricsFadeOutMillis), out.minMillis());
            if (kind == PickerKind.IN_ANIMATION) {
                // Only the way in: the word comes, stays a moment, is cleared away and comes again.
                int hold = 1100;
                int gap = 400;
                long t = since % (inMs + hold + gap);
                active = in;
                if (t < inMs) {
                    raw = 1.0f - (float) t / inMs;
                } else if (t >= inMs + hold) {
                    raw = 1.0f;
                }
            } else {
                // Only the way out: the word stands there, goes, and is put back for the next round.
                int lead = 700;
                int gap = 450;
                long t = since % (lead + outMs + gap);
                if (t >= lead) {
                    raw = t < lead + outMs ? (float) (t - lead) / outMs : 1.0f;
                    active = out;
                    leaving = true;
                }
            }
        }
        float hidden = LyricsAnimation.ease(raw);
        LyricsAnimation.Pose pose = active == null ? LyricsAnimation.Pose.REST : active.pose(leaving, hidden);
        boolean perLetter = active != null && active.perLetter();
        float alpha = 1.0f - hidden;
        if (!perLetter && alpha < 0.06f && !(active != null && active.sparks())) {
            return;
        }
        int textWidth = Math.max(1, font.width(word));
        float scale = Math.min(animated ? 2.2f : 1.6f, (w - 12.0f) / textWidth);
        float lineHeight = 8.0f * scale;
        Theme.scissor(graphics, px, py, px + w, py + h);
        if (perLetter) {
            // One letter at a time, each in its own place of the animation, as the world draws it.
            int[] points = sample.codePoints().toArray();
            Component[] letters = new Component[points.length];
            int[] widths = new int[points.length];
            for (int i = 0; i < points.length; i++) {
                letters[i] = LyricsColor.build(new String(Character.toChars(points[i])),
                        new int[] {LyricsColor.sample(stops, (float) i / (points.length - 1))}, base);
                widths[i] = font.width(letters[i]);
            }
            int before = 0;
            for (int i = 0; i < points.length; i++) {
                LyricsAnimation.LetterState state = LyricsAnimation.letter(leaving, raw, i, points.length);
                float letterAlpha = 1.0f - state.hidden();
                if (letterAlpha >= 0.06f) {
                    LyricsAnimation.Pose letterPose = state.pose();
                    float centre = (before + widths[i] / 2.0f - textWidth / 2.0f) * scale;
                    float flip = Math.max(0.03f, (float) Math.abs(Math.cos(letterPose.flip())));
                    Platform.pushTransform(graphics, px + w / 2.0f + centre + letterPose.slide() * lineHeight,
                            py + h / 2.0f - letterPose.lift() * lineHeight, letterPose.spin(),
                            scale * letterPose.scale() * flip, scale * letterPose.scale());
                    Platform.drawText(graphics, font, letters[i], -widths[i] / 2, -4,
                            Theme.alpha(0xFFFFFFFF, letterAlpha), true);
                    Platform.popMatrix(graphics);
                }
                before += widths[i];
            }
        } else if (alpha >= 0.06f) {
            float flip = Math.max(0.03f, (float) Math.abs(Math.cos(pose.flip())));
            Platform.pushTransform(graphics, px + w / 2.0f + pose.slide() * lineHeight,
                    py + h / 2.0f - pose.lift() * lineHeight, pose.spin(),
                    scale * pose.scale() * flip, scale * pose.scale());
            // The word carries its own colours, so what is passed here only sets how see-through it is.
            Platform.drawText(graphics, font, word, -textWidth / 2, -4, Theme.alpha(0xFFFFFFFF, alpha), true);
            Platform.popMatrix(graphics);
        }
        if (active != null && active.sparks()) {
            // Sparks fly out from the word as it appears and as it goes, drawn as little squares.
            float burst = leaving ? raw : 1.0f - raw;
            float fade = 1.0f - burst;
            for (int k = 0; k < 18; k++) {
                double angle = k * 2.399963 + k * 0.31;
                double reach = (0.25 + 0.75 * ((k * 37) % 11) / 10.0) * burst;
                int sx = (int) (px + w / 2.0 + Math.cos(angle) * reach * w * 0.48);
                int sy = (int) (py + h / 2.0 + Math.sin(angle) * reach * h * 0.5);
                graphics.fill(sx, sy, sx + 2, sy + 2, Theme.alpha(0xFFFFFFFF, Math.max(0.0f, fade)));
            }
        }
        Theme.endScissor(graphics);
    }

    /** Hue in degrees, saturation and brightness in percent of the colour a field edits. */
    private static int[] hsvOf(ClientConfig config, int which) {
        return which == 0
                ? new int[] {config.lyricsCustom1Hue, config.lyricsCustom1Saturation, config.lyricsCustom1Brightness}
                : new int[] {config.lyricsCustom2Hue, config.lyricsCustom2Saturation, config.lyricsCustom2Brightness};
    }

    private static void setHsv(ClientConfig config, int which, int hue, int saturation, int brightness) {
        if (which == 0) {
            config.lyricsCustom1Hue = hue;
            config.lyricsCustom1Saturation = saturation;
            config.lyricsCustom1Brightness = brightness;
        } else {
            config.lyricsCustom2Hue = hue;
            config.lyricsCustom2Saturation = saturation;
            config.lyricsCustom2Brightness = brightness;
        }
    }

    /** Where the parts of a colour field sit: the square as x, y, w, h, then the hue bar as x, y, w, h. */
    private static int[] colorField(int x, int y) {
        int height = COLOR_SPAN * ROW_HEIGHT - 6 - 28;
        int squareX = x + 10;
        int squareY = y + 24;
        return new int[] {squareX, squareY, COLOR_FIELD_WIDTH, height,
                squareX + COLOR_FIELD_WIDTH + 8, squareY, COLOR_BAR_WIDTH, height};
    }

    private void renderColorField(GuiGraphics graphics, Font font, ColorRow row, int x, int y, int width) {
        ClientConfig config = ConfigManager.client();
        int blockHeight = COLOR_SPAN * ROW_HEIGHT - 6;
        Theme.rounded(graphics, x + 4, y + 2, width - 14, blockHeight, 6, Theme.alpha(Theme.SURFACE_HOVER, 0.55f));
        Platform.drawString(graphics, font, row.label(), x + 10, y + 8, Theme.TEXT, false);
        int[] f = colorField(x, y);
        int[] hsv = hsvOf(config, row.which());

        // The square is the colour at full strength along the top right and white and black beside
        // it, drawn as small cells because that is what the interface can paint without a texture.
        int columns = 30;
        int lines = 17;
        for (int line = 0; line < lines; line++) {
            int y0 = f[1] + line * f[3] / lines;
            int y1 = f[1] + (line + 1) * f[3] / lines;
            int brightness = Math.round(100.0f * (1.0f - (float) line / (lines - 1)));
            for (int column = 0; column < columns; column++) {
                int x0 = f[0] + column * f[2] / columns;
                int x1 = f[0] + (column + 1) * f[2] / columns;
                int saturation = Math.round(100.0f * column / (columns - 1));
                graphics.fill(x0, y0, x1, y1, 0xFF000000 | LyricsColor.hsv(hsv[0], saturation, brightness));
            }
        }
        int steps = 24;
        for (int step = 0; step < steps; step++) {
            int y0 = f[5] + step * f[7] / steps;
            int y1 = f[5] + (step + 1) * f[7] / steps;
            graphics.fill(f[4], y0, f[4] + f[6], y1, 0xFF000000 | LyricsColor.hsv(step * 360 / steps, 100, 100));
        }

        int picked = 0xFF000000 | LyricsColor.hsv(hsv[0], hsv[1], hsv[2]);
        int markX = f[0] + Math.round((f[2] - 1) * hsv[1] / 100.0f);
        int markY = f[1] + Math.round((f[3] - 1) * (1.0f - hsv[2] / 100.0f));
        graphics.fill(markX - 3, markY - 3, markX + 4, markY + 4, Theme.TEXT);
        graphics.fill(markX - 2, markY - 2, markX + 3, markY + 3, picked);
        int hueY = f[5] + Math.round((f[7] - 1) * hsv[0] / 360.0f);
        graphics.fill(f[4] - 2, hueY - 1, f[4] + f[6] + 2, hueY + 2, Theme.TEXT);

        int swatchX = f[4] + f[6] + 14;
        Theme.rounded(graphics, swatchX, f[1], 52, 24, 6, picked);
        String hex = String.format(java.util.Locale.ROOT, "#%06X", picked & 0xFFFFFF);
        Platform.drawString(graphics, font, hex, swatchX, f[1] + 32, Theme.TEXT_MUTED, false);
    }

    /** Moves the colour of a field to where the cursor is, in the part that was grabbed. */
    private void applyColor(int index, int part, double mouseX, double mouseY) {
        if (index < 0 || index >= rows.size() || !(rows.get(index) instanceof ColorRow row)) {
            return;
        }
        int rowTop = rowsY + index * ROW_HEIGHT - scroll.offset();
        int[] f = colorField(panelX, rowTop);
        ClientConfig config = ConfigManager.client();
        int[] hsv = hsvOf(config, row.which());
        if (part == 0) {
            double saturation = (mouseX - f[0]) / (double) (f[2] - 1);
            double brightness = 1.0D - (mouseY - f[1]) / (double) (f[3] - 1);
            hsv[1] = (int) Math.round(100.0D * Math.max(0.0D, Math.min(1.0D, saturation)));
            hsv[2] = (int) Math.round(100.0D * Math.max(0.0D, Math.min(1.0D, brightness)));
        } else {
            double hue = (mouseY - f[5]) / (double) (f[7] - 1);
            hsv[0] = (int) Math.round(360.0D * Math.max(0.0D, Math.min(1.0D, hue)));
        }
        setHsv(config, row.which(), hsv[0], hsv[1], hsv[2]);
        ConfigManager.saveClient();
    }

    private void clickPicker(PickerRow picker, int index, int mouseX, int mouseY) {
        int rowTop = rowsY + index * ROW_HEIGHT - scroll.offset();
        Font font = Minecraft.getInstance().font;
        String[] keys = pickerKeys(picker.kind());
        int[][] rects = chipRects(font, picker.kind(), panelX, rowTop, panelWidth);
        for (int i = 0; i < keys.length; i++) {
            int[] r = rects[i];
            if (Theme.hovered(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                Anim.click("set.chip:" + index + ":" + i);
                picker.set().accept(keys[i]);
                return;
            }
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!Theme.hovered((int) mouseX, (int) mouseY, panelX, panelY, panelWidth, panelHeight)) {
            unfocusBoxes();
            return false;
        }
        if (Theme.hovered((int) mouseX, (int) mouseY, helpButtonX(panelX, panelWidth), boxesY + 3,
                Minecraft.getInstance().font.width(HELP_LABEL) + 16, 18)) {
            Anim.click("set.help.press");
            Platform.openUri(HELP_URL);
            return true;
        }
        if (clientIdBox != null && Theme.hovered((int) mouseX, (int) mouseY,
                panelX + 114, boxesY, clientBoxWidth(panelWidth), 24)) {
            focusBox(clientIdBox, portBox, mouseX, button);
            return true;
        }
        if (portBox != null && Theme.hovered((int) mouseX, (int) mouseY,
                panelX + 114, boxesY + 26, 68, 24)) {
            focusBox(portBox, clientIdBox, mouseX, button);
            return true;
        }
        unfocusBoxes();
        if (mouseY < rowsY) {
            capturingKey = false;
            return true;
        }
        if (scroll.scrollable() && (int) mouseX >= panelX + panelWidth - 4) {
            return true;
        }
        int index = ((int) mouseY - rowsY + scroll.offset()) / ROW_HEIGHT;
        if (index < 0 || index >= rows.size()) {
            return true;
        }
        if (button != 0) {
            return true;
        }
        Row row = rows.get(index);
        if (row instanceof PickerFiller filler) {
            index -= filler.slot();
            row = rows.get(index);
        }
        if (row instanceof PickerRow picker) {
            clickPicker(picker, index, (int) mouseX, (int) mouseY);
            return true;
        }
        if (row instanceof ColorRow) {
            int[] f = colorField(panelX, rowsY + index * ROW_HEIGHT - scroll.offset());
            if (Theme.hovered((int) mouseX, (int) mouseY, f[0], f[1], f[2], f[3])) {
                draggingColor = index;
                draggingColorPart = 0;
            } else if (Theme.hovered((int) mouseX, (int) mouseY, f[4] - 4, f[5], f[6] + 8, f[7])) {
                draggingColor = index;
                draggingColorPart = 1;
            }
            if (draggingColor >= 0) {
                applyColor(draggingColor, draggingColorPart, mouseX, mouseY);
            }
            return true;
        }
        if (row instanceof HeaderRow) {
            return true;
        }
        int controlX = controlX(panelX, panelWidth);
        if (row instanceof ToggleRow toggle) {
            Anim.click("set.knob:" + index);
            toggle.set().accept(!toggle.get().getAsBoolean());
        } else if (row instanceof CycleRow cycle) {
            Anim.click("set.btn:" + index);
            cycle.next().run();
        } else if (row instanceof ActionRow action) {
            int rowTop = rowsY + index * ROW_HEIGHT - scroll.offset();
            if (Theme.hovered((int) mouseX, (int) mouseY, controlX, rowTop + 3, CONTROL_WIDTH, 18)) {
                Anim.click("set.btn:" + index);
                action.action().run();
            }
        } else if (row instanceof CopyRow copy) {
            Anim.click("set.btn:" + index);
            Platform.copyToClipboard(copy.value().get());
            ClientState.toast(Lang.plain("gui.copied"));
        } else if (row instanceof SliderRow slider) {
            if (mouseX >= controlX - 4 && mouseX < controlX + sliderTrackWidth() + 4) {
                Anim.click("set.knob:" + index);
                draggingSlider = index;
                applySlider(slider, mouseX, controlX);
            }
        }
        return true;
    }

    private void focusBox(EditBox target, EditBox other, double mouseX, int button) {
        double clamped = Math.max(target.getX(),
                Math.min(mouseX, target.getX() + target.getWidth() - 1));
        Platform.click(target, clamped, target.getY() + 8, button);
        target.setFocused(true);
        if (other != null) {
            other.setFocused(false);
        }
    }

    private void unfocusBoxes() {
        if (clientIdBox != null) {
            clientIdBox.setFocused(false);
        }
        if (portBox != null) {
            portBox.setFocused(false);
        }
    }

    public boolean mouseDragged(double mouseX, double mouseY) {
        if (draggingColor >= 0) {
            applyColor(draggingColor, draggingColorPart, mouseX, mouseY);
            return true;
        }
        if (draggingSlider < 0 || draggingSlider >= rows.size()) {
            return false;
        }
        if (rows.get(draggingSlider) instanceof SliderRow slider) {
            applySlider(slider, mouseX, controlX(panelX, panelWidth));
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        draggingSlider = -1;
        draggingColor = -1;
    }

    /**
      * The setup step the user still has in front of them, or -1 when there is none to point at.
      * Only the last step leaves a trace this side can see - whether a client id has been pasted in.
      * Opening the dashboard and entering the redirect URI happen in a browser, so those two are
      * never singled out.
      */
    private static int openSetupStep() {
        if (ClientState.session().connected()) {
            return -1;
        }
        String clientId = ConfigManager.client().spotifyClientId;
        return clientId == null || clientId.isBlank() ? 2 : -1;
    }

    /**
      * A text box has no lit state in the still interface, so the fade towards one is only asked
      * for while animations are on and it can travel in both directions.
      */
    private static float focusLift(String id, boolean focused) {
        return Anim.enabled() ? Anim.to(id, focused ? 1.0f : 0.0f, Anim.HOVER) : 0.0f;
    }

    /** Width of the client ID field, which leaves room for the help button after it. */
    private static int clientBoxWidth(int width) {
        return Math.max(48, Math.min(268, width - 190));
    }

    private static int helpButtonX(int x, int width) {
        return x + 114 + clientBoxWidth(width) + 8;
    }

    private static int controlX(int x, int width) {
        return x + width - 16 - CONTROL_WIDTH;
    }

    private static int sliderTrackWidth() {
        return CONTROL_WIDTH - SLIDER_VALUE_WIDTH;
    }

    private void applySlider(SliderRow slider, double mouseX, int controlX) {
        double fraction = (mouseX - controlX) / (double) sliderTrackWidth();
        double raw = slider.min() + (slider.max() - slider.min()) * Math.max(0.0D, Math.min(1.0D, fraction));
        int step = Math.max(1, slider.step());
        int value = slider.min() + (int) Math.round((raw - slider.min()) / step) * step;
        value = Math.max(slider.min(), Math.min(slider.max(), value));
        // Every setter writes the config file, so a drag must only act on a real change.
        if (value != slider.get().getAsInt()) {
            slider.set().accept(value);
        }
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturingKey) {
            capturingKey = false;
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                ConfigManager.client().overlayKeyCode = keyCode;
                ConfigManager.saveClient();
            }
            return true;
        }
        if (clientIdBox != null && clientIdBox.isFocused()) {
            return Platform.keyPress(clientIdBox, keyCode, scanCode, modifiers);
        }
        if (portBox != null && portBox.isFocused()) {
            return Platform.keyPress(portBox, keyCode, scanCode, modifiers);
        }
        return false;
    }

    public boolean charTyped(int codepoint, int modifiers) {
        if (clientIdBox != null && clientIdBox.isFocused()) {
            return Platform.charTyped(clientIdBox, codepoint, modifiers);
        }
        if (portBox != null && portBox.isFocused()) {
            return Platform.charTyped(portBox, codepoint, modifiers);
        }
        return false;
    }

    public EditBox focusedBox() {
        if (clientIdBox != null && clientIdBox.isFocused()) {
            return clientIdBox;
        }
        if (portBox != null && portBox.isFocused()) {
            return portBox;
        }
        return null;
    }

    public boolean textFieldFocused() {
        return (clientIdBox != null && clientIdBox.isFocused()) || (portBox != null && portBox.isFocused());
    }

    public boolean mouseScrolled(double amount) {
        return scroll.scrollBy(amount);
    }

    /**
      * What Spotify reports for the account. The Web API only ever answers premium, free or open;
      * it does not say whether a premium account is individual, duo, family or student.
      */
    private static String planLabel() {
        String product = ClientState.session().tokens().product;
        String country = ClientState.session().tokens().country;
        if (product.isEmpty()) {
            return "unknown";
        }
        String name = product.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + product.substring(1);
        return country.isEmpty() ? name : name + " (" + country + ")";
    }

    private static String languageLabel(String code) {
        return switch (code.toLowerCase(java.util.Locale.ROOT)) {
            case "de" -> "Deutsch";
            case "ru" -> "Русский";
            case "es" -> "Español";
            case "fr" -> "Français";
            case "ja" -> "日本語";
            case "zh" -> "中文";
            default -> "English";
        };
    }

    /** Turns TOP_LEFT into Top Left. */
    private static String cornerLabel(String value) {
        StringBuilder out = new StringBuilder();
        for (String part : value.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }

    private static String startViewLabel(String value) {
        return switch (value == null ? "" : value.toUpperCase(java.util.Locale.ROOT)) {
            case "SEARCH" -> Lang.plain("gui.search");
            case "LIBRARY" -> Lang.plain("gui.library");
            case "LIKED" -> Lang.plain("gui.liked");
            default -> Lang.plain("gui.home");
        };
    }

    private static String overlayScaleLabel(int value) {
        return value <= 0 ? "Same as game" : value + "x";
    }

    private static String keepModeLabel(String mode) {
        return switch (mode) {
            case "COUNT" -> "Over the kept count";
            case "SECONDS" -> "After the seconds";
            default -> "When the line ends";
        };
    }

    private static String cycle(String[] values, String current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(current)) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    /**
     * The capture device with its kind in front of its name.
     *
     * <p>The kind leads because the control is narrow enough to clip a long device name, and of the
     * two halves the kind is the one that decides whether the party hears Spotify or the room. A
     * clipped name is still recognisable; a clipped kind would leave the row saying exactly as
     * little as it did while the bug went unnoticed.</p>
     */
    private static String captureDeviceLabel() {
        String device = ConfigManager.client().captureDeviceName;
        if (device.isBlank()) {
            return Lang.plain("audio.no_device");
        }
        String kind = Lang.plain(AudioDevices.isLoopback(device) ? "audio.kind_output" : "audio.kind_input");
        // The kind still comes from the classification, the name no longer comes from the config
        // as it is stored: the speaker mix is kept under an internal marker, and printing that
        // marker would put a token where the row promises a device.
        return kind + " - " + ClientState.captureSourceName(device);
    }

    /**
     * Steps through the devices in the order AudioDevices classified them, which puts the loopback
     * candidates first, so the first press lands on something that can carry Spotify rather than on
     * whichever microphone the sound system happened to report first. From nothing selected the
     * index is -1 and the step lands on entry zero, which on Windows is the speaker mix - the one
     * source that is there without anything being installed.
     */
    private static void cycleCaptureDevice() {
        List<AudioDevices.CaptureDevice> devices = AudioDevices.listDevices();
        if (devices.isEmpty()) {
            ClientState.toast(Lang.plain("audio.no_device"));
            return;
        }
        int index = indexOfDevice(devices, ConfigManager.client().captureDeviceName);
        AudioDevices.CaptureDevice next = devices.get((index + 1) % devices.size());
        ConfigManager.client().captureDeviceName = next.name();
        ConfigManager.saveClient();
        if (!next.loopback()) {
            // Said at the moment of choosing, while the user can still press again. The broadcast
            // warns a second time, but by then the microphone is already on the air.
            ClientState.toast(Lang.plain("audio.not_loopback", "device",
                    ClientState.captureSourceName(next.name())));
        }
    }

    /** Position of a stored device name in the classified list, or -1 when it is gone or unset. */
    private static int indexOfDevice(List<AudioDevices.CaptureDevice> devices, String name) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
