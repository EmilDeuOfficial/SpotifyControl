# Installation

## Requirements

| Requirement | Version |
|---|---|
| Minecraft | 1.21 to 1.21.11 |
| Fabric Loader | 0.16 or higher |
| Fabric API | Latest for your Minecraft version |
| Java | 21 or higher |
| Simple Voice Chat | 2.5 or higher, only for broadcast mode |
| Spotify account | Premium for playback control, any account for browsing |

Forge, NeoForge and Quilt are not supported.

## Step-by-step

### 1. Install Fabric Loader

Download and run the [Fabric Installer](https://fabricmc.net/use/) for Minecraft 1.21.x. For a
dedicated server, use the server installer option.

### 2. Download Fabric API

Download the latest [Fabric API](https://modrinth.com/mod/fabric-api) for your Minecraft version and
place it in your `mods/` folder.

### 3. Download SpotifyControl

Download `SpotifyControl_VERSION_1.21.x.jar` from the
[Releases page](https://github.com/EmilDeuOfficial/SpotifyControl/releases). That one jar runs on
every Minecraft version from 1.21 to 1.21.11.

### 4. Place in mods folder

```
.minecraft/
└── mods/
    ├── fabric-api-VERSION.jar
    ├── voicechat-fabric-VERSION.jar   (optional, for broadcast mode)
    └── SpotifyControl_1.0_1.21.x.jar
```

For listening parties the mod also has to be installed on the server. Broadcast mode needs Simple
Voice Chat on both sides.

### 5. Start the game

SpotifyControl writes its defaults to `config/spotifycontrol/client.json` on the client and
`config/spotifycontrol/server.json` on the server during the first launch.

### 6. Verify

The log contains:

```
[SpotifyControl] SpotifyControl initialized (voice chat detected)
[SpotifyControl] SpotifyControl client ready, overlay bound to Control + M
```

## Creating the Spotify app

Spotify does not allow shipping a client ID inside a public mod, so every user registers their own
application. It takes about a minute and is free.

1. Sign in at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard).
2. Click Create app. Name and description can be anything.
3. Under Redirect URIs add `http://127.0.0.1:8888/callback` and save.
4. Under APIs used, tick Web API.
5. Open the app settings and copy the Client ID. The client secret is not needed, the mod uses PKCE.

If you change `callbackPort` in `client.json`, register the matching URI instead, for example
`http://127.0.0.1:9000/callback`.

## Connecting the account

1. Press Ctrl + M in game.
2. Click Settings in the sidebar.
3. Paste the client ID into the Client ID field.
4. Scroll to Spotify account and click Connect.
5. A browser tab opens on accounts.spotify.com. Approve the requested scopes.
6. The tab shows a green Connected page and the overlay switches to your library.

Tokens are written to `config/spotifycontrol/tokens.json` and refreshed automatically. Click Log out
in the settings to remove them.

With [Mod Menu](https://modrinth.com/mod/modmenu) installed, the Configure button on the SpotifyControl
entry opens the same settings tab, also from the title screen before any world is loaded.

## Updating

Replace the old jar in `mods/` with the new one. Config files and tokens carry over.

## Uninstalling

Delete the jar from `mods/`. To remove the stored Spotify tokens as well, delete
`config/spotifycontrol/`. Revoking access to the app on
[spotify.com/account/apps](https://www.spotify.com/account/apps) invalidates the refresh token
server side.
