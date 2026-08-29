package de.emilo.spotifycontrol.net.payload;

import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The party as every member sees it. "streaming" is the host's live audio state, not the mode:
 * a BROADCAST party whose host has paused or never started capturing is not streaming, and the
 * server pushes the state again whenever that changes.
 *
 * <p>The membership is carried twice on purpose. "members" are display names, which is what the
 * party panel lists, but a name is not an identity: anything that has to recognise a player in the
 * world - the name tag marker above their head - matches on the UUID instead. Both lists start with
 * the host and stay in the same order, so index i is the same person in either.
 */
public record PartyStatePayload(boolean inParty, boolean host, boolean streaming, UUID hostId, String hostName,
                                String mode, List<String> members, List<UUID> memberIds)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PartyStatePayload> TYPE =
            Platform.payloadType("party_state");

    public static final PartyStatePayload NONE =
            new PartyStatePayload(false, false, false, new UUID(0L, 0L), "", "SYNC", List.of(), List.of());

    public static final StreamCodec<FriendlyByteBuf, PartyStatePayload> CODEC =
            new StreamCodec<>() {
                @Override
                public PartyStatePayload decode(FriendlyByteBuf buffer) {
                    boolean inParty = buffer.readBoolean();
                    boolean host = buffer.readBoolean();
                    boolean streaming = buffer.readBoolean();
                    UUID hostId = buffer.readUUID();
                    String hostName = buffer.readUtf(64);
                    String mode = buffer.readUtf(32);
                    int size = buffer.readVarInt();
                    List<String> members = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        members.add(buffer.readUtf(64));
                    }
                    // Added after the first release, so a server that predates it stops here and the
                    // buffer is simply empty. Reading it unconditionally would run off the end and
                    // take the connection down, which is the failure this guard exists to avoid.
                    List<UUID> memberIds = List.of();
                    if (buffer.isReadable()) {
                        int idSize = buffer.readVarInt();
                        List<UUID> ids = new ArrayList<>(idSize);
                        for (int i = 0; i < idSize; i++) {
                            ids.add(buffer.readUUID());
                        }
                        memberIds = ids;
                    }
                    // Whatever a newer sender wrote past this point is dropped rather than left
                    // behind: Minecraft ends the connection when a packet still has bytes in it
                    // after decoding. It has to come after every read, not before, or it eats the
                    // packet the reads were about to take.
                    buffer.skipBytes(buffer.readableBytes());

                    return new PartyStatePayload(inParty, host, streaming, hostId, hostName, mode, members, memberIds);
                }

                @Override
                public void encode(FriendlyByteBuf buffer, PartyStatePayload value) {
                    buffer.writeBoolean(value.inParty());
                    buffer.writeBoolean(value.host());
                    buffer.writeBoolean(value.streaming());
                    buffer.writeUUID(value.hostId());
                    buffer.writeUtf(value.hostName(), 64);
                    buffer.writeUtf(value.mode(), 32);
                    buffer.writeVarInt(value.members().size());
                    for (String member : value.members()) {
                        buffer.writeUtf(member, 64);
                    }
                    buffer.writeVarInt(value.memberIds().size());
                    for (UUID memberId : value.memberIds()) {
                        buffer.writeUUID(memberId);
                    }
                }
            };

    @Override
    public Type<PartyStatePayload> type() {
        return TYPE;
    }
}
