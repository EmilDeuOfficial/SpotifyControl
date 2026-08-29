package de.emilo.spotifycontrol.net.payload;

import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record AudioFramePayload(long sequence, byte[] opus) implements CustomPacketPayload {

    public static final int MAX_FRAME_BYTES = 1024;

    public static final CustomPacketPayload.Type<AudioFramePayload> TYPE =
            Platform.payloadType("audio_frame");

    public static final StreamCodec<FriendlyByteBuf, AudioFramePayload> CODEC =
            new StreamCodec<>() {
                @Override
                public AudioFramePayload decode(FriendlyByteBuf buffer) {
                    long sequence = buffer.readVarLong();
                    byte[] opus = buffer.readByteArray(MAX_FRAME_BYTES);
                    // Whatever a newer sender wrote past this point is dropped rather than left
                    // behind: Minecraft ends the connection when a packet still has bytes in it
                    // after decoding. It has to come after every read, not before, or it eats the
                    // packet the reads were about to take.
                    buffer.skipBytes(buffer.readableBytes());
                    return new AudioFramePayload(sequence, opus);
                }

                @Override
                public void encode(FriendlyByteBuf buffer, AudioFramePayload value) {
                    buffer.writeVarLong(value.sequence());
                    buffer.writeByteArray(value.opus());
                }
            };

    @Override
    public Type<AudioFramePayload> type() {
        return TYPE;
    }
}
