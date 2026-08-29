package de.emilo.spotifycontrol.voice;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads what a Windows output device is playing, without anything being installed, and lists the
 * output devices there are to choose from.
 *
 * <p>javax.sound.sampled can only ever open an input, so the old route needed Stereomix or a
 * virtual cable before Spotify could be broadcast. WASAPI has had a loopback mode since Vista:
 * initialise a capture client against the RENDER endpoint and it delivers the mix that is on its
 * way out. The JDK has no binding for it, but JNA rides along with Minecraft and a COM interface is
 * only a table of function pointers, so every call here is an indexed jump through that table and
 * not one line of native code of our own.
 *
 * <p>What this captures is the WHOLE endpoint mix, so Minecraft's own sound is broadcast together
 * with the music. That is the same trade Stereomix makes. Capturing a single process needs
 * ActivateAudioInterfaceAsync and a COM completion handler implemented on this side, which is a far
 * bigger and riskier piece and is deliberately not attempted here; anyone who wants Spotify alone
 * stays on the virtual cable route with an explicitly chosen device.
 *
 * <p><b>Threading.</b> COM is initialised on the thread that calls {@link #open()}, and an
 * apartment belongs to its thread, so {@link #open()}, {@link #read(short[])} and {@link #close()}
 * all belong to that one thread. None of them is thread safe. The single concession is that
 * {@link #close()} may be called from elsewhere to abort a capture: it flips a volatile flag first
 * so a waiting {@link #read(short[])} gives up promptly, and it skips CoUninitialize when it is not
 * on the owning thread, because that call counts per thread and would otherwise unbalance a thread
 * this class never initialised.
 *
 * <p>{@link #outputs()} stands apart from all of that: it opens COM, reads the endpoint list and
 * closes COM again before it returns, so it owns nothing afterwards and any thread may call it.
 */
public final class WindowsLoopback {

    /**
     * The COM identifiers, in a holder so that loading WindowsLoopback never by itself resolves a
     * JNA class. {@link #available()} has to survive a machine where JNA is missing, and a class
     * initializer mentioning Guid would raise NoClassDefFoundError before the guard could run.
     */
    private static final class Com {
        static final Guid.GUID CLSID_MM_DEVICE_ENUMERATOR =
                Guid.GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
        static final Guid.GUID IID_IMM_DEVICE_ENUMERATOR =
                Guid.GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
        static final Guid.GUID IID_IAUDIO_CLIENT =
                Guid.GUID.fromString("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}");
        static final Guid.GUID IID_IAUDIO_CAPTURE_CLIENT =
                Guid.GUID.fromString("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}");

        private Com() {
        }
    }

    private static final int CLSCTX_ALL = 23;
    private static final int E_RENDER = 0;
    private static final int E_CONSOLE = 0;
    private static final int SHARE_MODE_SHARED = 0;
    private static final int STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int BUFFERFLAGS_SILENT = 0x2;

    /**
     * Endpoints that exist and are plugged in. Devices that are merely present but disabled or
     * unplugged cannot be captured, so offering them would only produce a setting that fails later.
     */
    private static final int DEVICE_STATE_ACTIVE = 0x1;
    /** STGM_READ. Nothing here ever writes a device property, and read access asks for no rights. */
    private static final int STGM_READ = 0;
    /** The PROPVARIANT type for a wide string that belongs to the COM allocator. */
    private static final int VT_LPWSTR = 31;
    /**
     * A PROPVARIANT is 16 bytes on a 32 bit process and 24 on a 64 bit one. Always handing GetValue
     * the larger block wastes eight bytes on the smaller platform and rules out a write past the end
     * on the bigger one, which is the cheaper mistake to make.
     */
    private static final int PROPVARIANT_BYTES = 24;
    /** The pid half of PKEY_Device_FriendlyName. The GUID half is written out in {@link #friendlyNameKey()}. */
    private static final int PID_DEVICE_FRIENDLY_NAME = 14;

    /** Reference times are in 100 nanosecond units, so this asks for a one second buffer. */
    private static final long ONE_SECOND = 10_000_000L;

    /** The mixer has produced nothing yet. A success code, so it must not be read as an error. */
    private static final int AUDCLNT_S_BUFFER_EMPTY = 0x08890001;
    /** The default output changed under us, or was unplugged. The stream is over, not broken. */
    private static final int AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004;
    /** The thread already lives in a single threaded apartment. COM works, we just did not open it. */
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    private static final int WAVE_FORMAT_PCM = 1;
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;
    private static final int SUBFORMAT_OFFSET = 24;

    /**
     * Everything after the leading DWORD of a KSDATAFORMAT_SUBTYPE GUID, in memory order. The
     * subtypes for PCM and IEEE float differ only in that first DWORD, which carries the plain
     * WAVE_FORMAT tag, so matching this tail proves the GUID belongs to that family and the DWORD
     * then says which member it is.
     */
    private static final byte[] KSDATAFORMAT_TAIL = {
            0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xAA,
            0x00, 0x38, (byte) 0x9B, 0x71
    };

    private static final int TARGET_RATE = 48000;

    /** WASAPI hands over packets of roughly ten milliseconds, so a short wait costs nothing. */
    private static final long PACKET_WAIT_MILLIS = 2L;

    /** Result of the one probe worth doing, since neither the OS nor JNA changes while we run. */
    private static volatile Boolean supported;

    private Thread owner;
    private boolean comInitialized;
    private Pointer enumerator;
    private Pointer device;
    private Pointer audioClient;
    private Pointer mixFormat;
    private Pointer captureClient;
    private boolean started;
    private boolean tornDown;

    private int channels;
    private int sampleRate;
    private int blockAlign;
    private int bytesPerSample;
    private boolean floatSamples;

    /**
     * Mono samples at the endpoint's own rate, waiting to be resampled. {@link #position} is the
     * read cursor into it and survives across calls, which is what keeps the resampler from
     * restarting its phase on every packet and wobbling the pitch.
     */
    private float[] source = new float[8192];
    private int sourceCount;
    private double position;
    private double step = 1.0;

    private volatile boolean closed;
    private boolean ended;

    private WindowsLoopback() {
    }

    /**
     * Whether this machine could plausibly do WASAPI loopback: Windows, with a JNA that actually
     * loads. Safe on every operating system, never throws, and the answer is cached.
     */
    public static boolean available() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            String os = System.getProperty("os.name");
            if (os != null && os.toLowerCase(Locale.ROOT).startsWith("windows")) {
                // Touching these resolves JNA and loads jnidispatch and then ole32. A JNA that is
                // absent, or that cannot unpack its native part, surfaces as an Error rather than
                // an Exception, so Throwable is the only net that catches it.
                result = Native.POINTER_SIZE > 0 && Ole32.INSTANCE != null;
            }
        } catch (Throwable t) {
            SpotifyControl.LOGGER.warn("WASAPI loopback unavailable, JNA did not load: {}", t.toString());
            result = false;
        }
        supported = result;
        return result;
    }

    /**
     * One render endpoint. The {@code id} is what WASAPI knows the device by and is the only half
     * that belongs in a config file; the {@code name} exists to be shown to the user and may change
     * when a driver is updated or the user renames the device in the Windows sound settings.
     */
    public record Endpoint(String id, String name) {
    }

    /**
     * Every active render endpoint on this machine, sorted by name: speakers, headsets and the input
     * side of any virtual cable, which is to say everything something can be played into, and no
     * microphone at all because a render endpoint is an output by definition.
     *
     * <p>That is the reason for going through WASAPI rather than javax.sound.sampled, which can only
     * list inputs and so forced the mod to guess from the device name which entries were usable. The
     * JDK list is also filled once and refreshed only when the NUMBER of devices changes, so a
     * virtual cable installed while the game is running never appears; WASAPI asks the system on
     * every call.
     *
     * <p>Empty on Linux and macOS, empty when COM refuses, never null and never throws. A single
     * endpoint that cannot be read is skipped rather than allowed to cost the caller the whole list.
     */
    public static List<Endpoint> outputs() {
        if (!available()) {
            return List.of();
        }
        List<Endpoint> found = new ArrayList<>();
        boolean comOwned = false;
        Pointer enumerator = null;
        Pointer collection = null;
        try {
            HRESULT init = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
            int hr = init.intValue();
            if (hr >= 0) {
                // S_FALSE too: it still owes a matching CoUninitialize.
                comOwned = true;
            } else if (hr != RPC_E_CHANGED_MODE) {
                ok("CoInitializeEx", hr);
                return List.of();
            }

            PointerByReference enumRef = new PointerByReference();
            if (!ok("CoCreateInstance(MMDeviceEnumerator)", Ole32.INSTANCE.CoCreateInstance(
                    Com.CLSID_MM_DEVICE_ENUMERATOR, null, CLSCTX_ALL,
                    Com.IID_IMM_DEVICE_ENUMERATOR, enumRef).intValue())) {
                return List.of();
            }
            enumerator = enumRef.getValue();

            PointerByReference collectionRef = new PointerByReference();
            if (!ok("EnumAudioEndpoints", call(enumerator, 3,
                    E_RENDER, DEVICE_STATE_ACTIVE, collectionRef))) {
                return List.of();
            }
            collection = collectionRef.getValue();

            IntByReference count = new IntByReference();
            if (!ok("IMMDeviceCollection::GetCount", call(collection, 3, count))) {
                return List.of();
            }
            for (int index = 0; index < count.getValue(); index++) {
                Endpoint endpoint = readEndpoint(collection, index);
                if (endpoint != null) {
                    found.add(endpoint);
                }
            }
        } catch (Throwable t) {
            // Whatever was collected before this is still a usable list, and a settings screen that
            // shows four of five devices is better than one that shows a crash.
            SpotifyControl.LOGGER.warn("WASAPI could not list the output devices", t);
        } finally {
            release(collection);
            release(enumerator);
            if (comOwned) {
                // Unlike the capture instance this whole method runs on one thread, so the count is
                // balanced here and now.
                Ole32.INSTANCE.CoUninitialize();
            }
        }
        // The id breaks ties, because two devices sharing a name would otherwise keep whatever order
        // the enumerator happened to return and could swap places between sessions.
        found.sort(Comparator.comparing(Endpoint::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Endpoint::id));
        return found;
    }

    /**
     * One endpoint out of the collection, or null when that single device cannot be read. Every
     * failure is a debug line and a skip: a machine with one odd driver on it must still get a list.
     */
    private static Endpoint readEndpoint(Pointer collection, int index) {
        Pointer device = null;
        Pointer store = null;
        Pointer id = null;
        try {
            PointerByReference deviceRef = new PointerByReference();
            int hr = call(collection, 4, index, deviceRef); // IMMDeviceCollection::Item
            if (hr < 0) {
                SpotifyControl.LOGGER.debug("WASAPI: output {} could not be fetched, hr=0x{}",
                        index, Integer.toHexString(hr));
                return null;
            }
            device = deviceRef.getValue();

            PointerByReference idRef = new PointerByReference();
            hr = call(device, 5, idRef); // IMMDevice::GetId
            if (hr < 0) {
                SpotifyControl.LOGGER.debug("WASAPI: output {} has no id, hr=0x{}",
                        index, Integer.toHexString(hr));
                return null;
            }
            id = idRef.getValue();
            String endpointId = id == null ? null : id.getWideString(0);
            if (endpointId == null || endpointId.isEmpty()) {
                return null;
            }

            String name = null;
            PointerByReference storeRef = new PointerByReference();
            hr = call(device, 4, STGM_READ, storeRef); // IMMDevice::OpenPropertyStore
            if (hr >= 0) {
                store = storeRef.getValue();
                name = friendlyName(store);
            } else {
                SpotifyControl.LOGGER.debug("WASAPI: output {} has no property store, hr=0x{}",
                        index, Integer.toHexString(hr));
            }
            // A device with no readable name is still perfectly capturable, so it is listed under
            // its id rather than dropped. The id is ugly but it is unique and it can be selected.
            return new Endpoint(endpointId, name == null || name.isBlank() ? endpointId : name);
        } catch (Throwable t) {
            SpotifyControl.LOGGER.debug("WASAPI: output {} could not be read: {}", index, t.toString());
            return null;
        } finally {
            if (id != null) {
                // GetId hands over a string from the COM allocator, and it is ours from there on.
                Ole32.INSTANCE.CoTaskMemFree(id);
            }
            release(store);
            release(device);
        }
    }

    /**
     * PKEY_Device_FriendlyName out of an open property store, or null when it is not there. This is
     * the name Windows itself shows in its sound settings, so the mod's list reads the same as the
     * system's and a user comparing the two finds the same words.
     */
    private static String friendlyName(Pointer store) {
        Memory variant = new Memory(PROPVARIANT_BYTES);
        variant.clear();
        if (call(store, 5, friendlyNameKey(), variant) < 0) { // IPropertyStore::GetValue
            return null;
        }
        // A device with no name set answers with VT_EMPTY rather than an error, and the pointer at
        // offset 8 would then be whatever the type union happens to hold.
        Pointer text = (variant.getShort(0) & 0xFFFF) == VT_LPWSTR ? variant.getPointer(8) : null;
        if (text == null) {
            return null;
        }
        try {
            return text.getWideString(0);
        } finally {
            // The variant owns a string from the COM allocator and nothing else here will free it.
            Ole32.INSTANCE.CoTaskMemFree(text);
        }
    }

    /**
     * PKEY_Device_FriendlyName as the twenty bytes GetValue expects: the GUID
     * {A45C254E-DF1C-4EFD-8020-67D146A850E0} followed by its pid. Written field by field because a
     * GUID is not sixteen bytes in reading order - the first three fields are integers and land
     * little endian in memory, only the last eight bytes are a plain sequence.
     */
    private static Memory friendlyNameKey() {
        Memory key = new Memory(20);
        key.clear();
        key.setInt(0, 0xA45C254E);
        key.setShort(4, (short) 0xDF1C);
        key.setShort(6, (short) 0x4EFD);
        key.write(8, new byte[]{
                (byte) 0x80, 0x20, 0x67, (byte) 0xD1, 0x46, (byte) 0xA8, 0x50, (byte) 0xE0}, 0, 8);
        key.setInt(16, PID_DEVICE_FRIENDLY_NAME);
        return key;
    }

    /**
     * Opens the loopback stream on the default output, on the calling thread, or returns null when
     * it cannot be opened. Never throws. The returned instance belongs to the calling thread, see
     * the class comment.
     */
    public static WindowsLoopback open() {
        return open(null);
    }

    /**
     * The same on one chosen endpoint, named by the id of an {@link Endpoint} from {@link #outputs()}.
     * A null or blank id means the default output, so a caller whose setting is unset needs no case
     * of its own.
     *
     * <p>Returns null when that endpoint is gone. Ids are listed at one moment and opened at another,
     * and a headset can be unplugged in between, so a device that has disappeared is an ordinary
     * outcome here and never an exception. The caller decides whether to fall back to the default
     * output or to tell the user their device is missing.
     */
    public static WindowsLoopback open(String endpointId) {
        if (!available()) {
            return null;
        }
        WindowsLoopback loopback = new WindowsLoopback();
        try {
            if (loopback.initialize(endpointId)) {
                return loopback;
            }
        } catch (Throwable t) {
            SpotifyControl.LOGGER.error("WASAPI loopback could not be opened", t);
        }
        loopback.close();
        return null;
    }

    /**
     * Fills the array with mono samples at 48 kHz, blocking until there are enough, and returns
     * false once the stream has ended or been closed. On false the array holds an incomplete frame
     * and must be discarded.
     */
    public boolean read(short[] mono) {
        if (mono == null || closed || ended || captureClient == null) {
            return false;
        }
        // Read the setting once per frame, exactly as DesktopAudioCapture does, so that moving the
        // slider takes effect within one frame without being re-read for every sample.
        float gain = Math.max(0, ConfigManager.client().captureVolumePercent) / 100.0f;
        for (int i = 0; i < mono.length; i++) {
            int index = (int) position;
            while (index + 1 >= sourceCount) {
                compact();
                if (!fetch()) {
                    return false;
                }
                index = (int) position;
            }
            float a = source[index];
            float b = source[index + 1];
            float value = (float) (a + (b - a) * (position - index));
            int scaled = Math.round(value * 32767.0f * gain);
            mono[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            position += step;
        }
        return true;
    }

    /** Stops and releases everything. Safe to call twice, and safe to call after a failed open. */
    public void close() {
        // Flipped before the lock so a read() that is waiting for the next packet gives up at once
        // even when the stop came from another thread.
        closed = true;
        synchronized (this) {
            if (tornDown) {
                return;
            }
            tornDown = true;
            try {
                if (started && audioClient != null) {
                    call(audioClient, 11); // IAudioClient::Stop
                    started = false;
                }
                // Reverse of the order they were acquired in.
                captureClient = release(captureClient);
                if (mixFormat != null) {
                    Ole32.INSTANCE.CoTaskMemFree(mixFormat);
                    mixFormat = null;
                }
                audioClient = release(audioClient);
                device = release(device);
                enumerator = release(enumerator);
                if (comInitialized) {
                    comInitialized = false;
                    if (Thread.currentThread() == owner) {
                        Ole32.INSTANCE.CoUninitialize();
                    } else {
                        // CoUninitialize counts per thread. Calling it here would balance the wrong
                        // ledger, so the apartment is left to die with its own thread instead.
                        SpotifyControl.LOGGER.debug(
                                "WASAPI loopback closed from another thread, leaving COM initialised");
                    }
                }
            } catch (Throwable t) {
                SpotifyControl.LOGGER.warn("WASAPI loopback did not shut down cleanly", t);
            }
        }
    }

    /**
     * The one open path both entry points run. Only the two lines that fetch the IMMDevice differ
     * between the default output and a chosen one; everything from Activate onwards is identical,
     * which is why the whole thing takes an id instead of being written out twice.
     */
    private boolean initialize(String endpointId) {
        owner = Thread.currentThread();

        HRESULT init = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
        int hr = init.intValue();
        if (hr >= 0) {
            // S_FALSE means the thread was already initialised, and that still owes a matching
            // CoUninitialize, so both success codes count as ours to undo.
            comInitialized = true;
        } else if (hr != RPC_E_CHANGED_MODE) {
            return ok("CoInitializeEx", hr);
        }

        PointerByReference enumRef = new PointerByReference();
        if (!ok("CoCreateInstance(MMDeviceEnumerator)", Ole32.INSTANCE.CoCreateInstance(
                Com.CLSID_MM_DEVICE_ENUMERATOR, null, CLSCTX_ALL,
                Com.IID_IMM_DEVICE_ENUMERATOR, enumRef).intValue())) {
            return false;
        }
        enumerator = enumRef.getValue();

        PointerByReference deviceRef = new PointerByReference();
        if (endpointId == null || endpointId.isBlank()) {
            if (!ok("GetDefaultAudioEndpoint",
                    call(enumerator, 4, E_RENDER, E_CONSOLE, deviceRef))) {
                return false;
            }
        } else {
            // The id goes over as a wchar_t*, and the Memory block stays reachable through the
            // argument array for as long as the call runs.
            Memory id = new Memory((endpointId.length() + 1) * 2L);
            id.setWideString(0, endpointId);
            int hrDevice = call(enumerator, 5, id, deviceRef); // IMMDeviceEnumerator::GetDevice
            if (hrDevice < 0) {
                // Unplugged, uninstalled, or a config file carried over from another machine. None
                // of that is a fault, so it gets one plain line and a null out of open().
                SpotifyControl.LOGGER.info(
                        "WASAPI loopback: the selected output device is not present (hr=0x{}), id {}",
                        Integer.toHexString(hrDevice), endpointId);
                return false;
            }
        }
        device = deviceRef.getValue();

        PointerByReference clientRef = new PointerByReference();
        if (!ok("IMMDevice::Activate(IAudioClient)",
                call(device, 3, Com.IID_IAUDIO_CLIENT, CLSCTX_ALL, null, clientRef))) {
            return false;
        }
        audioClient = clientRef.getValue();

        PointerByReference formatRef = new PointerByReference();
        if (!ok("GetMixFormat", call(audioClient, 8, formatRef))) {
            return false;
        }
        mixFormat = formatRef.getValue();
        if (!readMixFormat()) {
            return false;
        }

        // The loopback flag is the whole trick: it turns a render endpoint into something that can
        // be read. The mix format has to go back unchanged, shared mode accepts no other.
        if (!ok("Initialize(shared, loopback)", call(audioClient, 3,
                SHARE_MODE_SHARED, STREAMFLAGS_LOOPBACK, ONE_SECOND, 0L, mixFormat, null))) {
            return false;
        }

        PointerByReference captureRef = new PointerByReference();
        if (!ok("GetService(IAudioCaptureClient)",
                call(audioClient, 14, Com.IID_IAUDIO_CAPTURE_CLIENT, captureRef))) {
            return false;
        }
        captureClient = captureRef.getValue();

        if (!ok("Start", call(audioClient, 10))) {
            return false;
        }
        started = true;

        String opened = deviceName(device);
        if (opened == null) {
            opened = endpointId == null || endpointId.isBlank() ? "the default output" : endpointId;
        }
        SpotifyControl.LOGGER.info(
                "WASAPI loopback open on {}: {} Hz, {} channels, {}, resampled to {} Hz",
                opened, sampleRate, channels, floatSamples ? "32 bit float" : "16 bit PCM",
                TARGET_RATE);
        return true;
    }

    /**
     * The user visible name of a device that is already open, or null. Its only job is to make the
     * log line above say which speakers are being captured, so any failure is simply no name.
     */
    private static String deviceName(Pointer device) {
        Pointer store = null;
        try {
            PointerByReference storeRef = new PointerByReference();
            if (call(device, 4, STGM_READ, storeRef) < 0) { // IMMDevice::OpenPropertyStore
                return null;
            }
            store = storeRef.getValue();
            return friendlyName(store);
        } catch (Throwable t) {
            return null;
        } finally {
            release(store);
        }
    }

    /**
     * Reads the WAVEFORMATEX the mixer runs at and decides whether we can convert it. Shared mode
     * offers no choice of format, so an unsupported one has to end the attempt: guessing would put
     * noise into the voice chat, which is worse than not broadcasting at all.
     */
    private boolean readMixFormat() {
        int tag = mixFormat.getShort(0) & 0xFFFF;
        channels = mixFormat.getShort(2) & 0xFFFF;
        sampleRate = mixFormat.getInt(4);
        blockAlign = mixFormat.getShort(12) & 0xFFFF;
        int bits = mixFormat.getShort(14) & 0xFFFF;
        int cbSize = mixFormat.getShort(16) & 0xFFFF;

        int sampleType = tag;
        if (tag == WAVE_FORMAT_EXTENSIBLE) {
            // Anything beyond two channels or sixteen bits arrives in this form, which is to say
            // almost always, and only the SubFormat GUID says what the samples really are.
            if (cbSize < 22) {
                SpotifyControl.LOGGER.warn(
                        "WASAPI loopback: extensible mix format without a SubFormat, cbSize={}", cbSize);
                return false;
            }
            sampleType = subFormatTag(mixFormat);
        }

        if (sampleType == WAVE_FORMAT_IEEE_FLOAT && bits == 32) {
            floatSamples = true;
        } else if (sampleType == WAVE_FORMAT_PCM && bits == 16) {
            floatSamples = false;
        } else {
            SpotifyControl.LOGGER.warn(
                    "WASAPI loopback: unsupported mix format, tag={} sampleType={} bits={}. "
                            + "Only 32 bit float and 16 bit PCM are handled.",
                    tag, sampleType, bits);
            return false;
        }

        bytesPerSample = bits / 8;
        if (channels < 1 || sampleRate < 8000 || sampleRate > 384000) {
            SpotifyControl.LOGGER.warn("WASAPI loopback: implausible mix format, {} Hz, {} channels",
                    sampleRate, channels);
            return false;
        }
        if (blockAlign < channels * bytesPerSample) {
            // Trust the derived size over an nBlockAlign that cannot even hold one frame.
            blockAlign = channels * bytesPerSample;
        }
        step = sampleRate / (double) TARGET_RATE;
        return true;
    }

    /**
     * The WAVE_FORMAT tag a KSDATAFORMAT SubFormat GUID stands for, or -1 for any other GUID. The
     * two that matter are {00000003-0000-0010-8000-00AA00389B71} for IEEE float and
     * {00000001-0000-0010-8000-00AA00389B71} for PCM.
     */
    private static int subFormatTag(Pointer wfx) {
        byte[] guid = wfx.getByteArray(SUBFORMAT_OFFSET, 16);
        for (int i = 0; i < KSDATAFORMAT_TAIL.length; i++) {
            if (guid[i + 4] != KSDATAFORMAT_TAIL[i]) {
                return -1;
            }
        }
        return (guid[0] & 0xFF) | ((guid[1] & 0xFF) << 8) | ((guid[2] & 0xFF) << 16)
                | ((guid[3] & 0xFF) << 24);
    }

    /**
     * Waits for one packet and appends it to {@link #source} as mono at the endpoint's own rate.
     * False means the stream is over, whether by close, by interrupt, or by the device going away.
     */
    private boolean fetch() {
        while (!closed) {
            IntByReference available = new IntByReference();
            int hr = call(captureClient, 5, available); // GetNextPacketSize
            if (hr < 0) {
                return failed("GetNextPacketSize", hr);
            }
            if (available.getValue() == 0) {
                if (!waitForPacket()) {
                    return false;
                }
                continue;
            }

            PointerByReference dataRef = new PointerByReference();
            IntByReference frames = new IntByReference();
            IntByReference flags = new IntByReference();
            hr = call(captureClient, 3, dataRef, frames, flags, null, null); // GetBuffer
            if (hr < 0) {
                return failed("GetBuffer", hr);
            }
            int count = frames.getValue();
            if (count == 0) {
                // AUDCLNT_S_BUFFER_EMPTY hands out no buffer, so there is nothing to release. Any
                // other success with an empty packet did hand one out and has to be released, or
                // the next GetBuffer comes back out of order.
                if (hr != AUDCLNT_S_BUFFER_EMPTY) {
                    call(captureClient, 4, 0);
                }
                if (!waitForPacket()) {
                    return false;
                }
                continue;
            }

            append(dataRef.getValue(), count, (flags.getValue() & BUFFERFLAGS_SILENT) != 0);
            call(captureClient, 4, count); // ReleaseBuffer
            return true;
        }
        return false;
    }

    private boolean waitForPacket() {
        try {
            Thread.sleep(PACKET_WAIT_MILLIS);
            return true;
        } catch (InterruptedException e) {
            // The capture thread is being stopped. Restore the flag so the caller's own loop sees it.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean failed(String what, int hr) {
        ended = true;
        if (hr == AUDCLNT_E_DEVICE_INVALIDATED) {
            SpotifyControl.LOGGER.info(
                    "WASAPI loopback ended: the captured output device changed or was removed");
        } else {
            SpotifyControl.LOGGER.warn("WASAPI loopback: {} failed, hr=0x{}", what,
                    Integer.toHexString(hr));
        }
        return false;
    }

    /** Downmixes one packet to mono by averaging its channels and appends it to the buffer. */
    private void append(Pointer data, int frames, boolean silent) {
        ensureCapacity(sourceCount + frames);
        if (silent || data == null) {
            // The buffer may hold anything at all when this flag is set, so it must not be read.
            Arrays.fill(source, sourceCount, sourceCount + frames, 0.0f);
        } else {
            float scale = 1.0f / channels;
            for (int frame = 0; frame < frames; frame++) {
                long base = (long) frame * blockAlign;
                float sum = 0.0f;
                for (int channel = 0; channel < channels; channel++) {
                    long offset = base + (long) channel * bytesPerSample;
                    sum += floatSamples ? data.getFloat(offset) : data.getShort(offset) / 32768.0f;
                }
                source[sourceCount + frame] = sum * scale;
            }
        }
        sourceCount += frames;
    }

    /**
     * Drops the samples the cursor has already passed and slides the rest to the front. The cursor
     * moves with them, which is how the fractional phase carries from one packet into the next.
     */
    private void compact() {
        int base = (int) position;
        if (base <= 0) {
            return;
        }
        if (base > sourceCount) {
            // Decimating from a high rate can step past the end of a packet. The cursor measures
            // from the start of the buffer, so clamping here and letting the samples that arrive
            // next take those indices skips exactly the frames it stepped over.
            base = sourceCount;
        }
        System.arraycopy(source, base, source, 0, sourceCount - base);
        sourceCount -= base;
        position -= base;
    }

    private void ensureCapacity(int capacity) {
        if (capacity <= source.length) {
            return;
        }
        int size = source.length;
        while (size < capacity) {
            size *= 2;
        }
        source = Arrays.copyOf(source, size);
    }

    private static boolean ok(String what, int hr) {
        if (hr < 0) {
            SpotifyControl.LOGGER.warn("WASAPI loopback: {} failed, hr=0x{}", what,
                    Integer.toHexString(hr));
            return false;
        }
        return true;
    }

    /**
     * Calls the method at the given vtable index, counting IUnknown's three. A COM interface
     * pointer points at its vtable, and the interface pointer itself is the hidden first argument.
     */
    private static int call(Pointer self, int index, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = self;
        System.arraycopy(args, 0, all, 1, args.length);
        Pointer method = self.getPointer(0).getPointer((long) index * Native.POINTER_SIZE);
        return Function.getFunction(method).invokeInt(all);
    }

    private static Pointer release(Pointer self) {
        if (self != null) {
            call(self, 2); // IUnknown::Release
        }
        return null;
    }
}
