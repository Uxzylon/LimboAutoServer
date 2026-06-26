# LimboAutoServer

A [Velocity](https://papermc.io/software/velocity) plugin that **lazy-starts backend
servers** when a player connects and keeps them running only while players are present —
the same idea as [AutoServer](https://github.com/Artificial-720/AutoServer), but built on
top of [LimboAPI](https://github.com/Elytrium/LimboAPI).

## Why

AutoServer denies the initial connection while the backend boots, which leaves the client
stuck in the login phase. A cold Fabric server can take a while to start, and clients often
hit a **login timeout** before it is ready.

LimboAutoServer fixes this by dropping the player into a **LimboAPI virtual world** (a tiny
"waiting room" served directly by the proxy) while the backend starts. The player is fully
connected — no timeout — sees a status message, and is **seamlessly transferred** to the
real server as soon as it is responsive.

## How it works

1. During login, LimboAPI fires `LoginLimboRegisterEvent`.
2. LimboAutoServer resolves the backend the player is heading to (forced host, else the
   first entry of Velocity's `try` list) and pings it.
3. If it's **online**, the player connects normally.
4. If it's **offline**, the player is spawned into the limbo world and the backend's
   `start` command is run. Concurrent joiners share a single startup and are released
   together once the server responds.
5. When the last player leaves a backend, it is stopped again after `autoShutdownDelay`.

## Requirements

- Velocity 3.4.x
- [LimboAPI](https://modrinth.com/plugin/limboapi) installed on the same proxy (declared as
  a plugin dependency, so it loads first).

## Build

```bash
./gradlew build
```

The plugin jar is produced at `build/libs/limboautoserver-velocity-<version>.jar`.
`toml4j` and the Velocity/Adventure APIs are provided by Velocity at runtime, and the
LimboAPI classes are provided by the LimboAPI plugin, so the jar stays small (no shading).

## Configuration

On first run `config.toml` is written to `plugins/limboautoserver/`. Each backend is a
`[servers.<name>]` block whose name matches the server in `velocity.toml`. See the bundled
[config.toml](src/main/resources/config.toml) for the full set of options (start/stop
commands, working directory, startup/shutdown/idle delays) and the `[limbo]` section
(dimension, gamemode, spawn position, world time) and `[messages]`.

## Commands

`/limboautoserver` (alias `/las`):

| Subcommand | Permission | Description |
| --- | --- | --- |
| `reload` | `limboautoserver.command.reload` | Reload config and rebuild the limbo world |
| `status [server]` | `limboautoserver.command.status` | Show cached server status |
| `start <server>` | `limboautoserver.command.start` | Run the start sequence |
| `stop <server>` | `limboautoserver.command.stop` | Run the stop sequence |

## Credits

Derived from [AutoServer](https://github.com/Artificial-720/AutoServer) by Artificial-720
(MIT). Built on [LimboAPI](https://github.com/Elytrium/LimboAPI) by Elytrium (MIT).
Licensed under the MIT License — see [LICENSE](LICENSE).
