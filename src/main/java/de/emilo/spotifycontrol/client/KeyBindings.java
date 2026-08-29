package de.emilo.spotifycontrol.client;

import de.emilo.spotifycontrol.config.ConfigManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import de.emilo.spotifycontrol.platform.Platform;

public final class KeyBindings {

    private static KeyMapping open;
    private static KeyMapping playPause;
    private static KeyMapping next;
    private static KeyMapping previous;
    private static KeyMapping volumeUp;
    private static KeyMapping volumeDown;
    private static KeyMapping toggleHud;

    private KeyBindings() {
    }

    public static void register() {
        open = bind("key.spotifycontrol.open", GLFW.GLFW_KEY_M);
        playPause = bind("key.spotifycontrol.playpause", GLFW.GLFW_KEY_K);
        next = bind("key.spotifycontrol.next", GLFW.GLFW_KEY_PERIOD);
        previous = bind("key.spotifycontrol.previous", GLFW.GLFW_KEY_COMMA);
        volumeUp = bind("key.spotifycontrol.volume_up", GLFW.GLFW_KEY_PAGE_UP);
        volumeDown = bind("key.spotifycontrol.volume_down", GLFW.GLFW_KEY_PAGE_DOWN);
        toggleHud = bind("key.spotifycontrol.toggle_hud", GLFW.GLFW_KEY_H);
    }

    private static KeyMapping bind(String translationKey, int defaultKey) {
        return Platform.keyMapping(translationKey, defaultKey);
    }

    private static boolean customOpenDown;

    public static void tick(Minecraft client) {
        boolean modifier = modifierHeld(client);
        int custom = ConfigManager.client().overlayKeyCode;
        if (custom > 0) {
            // A key chosen inside the overlay replaces the game's binding for opening it.
            boolean down = Platform.isKeyDown(client, custom);
            if (down && !customOpenDown && (modifier || !ConfigManager.client().requireModifierKey)) {
                Platform.openOverlay();
            }
            customOpenDown = down;
            while (open.consumeClick()) {
                // swallow the binding so it cannot fire a second time
            }
        } else if (consume(open, modifier)) {
            Platform.openOverlay();
        }
        if (consume(playPause, modifier)) {
            ClientState.session().togglePlayback();
        }
        if (consume(next, modifier)) {
            ClientState.session().nextTrack();
        }
        if (consume(previous, modifier)) {
            ClientState.session().previousTrack();
        }
        int step = Math.max(1, ConfigManager.client().overlayVolumeStep);
        if (consume(volumeUp, modifier)) {
            ClientState.session().changeVolume(step);
        }
        if (consume(volumeDown, modifier)) {
            ClientState.session().changeVolume(-step);
        }
        if (consume(toggleHud, modifier)) {
            ConfigManager.client().showNowPlayingHud = !ConfigManager.client().showNowPlayingHud;
            ConfigManager.saveClient();
        }
    }

    private static boolean consume(KeyMapping mapping, boolean modifierHeld) {
        boolean triggered = false;
        while (mapping.consumeClick()) {
            triggered = true;
        }
        return triggered && (modifierHeld || !ConfigManager.client().requireModifierKey);
    }

    public static boolean modifierHeld(Minecraft client) {
        return switch (ConfigManager.client().modifierKey.toUpperCase()) {
            case "SHIFT" -> down(client, GLFW.GLFW_KEY_LEFT_SHIFT) || down(client, GLFW.GLFW_KEY_RIGHT_SHIFT);
            case "ALT" -> down(client, GLFW.GLFW_KEY_LEFT_ALT) || down(client, GLFW.GLFW_KEY_RIGHT_ALT);
            case "NONE" -> true;
            default -> down(client, GLFW.GLFW_KEY_LEFT_CONTROL) || down(client, GLFW.GLFW_KEY_RIGHT_CONTROL);
        };
    }

    /** Readable name of a GLFW key code, for the settings row. */
    public static String keyName(int code) {
        if (code <= 0) {
            return "";
        }
        String named = switch (code) {
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_INSERT -> "Insert";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_RIGHT -> "Right";
            case GLFW.GLFW_KEY_LEFT -> "Left";
            case GLFW.GLFW_KEY_DOWN -> "Down";
            case GLFW.GLFW_KEY_UP -> "Up";
            case GLFW.GLFW_KEY_PAGE_UP -> "Page up";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "Page down";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            default -> "";
        };
        if (!named.isEmpty()) {
            return named;
        }
        if (code >= GLFW.GLFW_KEY_F1 && code <= GLFW.GLFW_KEY_F25) {
            return "F" + (code - GLFW.GLFW_KEY_F1 + 1);
        }
        if (Minecraft.getInstance().getWindow() != null) {
            String name = GLFW.glfwGetKeyName(code, 0);
            if (name != null && !name.isBlank()) {
                return name.toUpperCase(java.util.Locale.ROOT);
            }
        }
        return "Key " + code;
    }

    public static String openCombination() {
        int custom = ConfigManager.client().overlayKeyCode;
        String base = custom > 0
                ? keyName(custom)
                : open == null || Minecraft.getInstance().getWindow() == null
                        ? "M"
                        : open.getTranslatedKeyMessage().getString();
        if (!ConfigManager.client().requireModifierKey || "NONE".equalsIgnoreCase(ConfigManager.client().modifierKey)) {
            return base;
        }
        return ConfigManager.client().modifierKey.charAt(0)
                + ConfigManager.client().modifierKey.substring(1).toLowerCase() + " + " + base;
    }

    private static boolean down(Minecraft client, int key) {
        return Platform.isKeyDown(client, key);
    }
}
