# Permissions

SpotifyControl uses the vanilla permission levels. There are no permission node strings unless you
add a permissions mod.

| Permission level | Who has it by default | Commands |
|---|---|---|
| 0 | Every player | `/spotifycontrol status`, all `party` subcommands |
| 2 | Operators | `/spotifycontrol on`, `off`, `reload` |

Levels 1, 3 and 4 are not used separately. Anything above 2 also passes the level 2 check.

## Granting operator level

On a dedicated server:

```
/op Steve
```

Or set the level directly in `ops.json`:

```json
[
  {
    "uuid": "00000000-0000-0000-0000-000000000000",
    "name": "Steve",
    "level": 2,
    "bypassesPlayerLimit": false
  }
]
```

`op-permission-level` in `server.properties` decides what `/op` grants. The default is 4, which
covers everything SpotifyControl checks.

## Fabric Permissions API and LuckPerms

For per-command control without handing out operator status, install
[LuckPerms Fabric](https://luckperms.net/download) together with the
[Fabric Permissions API](https://github.com/lucko/fabric-permissions-api). LuckPerms Fabric maps
permission nodes onto the vanilla levels, so granting the vanilla command node is enough:

```
/lp group moderator permission set minecraft.command.spotifycontrol true
```

To let a group run the operator subcommands without full operator rights, give them permission level
2 through a LuckPerms meta assignment:

```
/lp group moderator meta set permission-level 2
```

## What is not permission gated

Everything that happens client side needs no permission at all, because it never reaches the server:

- Opening the overlay
- Connecting a Spotify account
- Searching, browsing, playlist management
- Play, pause, skip, seek, volume and device transfer

The server only sees party actions, the now-playing metadata a host reports, and the audio frames a
broadcast host sends. Disabling the mod with `/spotifycontrol off` blocks all three.
