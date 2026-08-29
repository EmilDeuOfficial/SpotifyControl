package de.emilo.spotifycontrol.client;

import de.emilo.spotifycontrol.gui.Theme;
import de.emilo.spotifycontrol.net.payload.PartyStatePayload;
import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Decides whether a player's name should carry the streaming glyph. The rule lives here instead of
 * in the mixin so it stays readable, testable and reusable: the mixin is only the hook that asks.
 */
public final class StreamMarker {

    private StreamMarker() {
    }

    /**
     * Returns {@code name} unchanged unless {@code playerId} belongs to the local player's party.
     * The caller can compare the result against what it passed in: the same instance back means
     * nothing was decided and nothing was allocated.
     *
     * <p>The mark says "listening with you", not "sending audio", so it does not depend on
     * {@link PartyStatePayload#streaming()}: a SYNC party never streams anything and its members
     * are still listening together. Everyone in the roster carries it, the host included.
     *
     * <p>This runs for every player entity on every frame, so the common case of no party must cost
     * nothing beyond the single volatile read below.
     */
    public static Component decorate(UUID playerId, Component name) {
        if (playerId == null) {
            return name;
        }
        // The server's list of everyone in a party, not the local player's own party. The mark says
        // "this one is listening", which is worth knowing about a stranger walking past just as much
        // as about the people already listening along, so it must not depend on sharing a party with
        // them - or on being in one at all.
        List<UUID> listeners = ClientState.partyMarkers().listeners();
        if (listeners.isEmpty()) {
            return name;
        }
        // Indexed rather than for-each: a handful of ids is nothing to scan, but an iterator would
        // be one allocation per player per frame.
        boolean listening = false;
        for (int i = 0; i < listeners.size(); i++) {
            if (playerId.equals(listeners.get(i))) {
                listening = true;
                break;
            }
        }
        if (!listening) {
            return name;
        }
        // The glyph carries a Style with the marker font. An empty, unstyled root keeps that font
        // from leaking onto the siblings, which would render the marked name as glyph garbage.
        // The colour is whatever accent the reader chose in their own settings, so the mark belongs
        // to the interface they are looking at rather than being one more fixed green.
        return Component.empty()
                .append(Platform.streamGlyph().withColor(Theme.accent() & 0xFFFFFF))
                .append(" ")
                .append(name);
    }
}
