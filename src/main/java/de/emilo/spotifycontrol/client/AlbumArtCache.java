package de.emilo.spotifycontrol.client;

import com.mojang.blaze3d.platform.NativeImage;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.gui.raster.PngEncoder;
import de.emilo.spotifycontrol.gui.raster.RasterCache;
import de.emilo.spotifycontrol.platform.Platform;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AlbumArtCache {

    private static final int SOURCE_CACHE_ENTRIES = 160;
    private static final long RETRY_DELAY_MILLIS = 30_000L;
    private static final int LOGGED_FAILURES = 5;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ExecutorService workers = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "SpotifyControl-Artwork");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, byte[]> sources = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > SOURCE_CACHE_ENTRIES;
                }
            });
    private final Map<String, Object> textures = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Deque<String> insertionOrder = new ArrayDeque<>();
    private final Map<String, Long> lastUsed = new ConcurrentHashMap<>();
    private final Deque<Long> freeSlots = new ArrayDeque<>();
    private final Map<String, Long> slots = new ConcurrentHashMap<>();
    private final AtomicInteger loaded = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private volatile String lastError = "";
    private long sequence;

    public Object get(String url, int displaySize) {
        if (url == null || url.isEmpty() || displaySize <= 0 || !ConfigManager.client().showAlbumArt) {
            return null;
        }
        int pixels = Math.min(256, Math.max(8, RasterCache.rasterPixels(displaySize)));
        String key = url + "@" + pixels;
        Object existing = textures.get(key);
        if (existing != null) {
            lastUsed.put(key, System.currentTimeMillis());
            return existing;
        }
        Long retry = retryAfter.get(url);
        if (retry != null && System.currentTimeMillis() < retry) {
            return null;
        }
        if (!pending.add(key)) {
            return null;
        }
        workers.execute(() -> produce(url, pixels, key));
        return null;
    }

    public int loadedCount() {
        return loaded.get();
    }

    public int failureCount() {
        return failures.get();
    }

    public String lastError() {
        return lastError;
    }

    private void produce(String url, int pixels, String key) {
        try {
            byte[] source = sources.get(url);
            if (source == null) {
                source = download(url);
                if (isWebp(source)) {
                    String alternative = jpegAlternative(url);
                    if (alternative != null) {
                        source = download(alternative);
                    }
                }
                sources.put(url, source);
            }
            byte[] scaled = scaleToPng(source, pixels);
            if (scaled == null) {
                throw new IllegalStateException("unsupported image format " + describe(source));
            }
            byte[] payload = scaled;
            Minecraft.getInstance().execute(() -> upload(key, payload));
        } catch (Exception e) {
            fail(key, e);
            pending.remove(key);
        }
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static String jpegAlternative(String url) {
        int slash = url.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        String id = url.substring(slash + 1);
        int query = id.indexOf('?');
        if (query >= 0) {
            id = id.substring(0, query);
        }
        if (!id.matches("[0-9a-f]{40}")) {
            return null;
        }
        return "https://mosaic.scdn.co/300/" + id;
    }

    private static String describe(byte[] bytes) {
        if (isWebp(bytes)) {
            return "webp";
        }
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') {
            return "png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "jpeg";
        }
        return "(" + bytes.length + " bytes)";
    }

    private byte[] download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SpotifyControl/1.0 (Minecraft Fabric mod)")
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            int status = response.statusCode();
            // Shown in the diagnostics, so it says what happened rather than which code came back.
            throw new IllegalStateException(status >= 500 ? "Spotify's image server is overloaded"
                    : status == 404 ? "the cover no longer exists" : "the image server refused the download");
        }
        return response.body();
    }

    private byte[] scaleToPng(byte[] source, int pixels) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
            int[] scaled = resample(argb, width, height, pixels);
            return PngEncoder.encode(pixels, pixels, scaled);
        } catch (Exception e) {
            SpotifyControl.LOGGER.debug("ImageIO could not decode album art", e);
            return null;
        }
    }

    private static int[] resample(int[] source, int width, int height, int target) {
        int[] result = new int[target * target];
        for (int ty = 0; ty < target; ty++) {
            int y0 = ty * height / target;
            int y1 = Math.max(y0 + 1, (ty + 1) * height / target);
            for (int tx = 0; tx < target; tx++) {
                int x0 = tx * width / target;
                int x1 = Math.max(x0 + 1, (tx + 1) * width / target);
                long r = 0;
                long g = 0;
                long b = 0;
                int count = 0;
                for (int y = y0; y < y1 && y < height; y++) {
                    for (int x = x0; x < x1 && x < width; x++) {
                        int pixel = source[y * width + x];
                        r += (pixel >> 16) & 0xFF;
                        g += (pixel >> 8) & 0xFF;
                        b += pixel & 0xFF;
                        count++;
                    }
                }
                if (count == 0) {
                    count = 1;
                }
                result[ty * target + tx] = 0xFF000000
                        | (int) (r / count) << 16
                        | (int) (g / count) << 8
                        | (int) (b / count);
            }
        }
        return result;
    }

    private void upload(String key, byte[] bytes) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            long slot = freeSlots.isEmpty() ? sequence++ : freeSlots.pollFirst();
            Object handle = Platform.registerTexture("art/" + slot, image);
            textures.put(key, handle);
            slots.put(key, slot);
            lastUsed.put(key, System.currentTimeMillis());
            insertionOrder.addLast(key);
            loaded.incrementAndGet();
            evictIfNeeded();
        } catch (Throwable e) {
            fail(key, e);
        } finally {
            pending.remove(key);
        }
    }

    private void fail(String key, Throwable e) {
        // Keyed by the url, not by the size: the download fails the same way at any resolution,
        // and a size change must not slip past the delay.
        int at = key.lastIndexOf('@');
        retryAfter.put(at > 0 ? key.substring(0, at) : key, System.currentTimeMillis() + RETRY_DELAY_MILLIS);
        int count = failures.incrementAndGet();
        lastError = e instanceof java.io.IOException ? "no connection to the image server"
                : e.getMessage() == null ? "the image could not be read" : e.getMessage();
        if (count <= LOGGED_FAILURES) {
            SpotifyControl.LOGGER.warn("Album art failed for {}: {}", key, lastError, e);
        }
    }

    private void evictIfNeeded() {
        int limit = Math.max(32, ConfigManager.client().albumArtCacheSize);
        long now = System.currentTimeMillis();
        int attempts = insertionOrder.size();
        while (insertionOrder.size() > limit && attempts-- > 0) {
            String oldest = insertionOrder.pollFirst();
            Long used = lastUsed.get(oldest);
            if (used != null && now - used < 2_000L) {
                insertionOrder.addLast(oldest);
                continue;
            }
            release(oldest);
        }
    }

    private void release(String key) {
        Object location = textures.remove(key);
        lastUsed.remove(key);
        Long slot = slots.remove(key);
        if (location != null) {
            Platform.releaseTexture(location);
        }
        if (slot != null) {
            freeSlots.addLast(slot);
        }
    }

    public void clear() {
        for (String key : new java.util.ArrayList<>(textures.keySet())) {
            release(key);
        }
        insertionOrder.clear();
        retryAfter.clear();
        sources.clear();
    }

    public void shutdown() {
        workers.shutdownNow();
    }
}
