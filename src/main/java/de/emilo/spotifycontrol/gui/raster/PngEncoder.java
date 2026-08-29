package de.emilo.spotifycontrol.gui.raster;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public final class PngEncoder {

    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private PngEncoder() {
    }

    public static byte[] encode(int width, int height, int[] argb) {
        byte[] raw = new byte[height * (1 + width * 4)];
        int cursor = 0;
        for (int y = 0; y < height; y++) {
            raw[cursor++] = 0;
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                raw[cursor++] = (byte) (pixel >> 16);
                raw[cursor++] = (byte) (pixel >> 8);
                raw[cursor++] = (byte) pixel;
                raw[cursor++] = (byte) (pixel >>> 24);
            }
        }

        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(raw.length / 2 + 64);
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            compressed.write(buffer, 0, count);
        }
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.size() + 64);
        out.write(SIGNATURE, 0, SIGNATURE.length);
        byte[] header = new byte[13];
        writeInt(header, 0, width);
        writeInt(header, 4, height);
        header[8] = 8;
        header[9] = 6;
        header[10] = 0;
        header[11] = 0;
        header[12] = 0;
        chunk(out, "IHDR", header);
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        writeInt(length, 0, data.length);
        out.write(length, 0, 4);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] checksum = new byte[4];
        writeInt(checksum, 0, (int) crc.getValue());
        out.write(checksum, 0, 4);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
