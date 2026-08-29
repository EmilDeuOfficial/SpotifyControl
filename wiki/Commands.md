# Commands

Full command: `/spotifycontrol`. Alias: `/sc`.

| Command | Permission level | Description |
|---|---|---|
| `/spotifycontrol on` | 2 | Enable the mod globally |
| `/spotifycontrol off` | 2 | Disable the mod globally and close all parties |
| `/spotifycontrol status` | 0 | Show state, party count, listener count and voice chat status |
| `/spotifycontrol reload` | 2 | Reload the server config and push it to all clients |
| `/spotifycontrol party start [sync\|broadcast]` | 0 | Open a listening party |
| `/spotifycontrol party stop` | 0 | Close your own listening party |
| `/spotifycontrol party join <host>` | 0 | Join another player's listening party |
| `/spotifycontrol party leave` | 0 | Leave the party you are in |
| `/spotifycontrol party invite <player>` | 0 | Send an invite line to a player |

## on

Turns the mod back on after `/spotifycontrol off`. Writes `enabled: true` to `server.json` and sends
the updated settings to every connected client, so the party buttons in their overlay become active
again immediately.

```
/spotifycontrol on
```

## off

Turns the mod off globally. All open parties are closed, all voice chat channels are dropped, and
clients stop being able to start new parties. Playback control in each player's own overlay keeps
working, because that runs client to Spotify and never touches the server.

```
/spotifycontrol off
```

## status

Prints the current state. Safe for every player.

```
/spotifycontrol status
```

Example output:

```
[SpotifyControl] State: enabled, parties: 2, listeners: 7, voice chat: yes
```

## reload

Re-reads `config/spotifycontrol/server.json` from disk, reloads the language file and pushes the new
settings to all clients. Running parties are not interrupted, but changes to `broadcastRangeBlocks`
only take effect for channels opened after the reload.

```
/spotifycontrol reload
```

## party start

Opens a listening party with you as the host. Without an argument the party uses sync mode.

```
/spotifycontrol party start
/spotifycontrol party start sync
/spotifycontrol party start broadcast
```

Broadcast mode falls back to sync when the server has `allowAudioBroadcast: false` or Simple Voice
Chat is not installed. You are told in chat when that happens.

## party stop

Closes the party you host. Every member is notified and their overlay returns to the solo state.

```
/spotifycontrol party stop
```

## party join

Joins the party hosted by another online player.

```
/spotifycontrol party join Steve
```

The command argument is a player selector, so tab completion lists online players. You cannot join
while already in a party, leave first.

## party leave

Leaves the party you are a member of. If you are the host, this behaves like `party stop`.

```
/spotifycontrol party leave
```

## party invite

Sends the target player a chat line with the join command filled in. It does not add them to the
party by itself.

```
/spotifycontrol party invite Alex
```

The same action is available by clicking a player name in the Listening party tab of the overlay.
