package de.emilo.spotifycontrol.net.payload;

import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everyone on the server who is currently in a listening party, sent to everyone on the server.
 *
 * <p>This exists because {@link PartyStatePayload} cannot answer the question. That one describes
 * one party to the people in it, so a player who is in no party, or in a different one, is told
 * nothing at all - and the mark above a head is meant to be readable by anyone walking past, not
 * only by the people already listening along.
 *
 * <p>Only the ids travel. A name tag is matched by UUID, and sending the names as well would be a
 * second copy of something the client can already see for every player it can render.
 *
 * <p>Pushed only when the set changes, not on a timer: parties are started and left rarely, so this
 * is a handful of packets an evening rather than a broadcast per tick.
 */
public record PartyMarkersPayload(List<UUID> listeners) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PartyMarkersPayload> TYPE =
            Platform.payloadType("party_markers");

    public static final PartyMarkersPayload NONE = new PartyMarkersPayload(List.of());

    public static final StreamCodec<FriendlyByteBuf, PartyMarkersPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public PartyMarkersPayload decode(FriendlyByteBuf buffer) {
                    int size = buffer.readVarInt();
                    List<UUID> listeners = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        listeners.add(buffer.readUUID());
                    }
                    // Whatever a newer sender wrote past this point is dropped rather than left
                    // behind: Minecraft ends the connection when a packet still has bytes in it
                    // after decoding. It has to come after every read, not before, or it eats the
                    // packet the reads were about to take.
                    buffer.skipBytes(buffer.readableBytes());
                    return new PartyMarkersPayload(List.copyOf(listeners));
                }

                @Override
                public void encode(FriendlyByteBuf buffer, PartyMarkersPayload value) {
                    buffer.writeVarInt(value.listeners().size());
                    for (UUID listener : value.listeners()) {
                        buffer.writeUUID(listener);
                    }
                }
            };

    @Override
    public Type<PartyMarkersPayload> type() {
        return TYPE;
    }
}
