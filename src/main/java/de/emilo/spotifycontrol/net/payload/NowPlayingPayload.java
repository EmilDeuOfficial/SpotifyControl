package de.emilo.spotifycontrol.net.payload;

import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record NowPlayingPayload(String trackUri, String title, String artist, String artworkUrl,
                                long durationMs, long positionMs, boolean playing) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NowPlayingPayload> TYPE =
            Platform.payloadType("now_playing");

    public static final NowPlayingPayload EMPTY =
            new NowPlayingPayload("", "", "", "", 0L, 0L, false);

    public static final StreamCodec<FriendlyByteBuf, NowPlayingPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public NowPlayingPayload decode(FriendlyByteBuf buffer) {
                    String uri = buffer.readUtf(128);
                    String title = buffer.readUtf(256);
                    String artist = buffer.readUtf(256);
                    String artworkUrl = buffer.readUtf(512);
                    long positionMs = buffer.readVarLong();
                    long durationMs = buffer.readVarLong();
                    boolean playing = buffer.readBoolean();
                    // Whatever a newer sender wrote past this point is dropped rather than left
                    // behind: Minecraft ends the connection when a packet still has bytes in it
                    // after decoding. It has to come after every read, not before, or it eats the
                    // packet the reads were about to take.
                    buffer.skipBytes(buffer.readableBytes());
                    return new NowPlayingPayload(uri, title, artist, artworkUrl, positionMs, durationMs,
                            playing);
                }

                @Override
                public void encode(FriendlyByteBuf buffer, NowPlayingPayload value) {
                    buffer.writeUtf(value.trackUri(), 128);
                    buffer.writeUtf(value.title(), 256);
                    buffer.writeUtf(value.artist(), 256);
                    buffer.writeUtf(value.artworkUrl(), 512);
                    buffer.writeVarLong(value.durationMs());
                    buffer.writeVarLong(value.positionMs());
                    buffer.writeBoolean(value.playing());
                }
            };

    @Override
    public Type<NowPlayingPayload> type() {
        return TYPE;
    }
}
