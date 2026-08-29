package de.emilo.spotifycontrol.platform.impl.e;

import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.gui.SpotifyOverlay;
import de.emilo.spotifycontrol.gui.View;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.platform.ScreenHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class OverlayScreen extends Screen implements ScreenHost {

    private final SpotifyOverlay overlay;
    private final Screen parent;

    public OverlayScreen() {
        // null lets the overlay pick the page chosen in the settings
        this(null, null);
    }

    public OverlayScreen(Screen parent, View initialView) {
        super(Component.literal("SpotifyControl"));
        this.parent = parent;
        this.overlay = new SpotifyOverlay(this, initialView);
    }

    @Override
    protected void init() {
        overlay.init();
    }

    @Override
    public void tick() {
        overlay.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return ConfigManager.client().pauseGameOnOverlay;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        overlay.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Platform.setClickContext(event.modifiers(), doubleClick);
        return overlay.onMouseClick(event.x(), event.y(), event.button())
                || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        overlay.onMouseRelease(event.button());
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return overlay.onMouseDrag(event.x(), event.y(), event.button())
                || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return overlay.onMouseScroll(mouseX, mouseY, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return overlay.onKeyPress(event.key(), event.scancode(), event.modifiers()) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return overlay.onCharTyped(event.codepoint(), event.modifiers()) || super.charTyped(event);
    }

    @Override
    public void onClose() {
        overlay.onClosed();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public GuiEventListener getFocused() {
        GuiEventListener focused = overlay.focusedWidget();
        return focused != null ? focused : super.getFocused();
    }

    @Override
    public int hostWidth() {
        return this.width;
    }

    @Override
    public int hostHeight() {
        return this.height;
    }

    @Override
    public void hostRenderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (Minecraft.getInstance().level == null) {
            renderPanorama(graphics, partialTick);
        }
        if (ConfigManager.client().blurBackground) {
            renderBlurredBackground(graphics);
        }
    }

    @Override
    public void hostClose() {
        this.onClose();
    }
}
