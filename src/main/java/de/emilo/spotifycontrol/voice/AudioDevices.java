package de.emilo.spotifycontrol.voice;

import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.Lang;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AudioDevices {

    public static final AudioFormat FORMAT = new AudioFormat(48000.0f, 16, 2, true, false);
    public static final int FRAME_SAMPLES = 960;
    public static final int FRAME_BYTES = FRAME_SAMPLES * 4;

    /**
     * The device name that stands for what Windows is sending to the DEFAULT output, captured
     * through WASAPI loopback by {@link WindowsLoopback}. It is not a mixer name and never will be:
     * the JDK's sound system can only open inputs, so this entry exists to let the user pick a
     * source that {@link DesktopAudioCapture} then opens by a different route entirely.
     *
     * <p>It follows the default rather than naming one endpoint, which is the reason it stays above
     * the named endpoints below: a user who switches Windows from the speakers to a headset keeps
     * broadcasting, where a named endpoint would have to be picked again.
     *
     * <p>The prefix keeps it out of the way of real device names, which are whatever the driver
     * says they are, and makes it safe to write into the config file and read back on a later
     * start - including a start on a machine where the loopback is not available, where it simply
     * fails to open like any device that was unplugged.
     *
     * <p>The honest limit, which the interface has to keep saying: this is the whole mix, so
     * Minecraft's own sound is broadcast along with the music. A user who wants Spotify alone sends
     * Spotify into a virtual cable and picks that cable's endpoint below.
     */
    public static final String SYSTEM_OUTPUT = "spotifycontrol:system-output";

    /**
     * Head of the stored name of one named render endpoint. Same shape and same reasoning as
     * {@link #SYSTEM_OUTPUT}: it cannot collide with a driver's own device name, so a config
     * written here is never mistaken for a javax.sound mixer, by this version or by an older one.
     *
     * <p>The full form is {@code spotifycontrol:endpoint:<endpoint id>|<friendly name>}.
     *
     * <p>The id is the identity and the only half that is ever opened. It has to be, because a
     * friendly name identifies nothing: two headsets of the same model report the same name and
     * Windows is happy to have both plugged in. The endpoint id is unique and survives a restart,
     * which is what the config needs.
     *
     * <p>The friendly name rides along only so {@link #displayName(String)} can answer without
     * asking Windows. That matters because the settings row and the party panel print the selected
     * device on every frame, and enumerating COM endpoints sixty times a second to render one line
     * of text would cost more than the whole feature. It also lets an endpoint that is currently
     * unplugged still show as the headset it is instead of as a raw id.
     */
    private static final String ENDPOINT_PREFIX = "spotifycontrol:endpoint:";

    /**
     * Separates the id from the friendly name. Endpoint ids look like
     * {@code {0.0.0.00000000}.{guid}} and can never contain it; a friendly name that does contain
     * one is still read whole, because everything after the FIRST separator is the name.
     */
    private static final char ENDPOINT_SEPARATOR = '|';

    /**
     * Name fragments that mark a capture device as carrying what the machine is playing back
     * instead of what a microphone hears. Matched case insensitively against the human readable
     * mixer name, because javax.sound.sampled exposes nothing else about a device: the JDK has no
     * WASAPI loopback and no notion of device kind, so the name is the only evidence there is.
     *
     * <p>Not consulted on Windows any more. There the list comes from {@link WindowsLoopback} and
     * every entry is a render endpoint, which is an output by definition, so there is nothing left
     * to guess. This stays for Linux and macOS, where javax.sound is still the only door and the
     * name is still the only clue.
     *
     * <p>That makes this a heuristic and nothing more. It will miss loopback devices that were
     * renamed or that come from a driver nobody here has seen, and it can in principle match a
     * microphone whose name happens to contain one of these words. Therefore no code in this mod
     * may REFUSE to capture a device on the strength of this list - it may only warn the user that
     * the selected device looks like a microphone and that a microphone will be broadcast.
     *
     * <p>Order matters and expresses preference, see {@link #bestLoopback()}. Virtual cables come
     * first: an application such as Spotify can be routed into them alone, so the game's own sound
     * is not broadcast along with the music. The system wide mixes below them carry everything the
     * speakers play, which works but leaks the rest of the desktop into the voice chat.
     *
     * <p>{@link #SYSTEM_OUTPUT} sits above this whole list and is not part of it, because it is not
     * a device name and is not matched by anything here.
     */
    private static final String[] LOOPBACK_MARKERS = {
            "cable output",
            "virtual cable",
            "virtual audio cable",
            "vb-audio",
            "voicemeeter",
            "blackhole",
            "soundflower",
            "loopback",
            "monitor of",
            "monitor von",
            "stereomix",
            "stereo mix",
            "stereo-mix",
            "what u hear",
            "what you hear",
            "wave out mix",
            "waveout mix",
            "was sie h\u00f6ren",
            "was sie hoeren"
    };

    private AudioDevices() {
    }

    /**
     * A source the user may pick, together with what it records. On Windows {@code loopback} is
     * always true and is a fact rather than a guess, because a render endpoint cannot be anything
     * else; elsewhere it is the verdict of {@link #LOOPBACK_MARKERS}, where a false does not prove
     * the device is a microphone, it only means nothing in the name says otherwise.
     */
    public record CaptureDevice(String name, boolean loopback) {
    }

    /**
     * Whether this machine can hand us the sound it is playing without anything being installed.
     * False everywhere but Windows, and false on a Windows machine whose sound stack refuses the
     * loopback stream, so callers can treat it as a plain "is this offered here".
     */
    public static boolean systemOutputAvailable() {
        try {
            return WindowsLoopback.available();
        } catch (RuntimeException | LinkageError e) {
            // The loopback leans on JNA, which arrives with the game rather than with this mod. A
            // runtime that does not carry it must cost the user the system output entry, never the
            // whole device list.
            SpotifyControl.LOGGER.debug("System output capture is unavailable", e);
            return false;
        }
    }

    /**
     * What to show the user for a stored device name. Every screen that prints one goes through
     * here, so the settings row, the party panel and the toasts cannot drift apart or leak an
     * internal token into the interface.
     *
     * <p>The two synthetic names are unreadable by design and are translated or unpacked. A name
     * that came from javax.sound is already the string the operating system shows and is passed
     * through untouched rather than guessed at.
     *
     * <p>Answers from the stored name alone and enumerates nothing, because this is called from
     * rendering code once per frame.
     */
    public static String displayName(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return "";
        }
        if (SYSTEM_OUTPUT.equals(deviceName)) {
            return Lang.plain("audio.device_system");
        }
        if (deviceName.startsWith(ENDPOINT_PREFIX)) {
            String rest = deviceName.substring(ENDPOINT_PREFIX.length());
            int cut = rest.indexOf(ENDPOINT_SEPARATOR);
            // No separator means a hand edited config or a driver that reported no name at all.
            // The bare id is not pretty, but it is what is stored and it is the truth, which beats
            // printing the name of some device the user did not choose.
            return cut < 0 ? rest : rest.substring(cut + 1);
        }
        return deviceName;
    }

    /**
     * The endpoint id inside a stored device name, or null when the name does not carry one - that
     * is, for {@link #SYSTEM_OUTPUT}, for a javax.sound mixer name and for a blank name.
     * {@link DesktopAudioCapture} uses this to tell the three ways of opening a source apart.
     */
    public static String endpointId(String deviceName) {
        if (deviceName == null || !deviceName.startsWith(ENDPOINT_PREFIX)) {
            return null;
        }
        String rest = deviceName.substring(ENDPOINT_PREFIX.length());
        int cut = rest.indexOf(ENDPOINT_SEPARATOR);
        String id = cut < 0 ? rest : rest.substring(0, cut);
        return id.isBlank() ? null : id;
    }

    /**
     * Whether two stored names mean the same source. The right way for a screen to find its
     * selection in {@link #listDevices()}, because plain equality is not quite enough: the friendly
     * name is part of the stored string and a driver update or a rename in the Windows sound
     * settings changes it, which would leave a still present endpoint looking like one that is
     * gone. Endpoints are compared by id, everything else by the whole name.
     */
    public static boolean sameDevice(String first, String second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        String id = endpointId(first);
        if (id != null) {
            return id.equals(endpointId(second));
        }
        return first.equals(second);
    }

    /**
     * Every source the user may pick, best first. The order is the point: the settings screen
     * cycles through this list, so a user who never read the documentation lands on something that
     * can carry Spotify straight away, and the entry that needs no setting up at all is the one
     * they land on first.
     *
     * <p>On Windows that is the system output followed by one entry per render endpoint, and
     * nothing else. There is deliberately no microphone in it: every entry is captured by loopback,
     * so the user can no longer pick a source that broadcasts their room, and the name heuristic is
     * not consulted at all. Elsewhere it is the javax.sound mixers classified by name as before,
     * because that is the only door those platforms open.
     *
     * <p>Nothing here is cached. The JDK's own list is refreshed only when the NUMBER of devices
     * changes, which is why a virtual cable installed while the game was running never appeared; a
     * cache on this side would put that staleness straight back. Enumerating a handful of endpoints
     * is a few COM calls and happens only when a row is drawn or cycled.
     */
    public static List<CaptureDevice> listDevices() {
        List<CaptureDevice> endpoints = renderEndpoints();
        return endpoints != null ? endpoints : mixerDevices();
    }

    /**
     * The Windows list: the default output mix, then every active render endpoint. Null rather than
     * empty when this machine cannot do WASAPI loopback at all, so the caller can tell "no endpoints
     * here" apart from "ask javax.sound instead" - an empty list would claim there is no way to
     * capture and hide the mixers that are still there.
     */
    private static List<CaptureDevice> renderEndpoints() {
        if (!systemOutputAvailable()) {
            return null;
        }
        List<CaptureDevice> devices = new ArrayList<>();
        devices.add(new CaptureDevice(SYSTEM_OUTPUT, true));
        try {
            for (WindowsLoopback.Endpoint endpoint : WindowsLoopback.outputs()) {
                devices.add(new CaptureDevice(storedName(endpoint), true));
            }
        } catch (RuntimeException | LinkageError e) {
            // outputs() promises never to throw, so this is the belt to that braces: the list is
            // built while a screen is open, and a surprise from the COM layer has to cost the named
            // endpoints only. The default output entry is opened through a different call and may
            // well still work.
            SpotifyControl.LOGGER.warn("Could not enumerate the Windows output devices", e);
        }
        return devices;
    }

    /**
     * The stored form of one endpoint. The id carries the identity, the friendly name is appended
     * for display only; an endpoint whose driver reports no name keeps just the id, which
     * {@link #displayName(String)} then shows as it is.
     */
    private static String storedName(WindowsLoopback.Endpoint endpoint) {
        String name = endpoint.name();
        if (name == null || name.isBlank()) {
            return ENDPOINT_PREFIX + endpoint.id();
        }
        return ENDPOINT_PREFIX + endpoint.id() + ENDPOINT_SEPARATOR + name;
    }

    /**
     * The javax.sound list, which is what Linux and macOS use: every mixer that can deliver
     * {@link #FORMAT}, classified by name, loopback candidates first.
     */
    private static List<CaptureDevice> mixerDevices() {
        List<String> loopbacks = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                if (!AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    continue;
                }
                String name = mixerInfo.getName();
                if (markerRank(name) >= 0) {
                    loopbacks.add(name);
                } else {
                    rest.add(name);
                }
            } catch (Exception e) {
                SpotifyControl.LOGGER.debug("Skipping audio mixer {}", mixerInfo.getName());
            }
        }
        // List.sort is stable, so devices that matched the same marker keep the order the sound
        // system reported them in and the setting does not shuffle between game starts.
        loopbacks.sort(Comparator.comparingInt(AudioDevices::markerRank));
        List<CaptureDevice> devices = new ArrayList<>(loopbacks.size() + rest.size());
        for (String name : loopbacks) {
            devices.add(new CaptureDevice(name, true));
        }
        for (String name : rest) {
            devices.add(new CaptureDevice(name, false));
        }
        return devices;
    }

    /**
     * The name of the most suitable loopback source present, or null when the machine offers none.
     * Null used to be the common case on a stock Windows install, where Stereomix ships disabled and
     * no virtual cable is installed; on Windows it now only happens when the loopback itself cannot
     * be opened. Elsewhere callers should still read it as "tell the user how to get one", not as
     * an error.
     */
    public static String bestLoopback() {
        // Ahead of the enumeration, not merely first in the list: the system output needs nothing
        // installed and nothing enabled, and it follows the default output instead of pinning one
        // endpoint, so nothing found below is worth preferring over it. A user who wants Spotify
        // without the game's own sound picks their cable's endpoint by hand.
        if (systemOutputAvailable()) {
            return SYSTEM_OUTPUT;
        }
        for (CaptureDevice device : listDevices()) {
            if (device.loopback()) {
                return device.name();
            }
        }
        return null;
    }

    /**
     * Whether a device name records something the machine is playing rather than something it is
     * hearing. Callers use this on the name stored in the config, which may refer to a device that
     * is no longer present, so it deliberately does not enumerate anything.
     */
    public static boolean isLoopback(String deviceName) {
        // The two synthetic names are the cases that are not a guess: both name a render endpoint,
        // which is an output by construction, so they report as loopbacks even where they cannot be
        // opened. Saying otherwise would make the settings row call a headset a microphone on a
        // machine that has merely lost the device it was capturing from.
        if (SYSTEM_OUTPUT.equals(deviceName) || endpointId(deviceName) != null) {
            return true;
        }
        return deviceName != null && markerRank(deviceName) >= 0;
    }

    /**
     * Index of the first marker the name contains, or -1 for no match. The index doubles as the
     * preference score because {@link #LOOPBACK_MARKERS} is ordered best first.
     */
    private static int markerRank(String deviceName) {
        String name = deviceName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < LOOPBACK_MARKERS.length; i++) {
            if (name.contains(LOOPBACK_MARKERS[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Plain device names in the same order as {@link #listDevices()}, kept for the settings and
     * party screens which cycle through a List of String. Same signature as before, loopback
     * candidates come first, and the entries may be synthetic names, which are names to store and to
     * open but not names to show - put them through {@link #displayName(String)} before they reach
     * the screen.
     */
    public static List<String> list() {
        List<CaptureDevice> devices = listDevices();
        List<String> names = new ArrayList<>(devices.size());
        for (CaptureDevice device : devices) {
            names.add(device.name());
        }
        return names;
    }

    /**
     * Opens a named capture line, or null when the device is gone or refuses the format. Returns
     * null for both synthetic names as well: those sources are not javax.sound lines and are opened
     * by {@link DesktopAudioCapture} on its own capture thread, which is where WASAPI requires it.
     *
     * <p>Any other name is still looked up among the mixers exactly as before, which is what keeps
     * a config written by an older version working after this machine has moved to endpoints.
     */
    public static TargetDataLine open(String deviceName) {
        if (SYSTEM_OUTPUT.equals(deviceName) || endpointId(deviceName) != null) {
            return null;
        }
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        try {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (!mixerInfo.getName().equals(deviceName)) {
                    continue;
                }
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (!mixer.isLineSupported(info)) {
                    continue;
                }
                TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                line.open(FORMAT, FRAME_BYTES * 8);
                line.start();
                return line;
            }
        } catch (Exception e) {
            SpotifyControl.LOGGER.error("Could not open capture device {}", deviceName, e);
        }
        return null;
    }
}
