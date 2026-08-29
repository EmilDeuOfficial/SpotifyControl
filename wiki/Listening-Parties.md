# Listening Parties

A listening party lets other players hear what the host is listening to. There are two modes with
very different mechanics, and the right one depends on whether the other players have Spotify.

| | Sync mode | Broadcast mode |
|---|---|---|
| Every listener needs Spotify Premium | Yes | No |
| Simple Voice Chat required | No | Yes |
| Audio quality | Full Spotify quality per listener | Opus voice chat quality |
| Bandwidth on the Minecraft server | A few bytes per track change | About 12 kB/s per listener |
| Host needs a loopback device | No | Only on Linux and macOS |
| Works across dimensions | Yes | Configurable |

## Sync mode

The host's client reports the current track URI, position and play state to the server whenever the
track changes and at least every five seconds. The server forwards it to every party member. Each
member's client compares it against its own playback state and, when the track differs or the
position has drifted more than 2.5 seconds, calls the Spotify API to play the same track at the same
position on that player's own account.

No audio passes through the Minecraft server, so this costs almost nothing in bandwidth and sounds
exactly as good as Spotify normally does. The price is that every listener needs their own Premium
account and an active Spotify device.

Starting a sync party:

```
/spotifycontrol party start sync
```

Or open the overlay with Ctrl + M, go to Listening party, pick Sync mode and click Start party.

## Broadcast mode

The host's client opens the audio source, reads 20 ms frames of 48 kHz stereo audio, downmixes them
to mono, encodes each frame with the Opus encoder provided by Simple Voice Chat, and sends the
frames to the server. The server pushes them straight into a Simple Voice Chat audio channel, so
listeners hear it through the voice chat mixer with their normal voice chat volume.

Because this captures the operating system's audio output, it works for anything the host is
playing, Spotify Free included. It also captures everything else: Minecraft itself, notification
sounds, Discord, browser tabs. Mute what you do not want to send before you start streaming.

Starting a broadcast party:

```
/spotifycontrol party start broadcast
```

On Windows the audio source is already set to SpotifyControl System, so Start streaming in the
Listening party tab is all that is left. On Linux and macOS pick a loopback source in that row
first.

## Where the broadcast audio comes from

### Windows

Nothing to install and nothing to enable. The mod asks the sound system for the mix that is going to
the speakers and captures it there instead of through an input line, which is why neither Stereo Mix
nor a virtual cable is needed any more. This is the entry SpotifyControl System in the Audio source
row and in the Audio capture device row, and it is what the mod uses until you pick something else.

The price is that this is the whole mix. Everything the speakers play goes out to the party:
Minecraft's own sound, Discord, browser tabs, notification sounds. Mute what you do not want to send
before you start streaming.

### Windows, Spotify and nothing else

This is what the manual device choice is still there for. A virtual audio cable carries only what
you route into it, so sending Spotify alone into one and capturing that same device leaves every
other application on your speakers and out of the stream. VoiceMeeter and the virtual mixers some
sound cards ship work the same way.

[Streaming only Spotify](Streaming-Only-Spotify) walks through it step by step, including hearing
your own music while the cable carries it and how to tell from inside the game which of the two
sources is live.

Stereo Mix is the older form of what the automatic capture now does for you. It is an input line,
and the Windows list is outputs, so it no longer turns up in the mod at all. Nothing is lost with
it: it carried everything the speakers play, which is exactly what the automatic capture carries,
without the trip through the sound control panel that enabling it used to need.

### macOS

The automatic capture is Windows only, so here broadcast mode still needs a loopback device, an
input line that carries what the machine is playing back instead of what a microphone hears. Install
[BlackHole](https://existential.audio/blackhole/), create a Multi-Output Device in Audio MIDI
Setup that contains both your speakers and BlackHole 2ch, set it as the system output, then pick
BlackHole 2ch in the mod.

### Linux

A loopback device as well, and one is already there: with PipeWire or PulseAudio, the monitor source
of your output sink works directly, with nothing to install. It usually shows up as an entry
containing "Monitor of", and it carries the whole sink, the game included.

### Not a microphone

On Windows the question no longer arises. The device list there is what the machine plays sound out
of, so every entry carries audio that is being played and there is no microphone among them to pick
by mistake.

On Linux and macOS the list is the input side of the sound system, where every line that is not a
loopback is a microphone. Pick one of those and the party hears your room instead of the music.
Muting yourself in Simple Voice Chat does not change that: the mod captures straight from the sound
system and never reads the voice chat microphone, so the mute button has nothing to mute.

### What the device list holds

The Audio source row in the Listening party tab and the Audio capture device row in the settings
start on Windows with SpotifyControl System, followed by the outputs the machine has: speakers,
headsets and the input side of any virtual cable. That list comes from the sound system's own record
of what plays audio, not from reading device names, so there is nothing to mark and nothing to
guess. It is also read fresh every time, so a virtual cable installed while the game is running is
in the row without a restart.

On Linux and macOS the list is the input side of the sound system, loopback candidates first and
everything else after them, and each entry is marked as one that looks like an output or one that
looks like a microphone. The name is all those sound systems reveal about a device, so the mark is a
guess and never a block: a loopback that somebody renamed reads as a microphone here, and you can
still select it. Starting a stream on a line that does not look like a loopback warns you that a
microphone is going out to the party, and the mod says it is broadcasting a microphone rather than
claiming to broadcast desktop audio.

## Range and who can hear

`broadcastRangeBlocks` in `server.json` decides the delivery method.

With `broadcastRangeBlocks: 0` the server opens a static Simple Voice Chat channel per party member.
Only members hear the stream, and they hear it at full volume no matter where they are. Set
`requireSameDimension` to false to keep it running across dimensions.

With `broadcastRangeBlocks: 48` the server opens a single locational channel at the host's position
with a 48 block radius, updated every four ticks. Everyone in range hears the music with distance
falloff, whether or not they joined the party. This is the version for a jukebox in a town square.

## Joining and inviting

Any player can join an open party:

```
/spotifycontrol party join Steve
```

The host can send an invite that fills the command in for the other player:

```
/spotifycontrol party invite Alex
```

In the overlay, the Listening party tab lists every online player. Clicking a name joins their party
when you are not in one, or invites them when you are the host.

## Ending a party

The host uses `/spotifycontrol party stop` or the Stop party button. Members use
`/spotifycontrol party leave`. A party also ends when the host disconnects, and a broadcast party
closes after `partyIdleTimeoutSeconds` without audio.
