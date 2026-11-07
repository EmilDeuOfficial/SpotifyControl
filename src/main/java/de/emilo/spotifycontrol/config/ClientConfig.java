package de.emilo.spotifycontrol.config;

public class ClientConfig {

    public String language = "en";
    public String spotifyClientId = "";
    public int callbackPort = 8888;
    public boolean requireModifierKey = true;
    public String modifierKey = "CONTROL";
    /** How often the playback state is fetched while the app or the overlay is on screen. */
    public int playbackPollMillis = 2000;
    public boolean showNowPlayingHud = true;
    public String hudCorner = "TOP_RIGHT";
    /** Like the overlay scale: 0 follows the game, otherwise the gui scale to render at. */
    public int hudGuiScale = 2;
    /** One distance for both axes, measured from the chosen corner. */
    public int hudMargin = 4;
    public int overlayGuiScale = 2;
    public boolean pauseGameOnOverlay = false;
    public boolean blurBackground = true;
    public int overlayVolumeStep = 5;
    public boolean autoLaunchSpotify = true;
    public boolean minimizeSpotifyWindow = true;
    public String preferredDeviceName = "";

    /**
     * Which source the listening party broadcasts.
     *
     * <p>Empty does not mean "no source", it means nothing has been chosen yet: the mod then picks
     * the best source it can find and writes that choice back here, so a user who never opens this
     * setting still broadcasts the music rather than a microphone. On Windows the best source is
     * the speaker mix, which needs nothing installed; elsewhere it is whichever loopback the sound
     * system offers, and a machine that offers none is the only case where the broadcast has no
     * source at all.</p>
     *
     * <p>Otherwise this holds a mixer name exactly as the sound system reports it, or the synthetic
     * marker {@code AudioDevices.SYSTEM_OUTPUT}, which stands for the speaker mix and is not a
     * mixer name. That marker is an internal token, so it must be translated before it is shown -
     * never print this field to the user as it is stored.</p>
     */
    public String captureDeviceName = "";
    public int captureVolumePercent = 100;
    public boolean muteMinecraftMusicWhileListening = true;
    public boolean showAlbumArt = true;
    public int albumArtCacheSize = 128;

    /** How strongly the game behind the overlay is darkened, in percent. */
    public int backgroundDim = 50;

    /** Index into the accent palette in {@code Theme}. */
    public int accentColor = 0;

    /** Overlay key as a GLFW code. -1 keeps the key binding from the game's control settings. */
    public int overlayKeyCode = -1;

    /** Distance between the screen edge and the app window, on both axes. */
    public int appMargin = 10;

    /** Which page the app opens on: HOME, SEARCH, LIBRARY or LIKED. */
    public String startView = "HOME";

    /** Draw the interface at full resolution. Off gives the blocky look below. */
    public boolean smoothGraphics = true;

    /** Fades, presses and loading movement. Off draws every state at once, as it used to. */
    public boolean animations = true;

    /** Slide a long track title back and forth instead of cutting it off. */
    public boolean scrollingTitles = true;

    // Parts of the player bar that can be switched off.
    public boolean showTrackTime = true;
    public boolean showShuffleRepeat = true;
    public boolean showVolumeSlider = true;
    public boolean showQueueButton = true;
    public boolean showDeviceButton = true;
    public boolean showLikeButton = true;

    // Parts of the small now playing overlay.
    public boolean hudShowAlbumArt = true;
    public boolean hudShowProgress = true;
    public boolean hudShowArtist = true;

    /** Hide the small overlay while nothing is playing. */
    public boolean hudOnlyWhilePlaying = false;

    /** Pause Spotify when leaving a world or server. */
    public boolean pauseOnLeave = true;

    /** Close the Spotify desktop app when Minecraft closes. */
    public boolean closeSpotifyOnQuit = true;

    /** Dim tracks that Spotify reports as unplayable in your country. */
    public boolean dimUnavailableTracks = true;

    /**
     * Place the lyrics of the playing track into the world as floating text. The lyrics are looked
     * up on LRCLIB, a service outside Spotify.
     */
    public boolean worldLyrics = true;

    /** How many words share one block. */
    public int lyricsWordsPerPiece = 1;

    /** Nearest and farthest a new piece appears in front of the player, in blocks. */
    public int lyricsMinDistance = 10;
    public int lyricsMaxDistance = 20;

    /**
     * Added to both distances while sprinting, on foot or flying, so the lyrics appear far enough
     * ahead to be read before the player has run past them.
     */
    public int lyricsSprintExtraDistance = 15;

    /** Tilt each piece of the lyrics a little at random, sideways and towards the player. */
    public boolean lyricsTilt = true;

    /** Size of the lyrics text, in percent of the normal size. */
    public int lyricsTextSize = 75;

    /** Show the button that switches the world lyrics on and off next to the queue button. */
    public boolean showLyricsButton = true;

    /** How many pieces stay in the world before the oldest one is removed. */
    public int lyricsKept = 40;

    /**
     * When a piece of the lyrics fades out: LINE when its line has been sung, COUNT when more than
     * {@link #lyricsKept} pieces are in the world, SECONDS after {@link #lyricsSeconds}. LINE also
     * keeps to the count, so a long line cannot fill the world.
     */
    public String lyricsKeepMode = "LINE";

    /** How long a piece stays in the world in the SECONDS mode. */
    public int lyricsSeconds = 5;
}
