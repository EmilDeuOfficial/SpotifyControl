# SpotifyControl Wiki

Welcome to the SpotifyControl wiki. Use the sidebar to navigate between pages.

## What is SpotifyControl?

SpotifyControl is a Fabric mod that puts a Spotify client into Minecraft. A full overlay in the
Spotify colour theme opens on a key combination, connects to your own Spotify account over the
official Web API, and lets you search, browse your library, manage playlists and control playback
without alt-tabbing.

This means:
- Playback control, search and playlist management from inside the game
- Listening parties where everyone on the server hears the same music
- No audio ripping: sync mode drives each player's own Spotify, broadcast mode streams the host's
  desktop audio through Simple Voice Chat

## Quick Start

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.x
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your `mods/` folder
3. Drop `SpotifyControl_1.0_1.21.x.jar` into your `mods/` folder
4. Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) with
   redirect URI `http://127.0.0.1:8888/callback`
5. Start the game, press Ctrl + M, open Settings, paste the client ID and click Connect to Spotify

## Wiki Pages

| Page | Description |
|---|---|
| [Installation](Installation) | How to install and set up the Spotify app |
| [Commands](Commands) | All `/spotifycontrol` commands with examples |
| [Permissions](Permissions) | Permission levels and LuckPerms Fabric setup |
| [Configuration](Configuration) | Every config option explained |
| [Listening Parties](Listening-Parties) | Sync mode, broadcast mode and the audio source |
| [Streaming only Spotify](Streaming-Only-Spotify) | Step by step, so the party hears the music and nothing else |
| [FAQ](FAQ) | Common questions and troubleshooting |

## Compatibility

| | |
|---|---|
| Minecraft | 1.21 to 1.21.11 |
| Fabric Loader | 0.16+ |
| Fabric API | Latest for your Minecraft version |
| Java | 21+ |
| Simple Voice Chat | 2.5+, optional |

Forge, NeoForge and Quilt are not supported. One jar covers the whole 1.21 line, it detects the
Minecraft version on startup and loads the matching client implementation.
