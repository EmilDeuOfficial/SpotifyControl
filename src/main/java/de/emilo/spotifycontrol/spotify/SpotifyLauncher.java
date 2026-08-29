package de.emilo.spotifycontrol.spotify;

import de.emilo.spotifycontrol.SpotifyControl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class SpotifyLauncher {

    private static final String MINIMIZE_SCRIPT = String.join("; ",
            "Add-Type -Namespace SpotifyControl -Name Native -MemberDefinition "
                    + "'[DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);'",
            "Start-Sleep -Seconds 4",
            "Get-Process -Name Spotify -ErrorAction SilentlyContinue "
                    + "| Where-Object { $_.MainWindowHandle -ne 0 } "
                    + "| ForEach-Object { [SpotifyControl.Native]::ShowWindow($_.MainWindowHandle, 6) | Out-Null }");

    private SpotifyLauncher() {
    }

    /**
      * Closes the Spotify desktop app. On Windows the plain close request only minimises Spotify to
      * the tray when the user has that turned on, so the process is ended outright; Spotify keeps
      * its state on disk continuously and starts up again without complaint.
      */
    public static void quit() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                start("taskkill", "/IM", "Spotify.exe", "/F");
            } else if (os.contains("mac")) {
                start("osascript", "-e", "quit app \"Spotify\"");
            } else {
                start("pkill", "-TERM", "-x", "spotify");
            }
        } catch (Exception e) {
            SpotifyControl.LOGGER.debug("Could not close Spotify", e);
        }
    }

    public static boolean launch(boolean minimized) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return launchWindows(minimized);
            }
            if (os.contains("mac")) {
                return start("open", "-g", "-a", "Spotify");
            }
            return start("spotify")
                    || start("flatpak", "run", "com.spotify.Client")
                    || start("snap", "run", "spotify")
                    || start("xdg-open", "spotify:");
        } catch (Exception e) {
            SpotifyControl.LOGGER.warn("Could not start Spotify", e);
            return false;
        }
    }

    private static boolean launchWindows(boolean minimized) {
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            File desktop = new File(appData, "Spotify" + File.separator + "Spotify.exe");
            if (desktop.isFile()) {
                return minimized
                        ? start(desktop.getAbsolutePath(), "--minimized")
                        : start(desktop.getAbsolutePath());
            }
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            File alias = new File(localAppData, "Microsoft" + File.separator + "WindowsApps" + File.separator
                    + "Spotify.exe");
            if (alias.isFile()) {
                boolean started = minimized ? start(alias.getAbsolutePath(), "--minimized") : start(alias.getAbsolutePath());
                if (started) {
                    return true;
                }
            }
        }
        boolean started = start("cmd", "/c", "start", "", "spotify:");
        if (started && minimized) {
            minimizeWindowsSpotify();
        }
        return started;
    }

    private static void minimizeWindowsSpotify() {
        String encoded = Base64.getEncoder().encodeToString(MINIMIZE_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        start("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
    }

    private static boolean start(String... command) {
        try {
            new ProcessBuilder(List.of(command))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            SpotifyControl.LOGGER.info("Ran {} for Spotify", command[0]);
            return true;
        } catch (IOException e) {
            SpotifyControl.LOGGER.debug("Launcher candidate {} not available", command[0]);
            return false;
        }
    }
}
