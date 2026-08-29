package de.emilo.spotifycontrol.net.payload;

import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ServerSettingsPayload(boolean modEnabled, boolean partiesAllowed, boolean broadcastAllowed,
                                    boolean voicechatPresent, int maxKilobitsPerSecond,
                                    int broadcastRangeBlocks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerSettingsPayload> TYPE =
            Platform.payloadType("server_settings");

    public static final ServerSettingsPayload SINGLEPLAYER_DEFAULT =
            new ServerSettingsPayload(true, true, true, false, 96, 0);

    public static final StreamCodec<FriendlyByteBuf, ServerSettingsPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public ServerSettingsPayload decode(FriendlyByteBuf buffer) {
                    boolean modEnabled = buffer.readBoolean();
                    boolean partiesAllowed = buffer.readBoolean();
                    boolean broadcastAllowed = buffer.readBoolean();
                    boolean requireSameDimension = buffer.readBoolean();
                    int broadcastRangeBlocks = buffer.readVarInt();
                    int syncToleranceMs = buffer.readVarInt();
                    // Whatever a newer sender wrote past this point is dropped rather than left
                    // behind: Minecraft ends the connection when a packet still has bytes in it
                    // after decoding. It has to come after every read, not before, or it eats the
                    // packet the reads were about to take.
                    buffer.skipBytes(buffer.readableBytes());
                    return new ServerSettingsPayload(modEnabled, partiesAllowed, broadcastAllowed,
                            requireSameDimension, broadcastRangeBlocks, syncToleranceMs);
                }

                @Override
                public void encode(FriendlyByteBuf buffer, ServerSettingsPayload value) {
                    buffer.writeBoolean(value.modEnabled());
                    buffer.writeBoolean(value.partiesAllowed());
                    buffer.writeBoolean(value.broadcastAllowed());
                    buffer.writeBoolean(value.voicechatPresent());
                    buffer.writeVarInt(value.maxKilobitsPerSecond());
                    buffer.writeVarInt(value.broadcastRangeBlocks());
                }
            };

    @Override
    public Type<ServerSettingsPayload> type() {
        return TYPE;
    }
}
