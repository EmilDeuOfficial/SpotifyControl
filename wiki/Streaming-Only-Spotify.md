# Streaming only Spotify

Broadcast mode captures the sound your machine is playing. On Windows it does that by itself, with
nothing to install, but it captures the whole mix: Minecraft, Discord, browser tabs and notification
sounds all go out to the party along with the music.

This page sets up the other way, where the party hears Spotify and nothing else. It takes about two
minutes and you only do it once.

## How it works

A virtual audio cable is a fake sound card. Anything sent into its input comes back out of its
output, and nothing else does. Windows can send a single application to a chosen output, so Spotify
goes into the cable while every other program keeps playing on your speakers.

The mod then captures the cable instead of your speakers, and it captures the same end Spotify plays
into. Its device list on Windows is the outputs of the machine, so the cable's input is simply one
more entry in it and there is nothing to route back before the mod can read it.

```
Spotify      ->  CABLE Input  ->  SpotifyControl  ->  the party
Minecraft    ->  your speakers
Discord      ->  your speakers
```

## Before you start

| | |
|---|---|
| Operating system | Windows |
| Simple Voice Chat | Installed on the client and the server |
| Spotify | The desktop app, playing at least once so Windows knows it makes sound |

The listeners need nothing. Broadcast mode does not require Premium for anyone, host included.

## Step 1: install the cable

Download and install [VB-Audio Virtual Cable](https://vb-audio.com/Cable/) and restart the machine
when it asks. Two new devices appear in the Windows sound settings afterwards: CABLE Input, which is
an output, and CABLE Output, which is an input. The names read backwards until you think of the
cable itself: you plug into its input, you listen at its output. CABLE Input is the one that matters
here, for Spotify and for the mod alike.

## Step 2: send Spotify into the cable

Start Spotify and play something, otherwise Windows will not list it yet.

1. Right click the speaker icon in the tray and open Sound settings
2. Scroll to Volume mixer on Windows 11, or App volume and device preferences on Windows 10
3. Find Spotify in the list of applications
4. Set its Output device to CABLE Input

Spotify goes quiet on your speakers at this point. That is the cable working, and Step 4 gives you
the sound back.

## Step 3: point the mod at the cable

1. Press Ctrl + M to open the app
2. Go to the Listening party tab
3. In the Audio source row, click until it reads CABLE Input

That is the same device you just handed to Spotify, and it is meant to be: the mod records outputs,
so the source you pick is the output the music plays on. The row on Windows holds your speakers,
your headset and the input side of every virtual cable, and no microphone at all, so there is
nothing in it that can send your room to the party. The same choice lives in Settings under Audio
capture device if you prefer to set it there.

The stream is now correct. The next step is for your own ears only.

## Step 4: hear it yourself again

Spotify no longer reaches your speakers, and this is how you get it back. It is not part of getting
the stream working: the party hears the music whether or not you do this.

1. In Sound settings open More sound settings on Windows 11, or Sound Control Panel on Windows 10
2. Go to the Recording tab
3. Double click CABLE Output
4. On the Listen tab, tick Listen to this device
5. Under Playback through this device pick your speakers or headset
6. Apply

Spotify is audible again, coming back out of the far end of the cable. Leave this on; it is what
lets you hear your own music while the party hears it too.

## Step 5: start the party

```
/spotifycontrol party start broadcast
```

Or pick Broadcast mode in the Listening party tab and click Start party, then Start streaming.

## Checking it worked

When the stream starts, the mod says "Broadcasting the sound being played, from" and then the device
it opened. That name is the whole check:

| The device it names | What it means |
|---|---|
| CABLE Input | Correct, the party hears Spotify and nothing else |
| SpotifyControl System | The automatic capture, so the party hears the whole mix |
| Your speakers or headset | The wrong output, and again the whole mix |

Ask a listener what they hear while a Minecraft sound plays. If the game is in the stream too, the
source is the whole mix rather than the cable, so Step 3 has not taken. If they hear nothing, the
music is not in the cable, so Step 2 has not taken.

## If something is wrong

**Spotify is not in the Volume mixer.** It has not made a sound yet this session. Play a track and
look again.

**CABLE Input is missing from the mod.** The install still needs its restart, or the device is
disabled. In Sound settings open More sound settings, go to the Playback tab, right click the empty
area, enable Show Disabled Devices, then enable it. Minecraft does not need restarting for this: the
mod reads the device list from the sound system every time, so a cable installed while the game is
running is in the row straight away.

**You cannot hear your own music.** Step 4 was skipped, or Listen to this device lost its playback
device after a reboot. The party is unaffected either way.

**Listeners hear an echo of themselves.** The mod is capturing a device that the voice chat also
plays into. Capture CABLE Input, which nothing but Spotify plays into, and send Listen to this
device to your speakers or headset, never back into the cable.

**Listeners hear nothing at all.** Spotify is playing on your speakers again. Windows resets a per
application output when the device list changes, so redo Step 2.

**The stream is quiet.** Raise Capture volume in Settings. It goes above 100 percent.

## Other ways to do the same thing

VoiceMeeter, from the same author, does this and a mixing desk besides. Some sound cards ship a
virtual mixer of their own, such as ASUS Sonic Studio, which works the same way. Any of them is
fine: what matters is that Spotify plays into an output that only Spotify plays into, because that
output is what the mod records.

Stereo Mix is not one of these. It is an input line and the mod's list on Windows is outputs, so it
no longer appears there at all, and nothing is lost by that: it carried everything the speakers
play, which is what the automatic capture already does with nothing to install or enable.

## Undoing it

Set Spotify's output back to your speakers in the Volume mixer, untick Listen to this device on
CABLE Output, and set the Audio source row back to SpotifyControl System. The cable can stay
installed; nothing uses it until you route something into it again.

## See also

- [Listening Parties](Listening-Parties) - sync mode, broadcast mode, range and bandwidth
- [Configuration](Configuration) - `captureDeviceName`, `captureVolumePercent` and the server side
  broadcast settings
