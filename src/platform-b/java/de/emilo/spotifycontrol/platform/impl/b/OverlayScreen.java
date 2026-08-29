package de.emilo.spotifycontrol.platform.impl.b;

import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.gui.SpotifyOverlay;
import de.emilo.spotifycontrol.gui.View;
import de.emilo.spotifycontrol.platform.Platform;
import de.emilo.spotifycontrol.platform.ScreenHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Platform.setClickContext(0, false);
        return overlay.onMouseClick(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        overlay.onMouseRelease(button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return overlay.onMouseDrag(mouseX, mouseY, button)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return overlay.onMouseScroll(mouseX, mouseY, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return overlay.onKeyPress(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        return overlay.onCharTyped(character, modifiers) || super.charTyped(character, modifiers);
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
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void hostClose() {
        this.onClose();
    }
}
