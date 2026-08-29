# FAQ

### The overlay does not open when I press M

The default binding is Ctrl + M, not M alone. Check the Overlay key row in the overlay settings, or
rebind it in the vanilla Controls screen under the SpotifyControl category. To drop the modifier, set
`modifierKey` to `NONE` in `client.json` or cycle the Modifier key row in the settings.

### The overlay says "No Spotify client ID configured"

You have not pasted a client ID yet. Create an app at developer.spotify.com/dashboard, add
`http://127.0.0.1:8888/callback` as a redirect URI, copy the client ID and paste it into the settings
tab. Full steps are on the [Installation](Installation) page.

### The browser says INVALID_CLIENT: Invalid redirect URI

The redirect URI registered in the Spotify dashboard does not match what the mod sends. The mod uses
`http://127.0.0.1:<callbackPort>/callback`. Note that `localhost` is not accepted by Spotify any more,
it has to be the numeric `127.0.0.1`, and the port has to match `callbackPort` in `client.json`.

### The browser opens but the page never returns to Minecraft

The local callback server binds to 127.0.0.1 on `callbackPort`. Something else is probably using that
port. Change `callbackPort` in `client.json`, register the matching redirect URI in the dashboard,
and try again.

### Play, pause and skip do nothing

Two common causes.

Spotify Premium is required for playback control. The Spotify plan row in the overlay settings shows
what your account is. With a free account the API answers those calls with HTTP 403 and the overlay
shows "Spotify Premium is required for playback control".

Spotify also needs an active device. The Web API controls a running Spotify player, it is not a
player itself. Start Spotify on your desktop, phone or the web player, then pick it from the device
menu in the player bar (the small monitor icon on the right).

### Do I need to have the Spotify app open?

Not by hand. The Spotify Web API controls a running Spotify player, it cannot play audio itself, so
some player has to exist. When you press play and none is running, the mod starts the desktop app
minimized in the background, waits until it appears as a device and moves playback there. The app
stays in the taskbar or tray, the game keeps focus, and you control everything from the overlay.

A player built into the mod is not possible with the official API. Unofficial Spotify Connect
libraries exist, but they violate the Spotify terms of service and have been breaking regularly since
Spotify changed its audio key handling in 2025, so the mod does not bundle one.

### Spotify opened in front of the game

Turn on "Start Spotify minimized" in the settings (it is the default). On Windows the mod launches
`Spotify.exe --minimized`; if only the protocol handler is available it minimizes the window a few
seconds after launch. Alt-Tab back once if a launch still steals focus.

### The heart does not show that a song is already liked

Liked state is fetched from Spotify in batches of 50 for every list that opens, for the track in the
player bar whenever it changes, and everything in the Liked Songs view is marked as liked right away.
It arrives about a second after the list, so a freshly opened list shows outlined hearts for a moment.
If it never updates, the Spotify app was created without the `user-library-read` scope: log out and
connect again so the new consent screen grants it.

### The device menu is empty

Spotify only lists devices that are running and signed in with the same account. Open the Spotify app
once and play a second of anything, then click the device icon again. The overlay refreshes the list
each time you open the menu.

### Album art does not show up

Check that `showAlbumArt` is true, then look at the Album art row in the settings tab: it counts
loaded and failed covers and shows the last error message. Covers come from Spotify's image CDN, so a
blocked outbound connection leaves the note placeholder in place; failed downloads are retried every
30 seconds. The first frame after opening a playlist always shows placeholders while the images
download and get scaled. The first five failures are also written to the game log with their cause.

Spotify serves playlist covers as WebP, which neither Java's ImageIO nor Minecraft's own image loader
can decode (Minecraft accepts PNG only since 1.21.2). The mod detects the WebP signature and fetches
the same image as JPEG from `mosaic.scdn.co` instead. A log line saying `unsupported image format
webp` means that fallback host did not have the image either; please report the URL.

### Other players cannot hear anything in broadcast mode

Work through this list:

1. Simple Voice Chat has to be installed on the server and on every client, not just the host.
2. `allowAudioBroadcast` in `server.json` has to be true.
3. The host needs an audio source. On Windows the row starts on System sound (automatic) and needs
   nothing further; the status dot next to Start streaming turns green while frames are flowing.
4. On Linux and macOS, and on Windows after picking a device by hand, that line has to be a loopback
   device and not a microphone. The device list marks which entries look like an output and which
   look like a microphone. See [Listening Parties](Listening-Parties) for how to set one up.
5. Listeners must not have voice chat muted or the SpotifyControl category turned down in the Simple
   Voice Chat volume settings.

### The party hears my microphone instead of Spotify

A microphone is selected as the audio source. Broadcast mode reads whatever you pick, so that is
what goes out. Muting yourself in the voice chat does not stop it: the mod captures straight from the
sound system, and the mute button does not touch that.

On Windows, set the audio source back to System sound (automatic), which takes the mix from the
speakers and can never be a microphone. On Linux and macOS pick a loopback device instead, an input
that carries what the machine is playing: the "Monitor of" source of PipeWire or PulseAudio, or a
driver such as BlackHole. [Listening Parties](Listening-Parties) has the steps per system. In the mod
the loopback candidates come first in the device list, every entry is marked as one that looks like
an output or one that looks like a microphone, and a stream from a microphone is announced as exactly
that.

### Broadcast mode also sends my Discord calls and notifications

That is what the speaker mix carries: everything your system plays, Minecraft included. The
automatic capture on Windows, Stereo Mix and a Linux monitor source are all the same in this. For a
clean feed, route Spotify alone into a virtual audio cable and select that cable in the mod by hand,
or mute the applications you do not want to send.

### Sync mode does not follow the host

Every member needs their own Premium account and an active device. Members on a free account stay on
whatever they were playing, the mod does not try and fails silently on their side. The overlay
settings show the plan the account is on.

### Can I run this without Simple Voice Chat?

Yes. Everything except broadcast mode works without it. The Voice chat row in the overlay settings
shows whether it was detected, and broadcast mode is greyed out with a reason when it was not.

### Does this download or rip Spotify audio?

No. Sync mode only sends track URIs and positions, and each client plays through its own Spotify
account. Broadcast mode captures the operating system's audio output on the host machine, the same
thing any screen sharing tool does, and streams it as voice chat audio. The mod never decodes or
stores Spotify's audio streams.

### Which Minecraft versions are supported?

1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10 and 1.21.11,
all from `SpotifyControl_VERSION_1.21.x.jar`.

### How can one jar cover versions with incompatible APIs?

Minecraft changed the client rendering and input APIs six times during the 1.21 line. The calls that
moved sit behind an interface, and the jar carries one implementation per generation, each compiled
against its own Minecraft version. On startup the mod reads the Minecraft version from Fabric Loader
and loads only the matching one, so the five that would not run are never touched by the class loader.

Everything else is shared: 66 of the 69 Minecraft methods the rest of the mod calls are unchanged
across all twelve releases, and the three that are not go through the same interface.

### Where are my tokens stored and is that safe?

`config/spotifycontrol/tokens.json`, in plain text, on your own machine. Anyone with that file can
act on your Spotify account within the granted scopes, so do not share it or include it in a modpack
export. Click Log out in the settings to delete it, and revoke the app at
spotify.com/account/apps to invalidate the refresh token on Spotify's side.

### Does the server see my Spotify account?

Only the track metadata a host chooses to share with a party: track URI, title, artist, cover URL,
duration and position. Your tokens, your library and your search queries never leave your client.
