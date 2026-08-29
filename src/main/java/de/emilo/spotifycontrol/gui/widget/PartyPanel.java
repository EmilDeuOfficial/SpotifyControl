package de.emilo.spotifycontrol.gui.widget;

import de.emilo.spotifycontrol.client.ClientState;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.gui.Anim;
import de.emilo.spotifycontrol.gui.Icons;
import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.net.payload.PartyStatePayload;
import de.emilo.spotifycontrol.net.payload.ServerSettingsPayload;
import de.emilo.spotifycontrol.voice.AudioDevices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import de.emilo.spotifycontrol.platform.Platform;

public class PartyPanel {

    private static final int CARD_HEIGHT = 66;
    private static final int ROW_HEIGHT = 22;
    private static final int CONTROLS_HEIGHT = 46;
    // The warning needs a line of its own below the stream button, and red text pressed against a
    // rounded edge reads as a rendering fault rather than as a message, so the card grows for it
    // instead of the text being squeezed into the padding that is already there.
    private static final int CONTROLS_WARNING_HEIGHT = 58;

    private final ScrollArea scroll = new ScrollArea();

    public ScrollArea scroll() {
        return scroll;
    }
    private String selectedMode = "SYNC";

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int rosterY;
    private int modeSwitchX;
    private int modeSwitchY;
    private int modeSwitchWidth;

    public void render(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        panelX = x;
        panelY = y;
        panelWidth = width;
        panelHeight = height;

        PartyStatePayload party = ClientState.party();
        ServerSettingsPayload settings = ClientState.serverSettings();

        Platform.drawString(graphics, font, Lang.plain("gui.party"), x, y, Theme.TEXT, false);

        if (!party.inParty()) {
            renderModeCards(graphics, font, x, y + 18, width, mouseX, mouseY, settings);
            renderPrimaryButton(graphics, font, "party.start", x, y + 18 + CARD_HEIGHT + 12, 150,
                    Lang.plain("gui.party_start"), mouseX, mouseY, settings.partiesAllowed());
            // The start button ends 24 px below its own top, the caption has to clear that.
            rosterY = y + 18 + CARD_HEIGHT + 58;
            Platform.drawString(graphics, font, Lang.plain("gui.party_listeners", "count", 0), x, rosterY - 14,
                    Theme.TEXT_MUTED, false);
            renderRoster(graphics, font, x, rosterY, width, y + height - rosterY, mouseX, mouseY, "Join");
            return;
        }

        int infoY = y + 20;
        Theme.rounded(graphics, x, infoY, width, 44, 6, Theme.SURFACE);
        Icons.people(graphics, x + 22, infoY + 22, 16, Theme.GREEN);
        String modeLabel = "BROADCAST".equals(party.mode())
                ? Lang.plain("gui.party_mode_broadcast")
                : Lang.plain("gui.party_mode_sync");
        Platform.drawString(graphics, font, party.hostName(), x + 44, infoY + 12, Theme.TEXT, false);
        String modeLine = modeLabel + "  -  " + Lang.plain("gui.party_listeners",
                "count", party.members().size());
        boolean modeHovered = party.host()
                && Theme.hovered(mouseX, mouseY, x + 44, infoY + 22, font.width(modeLine), 12);
        Platform.drawString(graphics, font, modeLine, x + 44, infoY + 25 + Anim.pressInset("party.mode", 1),
                Anim.hoverColor("party.mode", modeHovered, Theme.TEXT_MUTED, Theme.GREEN_BRIGHT), false);
        modeSwitchX = x + 44;
        modeSwitchY = infoY + 22;
        modeSwitchWidth = font.width(modeLine);

        renderPrimaryButton(graphics, font, "party.stop", x + width - 120, infoY + 12, 108,
                Lang.plain("gui.party_stop"), mouseX, mouseY, true);

        int nextY = infoY + 56;
        if (party.host() && "BROADCAST".equals(party.mode())) {
            nextY = renderBroadcastControls(graphics, font, x, nextY, width, mouseX, mouseY);
        }

        rosterY = nextY + 20;
        String listeners = Lang.plain("gui.party_listeners", "count", party.members().size());
        Platform.drawString(graphics, font, listeners, x, rosterY - 14, Theme.TEXT_MUTED, false);
        if (party.members().size() <= 1 && Anim.enabled()) {
            // The host is always in that list, so a party of one is a party still waiting for its
            // first guest. The dots are the wait itself and have no meaning standing still.
            Anim.dots(graphics, x + font.width(listeners) + 6, rosterY - 10, Theme.TEXT_MUTED);
        }
        renderRoster(graphics, font, x, rosterY, width, y + height - rosterY, mouseX, mouseY,
                party.host() ? "Invite" : null);
    }

    private void renderModeCards(GuiGraphics graphics, Font font, int x, int y, int width, int mouseX, int mouseY,
                                 ServerSettingsPayload settings) {
        int cardWidth = Math.min(220, (width - 12) / 2);
        drawModeCard(graphics, font, 0, x, y, cardWidth, mouseX, mouseY, "SYNC",
                Lang.plain("gui.party_mode_sync"),
                "Everyone plays the same track on their own Spotify account.", true);
        drawModeCard(graphics, font, 1, x + cardWidth + 12, y, cardWidth, mouseX, mouseY, "BROADCAST",
                Lang.plain("gui.party_mode_broadcast"),
                "Your desktop audio is streamed through Simple Voice Chat.", settings.broadcastAllowed());
    }

    private void drawModeCard(GuiGraphics graphics, Font font, int index, int x, int y, int width, int mouseX,
                              int mouseY, String mode, String title, String description, boolean enabled) {
        boolean selected = selectedMode.equals(mode);
        boolean hovered = enabled && Theme.hovered(mouseX, mouseY, x, y, width, CARD_HEIGHT);
        float lift = Anim.hover("party.card:" + index, hovered);
        float chosen = Anim.to("party.card.on:" + index, selected ? 1.0f : 0.0f, Anim.FAST);
        // Both colours below reach a rounded rectangle, whose corners are rasterized once per exact
        // colour and cached under it, so a value still moving has to be snapped before it gets there.
        int background = Anim.mix(Anim.mix(Theme.ELEVATED, Theme.SURFACE, lift), Theme.SURFACE_HOVER, chosen);
        int inset = Anim.pressInset("party.card:" + index, 2);
        int drop = Anim.pressInset("party.card:" + index, 1);
        if (chosen > 0.0f) {
            // The ring is faded in over the card rather than switched on, so picking the other mode
            // hands the outline across instead of moving it in one frame.
            Theme.roundedBorder(graphics, x + inset, y + inset, width - 2 * inset, CARD_HEIGHT - 2 * inset, 6,
                    Anim.fade(Theme.GREEN, chosen), background);
        } else {
            Theme.rounded(graphics, x + inset, y + inset, width - 2 * inset, CARD_HEIGHT - 2 * inset, 6, background);
        }
        int titleColor = enabled ? Theme.TEXT : Theme.TEXT_DIM;
        Platform.drawString(graphics, font, title, x + 12, y + 12 + drop, titleColor, false);
        List<String> lines = wrap(font, description, width - 24);
        for (int i = 0; i < lines.size() && i < 3; i++) {
            Platform.drawString(graphics, font, lines.get(i), x + 12, y + 28 + i * 10 + drop,
                    enabled ? Theme.TEXT_MUTED : Theme.TEXT_DIM, false);
        }
        if (!enabled) {
            Platform.drawString(graphics, font, Lang.plain("party.broadcast_disabled"), x + 12, y + CARD_HEIGHT - 14,
                    Theme.RED, false);
        }
    }

    private int renderBroadcastControls(GuiGraphics graphics, Font font, int x, int y, int width, int mouseX,
                                        int mouseY) {
        boolean capturing = ClientState.capturing();
        String device = ConfigManager.client().captureDeviceName;
        // Broadcasting a microphone instead of the music is silent at the sending end: the host
        // hears their own Spotify either way and being muted in the voice chat changes nothing,
        // because the capture is a second line straight from the sound system. Only the listeners
        // notice, and by then the session is over. A toast lasts four seconds, so the warning has
        // to stay up for as long as the stream does, which is what this line and the taller card
        // below are for.
        boolean notLoopback = capturing && !AudioDevices.isLoopback(device);
        int cardHeight = notLoopback ? CONTROLS_WARNING_HEIGHT : CONTROLS_HEIGHT;
        Theme.rounded(graphics, x, y, width, cardHeight, 6, Theme.SURFACE);
        Platform.drawString(graphics, font, "Audio source", x + 12, y + 10, Theme.TEXT_MUTED, false);
        // Through the same unpacking the settings row uses. A stored endpoint is an id with the
        // name appended, and printing that raw would put a token where the card promises a device.
        String shown = device.isBlank() ? Lang.plain("audio.no_device") : ClientState.captureSourceName(device);
        boolean deviceHovered = Theme.hovered(mouseX, mouseY, x + 12, y + 24, width - 150, 12);
        String deviceLine = Theme.fit(font, shown, width - 160);
        Platform.drawString(graphics, font, deviceLine, x + 12, y + 24 + Anim.pressInset("party.device", 1),
                Anim.hoverColor("party.device", deviceHovered, Theme.TEXT, Theme.GREEN_BRIGHT), false);

        String label = capturing ? "Stop streaming" : "Start streaming";
        renderPrimaryButton(graphics, font, "party.stream", x + width - 128, y + 16, 116, label, mouseX, mouseY,
                true);
        if (capturing) {
            // A recording light that breathes out of its own colour, so one glance says the stream
            // is running and a still interface still shows the plain dot. Centred on the button it
            // belongs to, which starts at y + 16 and is 24 tall.
            Theme.circle(graphics, x + width - 138, y + 28, 3,
                    Anim.mix(Theme.GREEN_BRIGHT, Theme.GREEN_DARK, Anim.pulse(1.5f) * 0.7f));
        }
        if (notLoopback) {
            // Clear of the button, which ends at y + 40, so the whole card width is available and
            // Theme.fit only has to shorten the line on a narrow panel.
            Platform.drawString(graphics, font, Theme.fit(font, Lang.plain("audio.not_loopback"), width - 24),
                    x + 12, y + 40, Theme.RED, false);
        }
        return y + cardHeight;
    }

    private void renderRoster(GuiGraphics graphics, Font font, int x, int y, int width, int height, int mouseX,
                              int mouseY, String action) {
        if (height <= 0) {
            return;
        }
        List<String> names = onlinePlayers();
        scroll.setViewportHeight(height);
        scroll.setContentHeight(names.size() * ROW_HEIGHT);
        Theme.scissor(graphics, x, y, x + width, y + height);
        int offset = scroll.offset();
        for (int index = 0; index < names.size(); index++) {
            int rowY = y + index * ROW_HEIGHT - offset;
            if (rowY + ROW_HEIGHT < y || rowY > y + height) {
                continue;
            }
            boolean hovered = action != null
                    && Theme.hovered(mouseX, mouseY, x, rowY, width - 6, ROW_HEIGHT - 2)
                    && mouseY >= y && mouseY < y + height
                    && !scroll.claims(mouseX, mouseY);
            // Faded rather than switched, so the highlight also has a way out once the cursor has
            // moved on to the next name.
            float lift = Anim.hover("party.row:" + index, hovered);
            int inset = Anim.pressInset("party.row:" + index, 1);
            if (lift > 0.0f) {
                Theme.rounded(graphics, x + inset, rowY + inset, width - 6 - 2 * inset, ROW_HEIGHT - 2 - 2 * inset, 4,
                        Anim.fade(Theme.SURFACE_HOVER, lift));
            }
            Platform.drawString(graphics, font, names.get(index), x + 10, rowY + 6, Theme.TEXT, false);
            if (action != null) {
                int actionWidth = font.width(action);
                int actionX = x + width - actionWidth - 18;
                Platform.drawString(graphics, font, action, actionX, rowY + 6,
                        Theme.mix(Theme.TEXT_MUTED, Theme.GREEN_BRIGHT, lift), false);
            }
        }
        Theme.endScissor(graphics);
        scroll.renderScrollbar(graphics, x + width - 4, y, height);
    }

    private void renderPrimaryButton(GuiGraphics graphics, Font font, String id, int x, int y, int width,
                                     String label, int mouseX, int mouseY, boolean enabled) {
        boolean hovered = enabled && Theme.hovered(mouseX, mouseY, x, y, width, 24);
        int color = !enabled ? Theme.ELEVATED
                : Anim.mix(Theme.GREEN, Theme.GREEN_BRIGHT, Anim.hover(id, hovered));
        int inset = Anim.pressInset(id, 2);
        Theme.rounded(graphics, x + inset, y + inset, width - 2 * inset, 24 - 2 * inset, 12, color);
        int textColor = enabled ? Theme.BLACK : Theme.TEXT_DIM;
        // Two depths off the same press. The plate gives two pixels because a button this size has
        // that much to give, but a label cannot shrink by a pixel without going soft, so it travels
        // instead - one pixel into the button while pressed, one proud of it on the rebound.
        Platform.drawString(graphics, font, label, x + (width - font.width(label)) / 2,
                y + 8 + Anim.pressInset(id, 1), textColor, false);
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (!Theme.hovered(mx, my, panelX, panelY, panelWidth, panelHeight)) {
            return false;
        }
        PartyStatePayload party = ClientState.party();
        ServerSettingsPayload settings = ClientState.serverSettings();

        if (!party.inParty()) {
            int cardWidth = Math.min(220, (panelWidth - 12) / 2);
            int cardY = panelY + 18;
            if (Theme.hovered(mx, my, panelX, cardY, cardWidth, CARD_HEIGHT)) {
                Anim.click("party.card:0");
                selectedMode = "SYNC";
                return true;
            }
            if (Theme.hovered(mx, my, panelX + cardWidth + 12, cardY, cardWidth, CARD_HEIGHT)
                    && settings.broadcastAllowed()) {
                Anim.click("party.card:1");
                selectedMode = "BROADCAST";
                return true;
            }
            if (Theme.hovered(mx, my, panelX, cardY + CARD_HEIGHT + 12, 150, 24) && settings.partiesAllowed()) {
                Anim.click("party.start");
                ClientState.startParty(selectedMode);
                return true;
            }
        } else {
            int infoY = panelY + 20;
            if (Theme.hovered(mx, my, panelX + panelWidth - 120, infoY + 12, 108, 24)) {
                Anim.click("party.stop");
                if (party.host()) {
                    ClientState.stopParty();
                } else {
                    ClientState.leaveParty();
                }
                return true;
            }
            if (party.host() && Theme.hovered(mx, my, modeSwitchX, modeSwitchY, modeSwitchWidth, 12)) {
                Anim.click("party.mode");
                ClientState.setPartyMode("BROADCAST".equals(party.mode()) ? "SYNC" : "BROADCAST");
                return true;
            }
            if (party.host() && "BROADCAST".equals(party.mode())) {
                int controlsY = infoY + 56;
                if (Theme.hovered(mx, my, panelX + 12, controlsY + 24, panelWidth - 150, 12)) {
                    Anim.click("party.device");
                    cycleCaptureDevice();
                    return true;
                }
                if (Theme.hovered(mx, my, panelX + panelWidth - 128, controlsY + 16, 116, 24)) {
                    Anim.click("party.stream");
                    if (ClientState.capturing()) {
                        ClientState.stopCapture();
                    } else {
                        ClientState.startCapture();
                    }
                    return true;
                }
            }
        }

        List<String> names = onlinePlayers();
        if (my >= rosterY && mx < panelX + panelWidth - 6) {
            int localY = my - rosterY + scroll.offset();
            if (localY % ROW_HEIGHT >= ROW_HEIGHT - 2) {
                return true;
            }
            int index = localY / ROW_HEIGHT;
            if (index >= 0 && index < names.size()) {
                Anim.click("party.row:" + index);
                if (party.inParty() && party.host()) {
                    ClientState.inviteToParty(names.get(index));
                } else if (!party.inParty()) {
                    ClientState.joinParty(names.get(index));
                }
                return true;
            }
        }
        return true;
    }

    /**
      * Steps through the same classified list the settings row uses. It used to walk the raw
      * javax.sound list instead, which on Windows is every microphone the machine has - so this row,
      * which is the one people actually reach for, kept offering the very devices the rest of the
      * mod had stopped offering.
      */
    private void cycleCaptureDevice() {
        List<AudioDevices.CaptureDevice> devices = AudioDevices.listDevices();
        if (devices.isEmpty()) {
            ClientState.toast(Lang.plain("audio.no_device"));
            return;
        }
        String current = ConfigManager.client().captureDeviceName;
        int index = -1;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).name().equals(current)) {
                index = i;
                break;
            }
        }
        ConfigManager.client().captureDeviceName = devices.get((index + 1) % devices.size()).name();
        ConfigManager.saveClient();
    }

    public boolean mouseScrolled(double amount) {
        return scroll.scrollBy(amount);
    }

    private static List<String> onlinePlayers() {
        return Platform.onlinePlayerNames(Minecraft.getInstance());
    }

    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.width(candidate) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }
}
