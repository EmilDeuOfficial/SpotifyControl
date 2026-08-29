package de.emilo.spotifycontrol.platform;

import com.mojang.blaze3d.platform.NativeImage;
import de.emilo.spotifycontrol.SpotifyControl;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class Platform {

    private static final String IMPL_PACKAGE = "de.emilo.spotifycontrol.platform.impl.";

    private static PlatformApi api;

    private Platform() {
    }

    public static PlatformApi api() {
        if (api == null) {
            api = resolve();
        }
        return api;
    }

    public static String generation() {
        int patch = minecraftPatch();
        if (patch <= 1) {
            return "a";
        }
        if (patch <= 4) {
            return "b";
        }
        if (patch == 5) {
            return "c";
        }
        if (patch <= 8) {
            return "d";
        }
        if (patch <= 10) {
            return "e";
        }
        return "f";
    }

    public static int minecraftPatch() {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("1.21");
        String[] parts = version.split("\\.");
        if (parts.length < 3) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : parts[2].toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }

    private static PlatformApi resolve() {
        String generation = generation();
        try {
            Class<?> type = Class.forName(IMPL_PACKAGE + generation + ".PlatformImpl");
            PlatformApi resolved = (PlatformApi) type.getDeclaredConstructor().newInstance();
            SpotifyControl.LOGGER.info("Using platform generation {}", generation);
            return resolved;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SpotifyControl has no platform support for this Minecraft version", e);
        }
    }

    public static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return api().payloadType(path);
    }

    public static Object registerTexture(String path, NativeImage image) {
        return api().registerTexture(path, image);
    }

    public static void releaseTexture(Object handle) {
        api().releaseTexture(handle);
    }

    public static void drawTexture(GuiGraphics graphics, Object handle, int x, int y, int width, int height) {
        api().drawTexture(graphics, handle, x, y, width, height);
    }

    public static void drawString(GuiGraphics graphics, Font font, String text, int x, int y, int color,
                                  boolean shadow) {
        api().drawString(graphics, font, text, x, y, color, shadow);
    }

    public static void pushScale(GuiGraphics graphics, float translateX, float translateY, float scale) {
        api().pushScale(graphics, translateX, translateY, scale);
    }

    public static void popMatrix(GuiGraphics graphics) {
        api().popMatrix(graphics);
    }

    public static KeyMapping keyMapping(String translationKey, int defaultKey) {
        return api().keyMapping(translationKey, defaultKey);
    }

    public static boolean isKeyDown(Minecraft client, int key) {
        return api().isKeyDown(client, key);
    }

    public static int guiScale() {
        return api().guiScale();
    }

    public static boolean scissorFollowsPose() {
        return minecraftPatch() >= 4;
    }

    public static Screen settingsScreen(Screen parent) {
        return api().settingsScreen(parent);
    }

    public static boolean controlDown() {
        return api().controlDown();
    }

    public static void openOverlay() {
        api().openOverlay();
    }

    public static void openUri(String uri) {
        api().openUri(uri);
    }

    public static void copyToClipboard(String text) {
        api().copyToClipboard(text);
    }

    public static Predicate<CommandSourceStack> permission(int level) {
        return api().permission(level);
    }

    public static String playerName(ServerPlayer player) {
        return api().playerName(player);
    }

    public static MinecraftServer serverOf(ServerPlayer player) {
        return api().serverOf(player);
    }

    public static Object levelOf(ServerPlayer player) {
        return api().levelOf(player);
    }

    public static void sendMessage(ServerPlayer player, Component message) {
        api().sendMessage(player, message);
    }

    public static void stopMusic(Minecraft client) {
        api().stopMusic(client);
    }

    public static void registerHud(Consumer<GuiGraphics> renderer) {
        api().registerHud(renderer);
    }

    public static boolean click(GuiEventListener widget, double mouseX, double mouseY, int button) {
        return api().click(widget, mouseX, mouseY, button);
    }

    public static boolean keyPress(GuiEventListener widget, int keyCode, int scanCode, int modifiers) {
        return api().keyPress(widget, keyCode, scanCode, modifiers);
    }

    public static boolean charTyped(GuiEventListener widget, int codepoint, int modifiers) {
        return api().charTyped(widget, codepoint, modifiers);
    }

    private static int clickModifiers;
    private static boolean clickDouble;

    public static void setClickContext(int modifiers, boolean doubleClick) {
        clickModifiers = modifiers;
        clickDouble = doubleClick;
    }

    public static int clickModifiers() {
        return clickModifiers;
    }

    public static boolean clickDouble() {
        return clickDouble;
    }

    public static List<String> onlinePlayerNames(Minecraft client) {
        return api().onlinePlayerNames(client);
    }

    public static MutableComponent streamGlyph() {
        return api().streamGlyph();
    }
}
