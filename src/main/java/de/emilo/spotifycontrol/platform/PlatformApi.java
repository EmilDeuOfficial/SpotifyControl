package de.emilo.spotifycontrol.platform;

import com.mojang.blaze3d.platform.NativeImage;
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

public interface PlatformApi {

    <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path);

    Object registerTexture(String path, NativeImage image);

    void releaseTexture(Object handle);

    void drawTexture(GuiGraphics graphics, Object handle, int x, int y, int width, int height);

    void drawString(GuiGraphics graphics, Font font, String text, int x, int y, int color, boolean shadow);

    void pushScale(GuiGraphics graphics, float translateX, float translateY, float scale);

    void popMatrix(GuiGraphics graphics);

    KeyMapping keyMapping(String translationKey, int defaultKey);

    boolean isKeyDown(Minecraft client, int key);

    int guiScale();

    boolean controlDown();

    void openOverlay();

    Screen settingsScreen(Screen parent);

    void openUri(String uri);

    void copyToClipboard(String text);

    Predicate<CommandSourceStack> permission(int level);

    String playerName(ServerPlayer player);

    MinecraftServer serverOf(ServerPlayer player);

    Object levelOf(ServerPlayer player);

    void sendMessage(ServerPlayer player, Component message);

    void stopMusic(Minecraft client);

    void registerHud(Consumer<GuiGraphics> renderer);

    boolean click(GuiEventListener widget, double mouseX, double mouseY, int button);

    boolean keyPress(GuiEventListener widget, int keyCode, int scanCode, int modifiers);

    boolean charTyped(GuiEventListener widget, int codepoint, int modifiers);

    List<String> onlinePlayerNames(Minecraft client);

    /**
     * A display entity transformation. The constructor takes JOML's mutable types up to 1.21.10
     * and their read only interfaces from 1.21.11 on, which is a different method to the JVM, so
     * every generation builds it with its own compiled call.
     */
    com.mojang.math.Transformation transformation(org.joml.Vector3f translation, org.joml.Quaternionf rotation,
                                                  org.joml.Vector3f scale);

    /**
     * The style with another font. The font is named by a resource location up to 1.21.10 and by a
     * font description from 1.21.11 on, so each generation builds it itself.
     */
    net.minecraft.network.chat.Style withFont(net.minecraft.network.chat.Style style, String namespace, String path);

    /**
     * Translates, then turns by {@code rotation} radians, then scales by the two factors, for
     * everything drawn until the matching {@link #popMatrix}. The matrix types differ between
     * the generations: a pose stack up to 1.21.5 and a 2D matrix stack from 1.21.6 on.
     */
    void pushTransform(GuiGraphics graphics, float translateX, float translateY, float rotation, float scaleX,
                       float scaleY);

    /** Draws styled text, so a word can be shown in a font other than the default one. */
    void drawText(GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean shadow);

    /** The mod's own glyph, styled with the font that draws it. */
    MutableComponent streamGlyph();
}
