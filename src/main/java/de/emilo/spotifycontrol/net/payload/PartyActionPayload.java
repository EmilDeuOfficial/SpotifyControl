package de.emilo.spotifycontrol.net.payload;

import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PartyActionPayload(String action, String target, String mode) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PartyActionPayload> TYPE =
            Platform.payloadType("party_action");

    public static final StreamCodec<FriendlyByteBuf, PartyActionPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public PartyActionPayload decode(FriendlyByteBuf buffer) {
                    String action = buffer.readUtf(32);
                    String target = buffer.readUtf(64);
                    String mode = buffer.readUtf(32);
                    // Whatever a newer sender wrote past this point is dropped rather than left
                    // behind: Minecraft ends the connection when a packet still has bytes in it
                    // after decoding. It has to come after every read, not before, or it eats the
                    // packet the reads were about to take.
                    buffer.skipBytes(buffer.readableBytes());
                    return new PartyActionPayload(action, target, mode);
                }

                @Override
                public void encode(FriendlyByteBuf buffer, PartyActionPayload value) {
                    buffer.writeUtf(value.action(), 32);
                    buffer.writeUtf(value.target(), 64);
                    buffer.writeUtf(value.mode(), 32);
                }
            };

    public static PartyActionPayload of(String action) {
        return new PartyActionPayload(action, "", "");
    }

    @Override
    public Type<PartyActionPayload> type() {
        return TYPE;
    }
}
