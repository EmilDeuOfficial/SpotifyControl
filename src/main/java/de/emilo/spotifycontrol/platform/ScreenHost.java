package de.emilo.spotifycontrol.platform;

import net.minecraft.client.gui.GuiGraphics;

public interface ScreenHost {

    int hostWidth();

    int hostHeight();

    void hostRenderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    void hostClose();
}
