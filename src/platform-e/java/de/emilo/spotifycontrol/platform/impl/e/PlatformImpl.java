package de.emilo.spotifycontrol.platform.impl.e;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.gui.View;
import de.emilo.spotifycontrol.platform.Platform;
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
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public final class PlatformImpl implements PlatformApi {

    private static KeyMapping.Category keyCategory;

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
        graphics.blit(RenderPipelines.GUI_TEXTURED, (ResourceLocation) handle, x, y, 0.0f, 0.0f, width, height,
                width, height);
    }

    @Override
    public void pushScale(GuiGraphics graphics, float translateX, float translateY, float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(translateX, translateY);
        graphics.pose().scale(scale, scale);
    }

    @Override
    public void popMatrix(GuiGraphics graphics) {
        graphics.pose().popMatrix();
    }

    @Override
    public KeyMapping keyMapping(String translationKey, int defaultKey) {
        if (keyCategory == null) {
            keyCategory = KeyMapping.Category.register(id("main"));
        }
        return KeyBindingHelper.registerKeyBinding(
                new KeyMapping(translationKey, InputConstants.Type.KEYSYM, defaultKey, keyCategory));
    }

    @Override
    public boolean isKeyDown(Minecraft client, int key) {
        return InputConstants.isKeyDown(client.getWindow(), key);
    }

    @Override
    public int guiScale() {
        return Math.max(1, (int) Math.round((double) Minecraft.getInstance().getWindow().getGuiScale()));
    }

    @Override
    public boolean controlDown() {
        Minecraft client = Minecraft.getInstance();
        return isKeyDown(client, GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyDown(client, GLFW.GLFW_KEY_RIGHT_CONTROL);
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
        return player.getGameProfile().name();
    }

    @Override
    public MinecraftServer serverOf(ServerPlayer player) {
        return player.level().getServer();
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
        return widget.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                new MouseButtonInfo(button, Platform.clickModifiers())), Platform.clickDouble());
    }

    @Override
    public boolean keyPress(GuiEventListener widget, int keyCode, int scanCode, int modifiers) {
        return widget.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
    }

    @Override
    public boolean charTyped(GuiEventListener widget, int codepoint, int modifiers) {
        return widget.charTyped(new CharacterEvent(codepoint, modifiers));
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
        String self = client.player.getGameProfile().name();
        for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (!name.equals(self)) {
                names.add(name);
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /**
     * Since 1.21.9 a Style no longer holds a bare resource id, so the id has to be wrapped in a
     * FontDescription before it can be attached.
     */
    @Override
    public MutableComponent streamGlyph() {
        return Component.literal("\uE000")
                .withStyle(Style.EMPTY.withFont(new FontDescription.Resource(id("marker"))));
    }

    @Override
    public com.mojang.math.Transformation transformation(org.joml.Vector3f translation, org.joml.Quaternionf rotation,
                                                         org.joml.Vector3f scale) {
        return new com.mojang.math.Transformation(translation, rotation, scale, new org.joml.Quaternionf());
    }
}
