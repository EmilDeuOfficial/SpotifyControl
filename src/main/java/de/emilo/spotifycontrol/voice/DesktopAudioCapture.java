package de.emilo.spotifycontrol.voice;

import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.net.payload.AudioFramePayload;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import javax.sound.sampled.TargetDataLine;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class DesktopAudioCapture {

    private volatile Thread worker;
    private volatile boolean running;
    private volatile String lastError = "";

    public boolean isRunning() {
        return running;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * Where a frame of samples comes from. The two implementations differ in more than the call
     * that fetches bytes: the line hands over interleaved stereo that has to be folded down, the
     * Windows loopback hands over the mono frame the encoder wants, and the loopback may only be
     * touched by the thread that opened it. Everything after the frame - gain, Opus, sequence
     * numbers, the packet - is the same for both and lives in {@link #pump}.
     */
    private interface Source {

        /**
         * Fills the buffer with one frame of mono samples, blocking until it is full. False means
         * this source has ended and the broadcast is over, which is the sound system refusing to
         * hand out any more data, the endpoint disappearing, or {@link #stop()} having been called
         * halfway through a frame.
         */
        boolean read(short[] mono);

        void close();
    }

    public synchronized boolean start(String deviceName, Consumer<String> onFailure) {
        if (running) {
            return true;
        }
        VoicechatClientApi api = SpotifyVoicechatClientPlugin.clientApi();
        if (api == null) {
            lastError = "Simple Voice Chat client API unavailable";
            onFailure.accept("audio.voicechat_missing");
            return false;
        }
        Supplier<Source> opener;
        String endpointId = AudioDevices.endpointId(deviceName);
        if (AudioDevices.SYSTEM_OUTPUT.equals(deviceName)) {
            // Deferred on purpose. WASAPI hands out COM objects that belong to the thread that
            // created them, so the loopback has to be opened by the worker that will read it, and
            // a failure to open can only be reported after this method has already returned.
            opener = LoopbackSource::openDefault;
        } else if (endpointId != null) {
            // The same deferral, on the endpoint the user picked instead of on whatever Windows
            // currently calls the default. The label is read here, off the stored name, so the
            // worker never has to enumerate anything just to write a log line.
            String label = AudioDevices.displayName(deviceName);
            opener = () -> LoopbackSource.openEndpoint(endpointId, label);
        } else {
            TargetDataLine line = AudioDevices.open(deviceName);
            if (line == null) {
                lastError = "device could not be opened";
                onFailure.accept("audio.device_failed");
                return false;
            }
            // A line carries no thread affinity, so it stays where it always was: opened here, so
            // a device that is gone is reported before the caller announces a broadcast.
            opener = () -> new LineSource(line);
        }
        running = true;
        worker = new Thread(() -> run(api, opener, onFailure), "SpotifyControl-AudioCapture");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * The body of the capture thread: bring the source up here, then pump it. The last step of
     * opening belongs to this thread and not to the caller of {@link #start}, because that is the
     * only way the Windows loopback can be used at all, and doing it for both paths means every
     * source is read and closed by the thread that finished opening it.
     */
    private void run(VoicechatClientApi api, Supplier<Source> opener, Consumer<String> onFailure) {
        Source source;
        try {
            source = opener.get();
        } catch (RuntimeException | LinkageError e) {
            // The loopback reaches for JNA, which arrives with the game and not with this mod. On a
            // runtime that does not carry it the broadcast has to fail like a missing device, not
            // kill the capture thread with an error nobody sees.
            SpotifyControl.LOGGER.error("Could not open the capture source", e);
            source = null;
        }
        if (source == null) {
            running = false;
            lastError = "device could not be opened";
            onFailure.accept("audio.device_failed");
            return;
        }
        pump(api, source);
    }

    private void pump(VoicechatClientApi api, Source source) {
        short[] mono = new short[AudioDevices.FRAME_SAMPLES];
        OpusEncoder encoder = null;
        long sequence = 0L;
        try {
            encoder = api.createEncoder();
            while (running) {
                if (!source.read(mono)) {
                    break;
                }
                applyGain(mono);
                byte[] opus = encoder.encode(mono);
                if (opus != null && opus.length > 0 && opus.length <= AudioFramePayload.MAX_FRAME_BYTES) {
                    ClientPlayNetworking.send(new AudioFramePayload(sequence++, opus));
                }
            }
        } catch (Exception e) {
            if (running) {
                SpotifyControl.LOGGER.error("Desktop audio capture stopped unexpectedly", e);
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        } finally {
            running = false;
            if (encoder != null) {
                encoder.close();
            }
            source.close();
        }
    }

    /**
     * Scales a finished frame by the configured capture volume. Read fresh every frame so a change
     * in the settings is heard at once, and applied to both sources, because the volume is a
     * property of the broadcast and not of the device it came from.
     */
    private void applyGain(short[] samples) {
        float gain = Math.max(0, ConfigManager.client().captureVolumePercent) / 100.0f;
        if (gain == 1.0f) {
            return;
        }
        for (int i = 0; i < samples.length; i++) {
            int scaled = Math.round(samples[i] * gain);
            samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
    }

    /** A capture line from the sound system, which is a microphone unless the user routed it. */
    private final class LineSource implements Source {

        private final TargetDataLine line;
        private final byte[] buffer = new byte[AudioDevices.FRAME_BYTES];

        private LineSource(TargetDataLine line) {
            this.line = line;
        }

        @Override
        public boolean read(short[] mono) {
            int read = 0;
            while (read < AudioDevices.FRAME_BYTES) {
                if (!running) {
                    return false;
                }
                int count = line.read(buffer, read, AudioDevices.FRAME_BYTES - read);
                if (count <= 0) {
                    // A started line blocks until it has the bytes, so nothing at all means the
                    // line is finished. Retrying would spin a core for no audio.
                    return false;
                }
                read += count;
            }
            downmix(buffer, mono);
            return true;
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }

        /** Little endian stereo pairs to single samples, the format the encoder is fed. */
        private void downmix(byte[] source, short[] target) {
            for (int i = 0; i < AudioDevices.FRAME_SAMPLES; i++) {
                int offset = i * 4;
                int left = (short) ((source[offset + 1] << 8) | (source[offset] & 0xFF));
                int right = (short) ((source[offset + 3] << 8) | (source[offset + 2] & 0xFF));
                // The mean of two 16 bit values is a 16 bit value, so this cannot overflow and
                // needs no clamp of its own.
                target[i] = (short) Math.round((left + right) / 2.0f);
            }
        }
    }

    /**
     * What Windows is sending to an output device, read through WASAPI loopback. It arrives as the
     * mono frame the encoder wants, so there is nothing to fold down here.
     */
    private static final class LoopbackSource implements Source {

        private final WindowsLoopback loopback;

        private LoopbackSource(WindowsLoopback loopback) {
            this.loopback = loopback;
        }

        /**
         * The mix on its way to whatever Windows currently calls the default output. Null when the
         * loopback is unavailable or refused, which the caller reports as a device that could not
         * be opened. Has to run on the thread that will go on to read it.
         */
        private static LoopbackSource openDefault() {
            WindowsLoopback loopback = WindowsLoopback.open();
            return loopback == null ? null : new LoopbackSource(loopback);
        }

        /**
         * The mix on its way to one named endpoint, falling back to the default output when that
         * endpoint is gone. Same threading rule as {@link #openDefault()}.
         *
         * <p>Falling back rather than failing, because the endpoint is named by a config that was
         * written when the headset was still plugged in, and what the user asked for is that the
         * party hears the music. Ending the broadcast would punish them for a cable. The stream
         * that results is the wrong device and only this log line says so, so it says which device
         * went missing by the name the user picked it under.
         */
        private static LoopbackSource openEndpoint(String endpointId, String label) {
            WindowsLoopback loopback = WindowsLoopback.open(endpointId);
            if (loopback == null) {
                SpotifyControl.LOGGER.warn(
                        "Audio source '{}' is gone, broadcasting the default output instead", label);
                loopback = WindowsLoopback.open();
            }
            return loopback == null ? null : new LoopbackSource(loopback);
        }

        @Override
        public boolean read(short[] mono) {
            return loopback.read(mono);
        }

        @Override
        public void close() {
            loopback.close();
        }
    }
}
