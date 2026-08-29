# Configuration

SpotifyControl writes two config files plus a token store into `config/spotifycontrol/`.

| File | Side | Purpose |
|---|---|---|
| `server.json` | Server | Party rules, audio limits, chat language |
| `client.json` | Client | Spotify app, overlay, HUD, key modifier, audio capture |
| `tokens.json` | Client | OAuth access and refresh token, never share this file |

`/spotifycontrol reload` re-reads `server.json`. `client.json` is written whenever you change a
setting in the overlay and re-read on game start.

## server.json

```json
{
  "enabled": true,
  "language": "en",
  "allowListeningParties": true,
  "allowAudioBroadcast": true,
  "maxPartySize": 20,
  "broadcastRangeBlocks": 0,
  "requireSameDimension": true,
  "maxAudioKilobitsPerSecond": 96,
  "announcePartyInChat": true,
  "broadcastNowPlayingToParty": true,
  "partyIdleTimeoutSeconds": 120
}
```

### enabled

Master switch. When false, party actions are rejected and all clients are told the feature is off.
Equivalent to `/spotifycontrol off`.

### language

Language file used for the chat messages the server sends. One of `en`, `de`, `ru`, `es`, `fr`, `ja`,
`zh`. Unknown values fall back to English.

### allowListeningParties

Set to false on servers that do not want the feature at all. Players keep their private overlay.

### allowAudioBroadcast

Controls broadcast mode only. Sync mode stays available. Has no effect when Simple Voice Chat is not
installed, in which case broadcast mode is unavailable anyway.

### maxPartySize

Host plus members. A join attempt past this number is rejected.

### broadcastRangeBlocks

Decides how the audio reaches listeners in broadcast mode.

| Value | Behaviour |
|---|---|
| `0` | One static Simple Voice Chat channel per party member. Only members hear the stream, anywhere in the world. |
| `> 0` | One locational channel at the host's position with this radius. Everyone nearby hears it, party membership is not required. |

The locational variant is the boombox behaviour: stand near the host and the music fades in.

### requireSameDimension

Only relevant for static channels. When true, party members in another dimension get no audio until
they return. Set to false to keep the stream running across the Nether portal.

### maxAudioKilobitsPerSecond

Reported to clients as the broadcast budget. A 20 ms Opus frame at 96 kbit/s is around 240 bytes, so
one broadcasting host produces roughly 12 kB/s upstream and the server sends that to each listener.

### announcePartyInChat

Sends join and leave lines to the party. Turn off on servers with strict chat rules.

### broadcastNowPlayingToParty

Forwards the host's track metadata to members. Sync mode depends on this, and the now-playing HUD of
guests shows the host's track through it. Turning it off leaves broadcast mode working but silent on
metadata.

### partyIdleTimeoutSeconds

Closes a broadcast party when no audio frame arrived for this long, so a host who alt-F4s does not
leave a channel open. Set to 0 to disable the timeout.

## client.json

```json
{
  "language": "en",
  "spotifyClientId": "",
  "callbackPort": 8888,
  "requireModifierKey": true,
  "modifierKey": "CONTROL",
  "playbackPollMillis": 2000,
  "showNowPlayingHud": true,
  "hudCorner": "TOP_RIGHT",
  "hudGuiScale": 2,
  "appMargin": 10,
  "startView": "HOME",
  "smoothGraphics": true,
  "hudMargin": 4,
  "overlayGuiScale": 2,
  "pauseGameOnOverlay": false,
  "blurBackground": true,
  "overlayVolumeStep": 5,
  "autoLaunchSpotify": true,
  "minimizeSpotifyWindow": true,
  "preferredDeviceName": "",
  "captureDeviceName": "",
  "captureVolumePercent": 100,
  "muteMinecraftMusicWhileListening": true,
  "showAlbumArt": true,
  "albumArtCacheSize": 128,
  "backgroundDim": 50,
  "accentColor": 0,
  "overlayKeyCode": -1,
  "scrollingTitles": true,
  "dimUnavailableTracks": true,
  "showTrackTime": true,
  "showShuffleRepeat": true,
  "showVolumeSlider": true,
  "showQueueButton": true,
  "showDeviceButton": true,
  "showLikeButton": true,
  "hudShowAlbumArt": true,
  "hudShowProgress": true,
  "hudShowArtist": true,
  "hudOnlyWhilePlaying": false,
  "pauseOnLeave": true,
  "closeSpotifyOnQuit": true
}
```

### spotifyClientId

Client ID of the Spotify app you registered. Without it the overlay only offers the connect prompt.
See [Installation](Installation) for how to get one.

### callbackPort

Port of the temporary local HTTP server that receives the OAuth callback. The redirect URI registered
in the Spotify dashboard has to use the same port. The server is only running during a login and is
bound to 127.0.0.1.

### requireModifierKey and modifierKey

When `requireModifierKey` is true, the configured modifier has to be held down for the SpotifyControl
key bindings to fire. Valid values are `CONTROL`, `SHIFT`, `ALT` and `NONE`. Setting `modifierKey` to
`NONE` in the overlay also clears `requireModifierKey`.

### playbackPollMillis

How often the client asks Spotify for the current playback state. Values are clamped to 500 to 5000.
Between polls the progress bar advances locally, so a slower poll costs accuracy but not smoothness.
The Spotify API rate limit is generous but not unlimited, 1000 is a good default.

### showNowPlayingHud, hudCorner, hudGuiScale, hudMargin

The small in-world card. `hudCorner` is one of `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`,
`BOTTOM_RIGHT`. `hudGuiScale` uses the same steps as `overlayGuiScale`: 0 follows the game scale, otherwise it is
the gui scale the HUD renders at, never larger than the game scale. `hudMargin` is measured from the chosen corner
in GUI pixels.

### overlayGuiScale

The GUI scale the overlay is rendered at, independent of the game's own setting. Minecraft's auto
scale picks 4 on a 1080p screen, which makes a full-screen overlay huge and cramped. With the default
of 2 the overlay draws as if the game ran at scale 2: text and icons at half the size, four times the
room for playlists. 0 follows the game scale exactly. The overlay is never rendered larger than the
game scale, so 3 on a scale-2 game behaves like 0. Also changeable through the Overlay scale row in
the settings tab.

### pauseGameOnOverlay

Pauses singleplayer while the overlay is open. Off by default so you can keep an eye on the world.

### blurBackground

Applies the vanilla screen blur behind the overlay. Turn it off on weak hardware.

### overlayVolumeStep

How many percent the volume key bindings move per press.

### autoLaunchSpotify, minimizeSpotifyWindow, preferredDeviceName

When you press play and Spotify reports no device, the mod starts the desktop app itself: on Windows
`%APPDATA%\Spotify\Spotify.exe --minimized` (or the Microsoft Store alias, or the `spotify:` URL
handler with a PowerShell call that minimizes the window afterwards), on macOS `open -g -a Spotify`,
on Linux `spotify`, the Flatpak, the Snap or `xdg-open spotify:`. It then polls the device list for
up to 30 seconds, transfers playback to the new device and runs the command you asked for. Set
`autoLaunchSpotify` to false to get the plain "no active device" message instead.
`minimizeSpotifyWindow` only affects the Windows launch. `preferredDeviceName` picks a specific
device when several are available, for example a speaker; leave it empty to prefer the active one
and otherwise any computer.

### captureDeviceName

The audio source used in broadcast mode. Cycle through the available sources with the Audio capture
device row in the overlay settings rather than typing a name by hand.

With no device name stored, which is how the config starts, the source is the automatic capture,
shown as System sound (automatic) in that row: on Windows it takes the mix going to the speakers
straight from the sound system, so it names no device and needs no setup. Any other value names an
input line, exactly as the operating system reports it, and that line has to be a loopback line and
not a microphone: the row lists the loopback candidates first and marks whether an entry looks like
an output or like a microphone. On Linux and macOS a named loopback line is the only way, see
[Listening Parties](Listening-Parties) for how to get one on your system.

### captureVolumePercent

Gain applied to the captured audio before Opus encoding, 0 to 200. Use it when the loopback line is
much quieter or louder than voice chat.

### muteMinecraftMusicWhileListening

Stops the vanilla music manager while Spotify is playing, so the two do not overlap. This does not
change your sound settings, it only cancels the currently playing background track.

### smoothGraphics

Icons, rounded corners, circles and album art are drawn through a small vector rasterizer. With this
on, shapes are rasterized at the real screen resolution with anti-aliasing, which is what keeps the
overlay crisp at GUI scale 3 or 4. With it off, the very same shapes are resolved coarsely, two
screen pixels per drawn pixel, and their coverage is rounded to fully on or off, which gives hard
edged pixel art rather than a blurred version of the smooth look. There is only one renderer either
way. The rasterized shapes are cached as textures, at most 512 at a time, keyed by render mode so the
two never share one. If a texture upload ever fails, the mod falls back to the plain pixel renderer
on its own and logs the reason.

### Appearance

`backgroundDim` controls how far the game behind the overlay is
darkened. `scrollingTitles` makes a title that does not fit slide back and forth the way the Spotify
client does; turn it off to get a plain ellipsis instead. `dimUnavailableTracks` greys out titles
that Spotify reports as unplayable in your country and stops a click from starting them; this needs
your account country, which the mod reads once when connecting.

All five can be changed in the overlay settings without editing the file.

### showAlbumArt and albumArtCacheSize

Album covers are downloaded on demand, decoded with ImageIO, scaled with a box filter to exactly the
size they are drawn at (times the GUI scale, capped at 256 px) and uploaded as GPU textures, one per
cover and size. Playlist covers arrive as WebP from Spotify's image CDN and are re-fetched as JPEG from
`mosaic.scdn.co`, because no decoder in the game runtime handles WebP.
`albumArtCacheSize` is the number of those textures kept before the oldest ones are released,
minimum 32. Failed downloads are retried after 30 seconds, the first five failures are logged at WARN
level, and the Album art row in the settings tab shows a running count plus the last error. Set
`showAlbumArt` to false to skip image downloads entirely.

## tokens.json

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "expiresAtEpochSecond": 1750000000,
  "userId": "...",
  "displayName": "...",
  "product": "premium"
}
```

Written after a successful login and rewritten on every token refresh. Treat it like a password:
anyone with the refresh token can act on your Spotify account within the granted scopes. Deleting the
file or clicking Log out disconnects the account locally. To revoke access on Spotify's side, use
[spotify.com/account/apps](https://www.spotify.com/account/apps).
