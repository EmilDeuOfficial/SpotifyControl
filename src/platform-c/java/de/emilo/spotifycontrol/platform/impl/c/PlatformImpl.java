package de.emilo.spotifycontrol.platform.impl.c;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.gui.View;
import de.emilo.spotifycontrol.platform.PlatformApi;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public final class PlatformImpl implements PlatformApi {

    public static final String KEY_CATEGORY = "category.spotifycontrol";

    private ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SpotifyControl.MOD_ID, path);
    }

    @Override
    public Object registerTexture(String path, NativeImage image) {
        ResourceLocation location = id(path);
        Minecraft.getInstance().getTextureManager().register(location,
                new DynamicTexture(() -> "spotifycontrol/" + path, image));
        return location;
    }

    @Override
    public void releaseTexture(Object handle) {
        Minecraft.getInstance().getTextureManager().release((ResourceLocation) handle);
    }

    @Override
    public void drawTexture(GuiGraphics graphics, Object handle, int x, int y, int width, int height) {
        graphics.blit(RenderType::guiTextured, (ResourceLocation) handle, x, y, 0.0f, 0.0f, width, height,
                width, height);
    }

    @Override
    public void pushScale(GuiGraphics graphics, float translateX, float translateY, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(translateX, translateY, 0.0f);
        graphics.pose().scale(scale, scale, 1.0f);
    }

    @Override
    public void popMatrix(GuiGraphics graphics) {
        graphics.pose().popPose();
    }

    @Override
    public KeyMapping keyMapping(String translationKey, int defaultKey) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyMapping(translationKey, InputConstants.Type.KEYSYM, defaultKey, KEY_CATEGORY));
    }

    @Override
    public boolean isKeyDown(Minecraft client, int key) {
        return InputConstants.isKeyDown(client.getWindow().getWindow(), key);
    }

    @Override
    public int guiScale() {
        return Math.max(1, (int) Math.round((double) Minecraft.getInstance().getWindow().getGuiScale()));
    }

    @Override
    public boolean controlDown() {
        return Screen.hasControlDown();
    }

    @Override
    public void copyToClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    @Override
    public void openUri(String uri) {
        Util.getPlatform().openUri(URI.create(uri));
    }

    @Override
    public Predicate<CommandSourceStack> permission(int level) {
        return source -> source.hasPermission(level);
    }

    @Override
    public String playerName(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    @Override
    public MinecraftServer serverOf(ServerPlayer player) {
        return player.getServer();
    }

    @Override
    public void stopMusic(Minecraft client) {
        client.getMusicManager().stopPlaying();
    }

    @Override
    public void registerHud(Consumer<GuiGraphics> renderer) {
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> renderer.accept(graphics));
    }

    @Override
    public boolean click(GuiEventListener widget, double mouseX, double mouseY, int button) {
        return widget.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPress(GuiEventListener widget, int keyCode, int scanCode, int modifiers) {
        return widget.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(GuiEventListener widget, int codepoint, int modifiers) {
        boolean handled = false;
        for (char unit : Character.toChars(codepoint)) {
            handled |= widget.charTyped(unit, modifiers);
        }
        return handled;
    }

    @Override
    public void drawString(GuiGraphics graphics, Font font, String text, int x, int y, int color,
                                  boolean shadow) {
        graphics.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void sendMessage(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
    }

    @Override
    public Object levelOf(ServerPlayer player) {
        return player.level();
    }

    @Override
    public <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(id(path));
    }

    @Override
    public void openOverlay() {
        Minecraft.getInstance().setScreen(new OverlayScreen());
    }

    @Override
    public Screen settingsScreen(Screen parent) {
        return new OverlayScreen(parent, View.SETTINGS);
    }

    @Override
    public List<String> onlinePlayerNames(Minecraft client) {
        List<String> names = new ArrayList<>();
        if (client.getConnection() == null || client.player == null) {
            return names;
        }
        String self = client.player.getGameProfile().getName();
        for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().getName();
            if (!name.equals(self)) {
                names.add(name);
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    @Override
    public MutableComponent streamGlyph() {
        return Component.literal("\uE000").withStyle(Style.EMPTY.withFont(id("marker")));
    }
}
