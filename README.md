# Latte Doom

**A DOOM source port for Minecraft.**

Latte Doom runs a real DOOM engine inside Minecraft's JVM and builds the level it's running
as walkable 3D geometry around you. The engine is a vendored copy of
[Mocha Doom](https://github.com/AXDOOMER/mochadoom), which descends from id Software's GPL
source release.

The engine owns everything that matters: collision, monster AI, line specials, the RNG, the
35 Hz tic. Minecraft supplies rendering, input, audio and the world the level stands in.
None of DOOM's behaviour is reimplemented, so a WAD that behaves a certain way in another
port behaves the same way here.

> **You need your own DOOM or DOOM II WAD.** No game data is included. See [LEGAL.md](LEGAL.md).


## Features

**Levels as real geometry.** Sectors, walls, moving floors, doors, lifts and crushers are
built from the WAD and updated from the engine every tic, interpolated so they stay smooth
at any frame rate. The sky is geometry too.

**The engine's own simulation.** Monsters, projectiles, hitscans, damage, pickups, keys,
switches, teleporters and level exits all run inside the engine and get mirrored into the
world. The player is mirrored back the other way, so monsters see, chase and shoot the real
player.

**Two ways to play.** Starting a game spawns you in as the marine. `/doommarine`
switches between the marine and a normal Minecraft player.

**Savegames.** Six slots, kept per WAD combination.

**Interface drawn from the WAD.** Menus, status bar and automap, drawn from the WAD's own
art, with mouse support. The menu has a WADs page and options.

**Boom and MBF maps.** Generalised line types, scrollers, friction, pushers, silent
teleporters, elevators, animated texture and switch definitions, DEHACKED/BEX patches at MBF
table sizes. [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) has the exact scope and the
known gaps.

**Multiplayer.** One client runs the engine, the others render the same level and see the
same monsters. Only state crosses the network; every client renders from its own copy of the
game data.

**Crossover mechanics.** Minecraft weapons and arrows hurt monsters, DOOM weapons hurt
Minecraft mobs, DOOM items convert into Minecraft resources through a configurable table,
and you can place blocks inside levels.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Mod loader | Fabric, with Fabric API |
| Java | 25 or newer |
| Game data | Your own `DOOM.WAD` or `DOOM2.WAD` |

## Installing

1. Install Fabric for Minecraft 26.2 and add Fabric API.
2. Put the Latte Doom jar in your `mods` folder.
3. Start the game once, then drop your IWAD into `config/latte-doom/`. Patch WADs and
   DEHACKED files go in `config/latte-doom/pwads/` (either folder works).
4. Press **M** in game for the DOOM menu, then New Game.

With no WAD present the mod stays idle and tells you what's missing. It never downloads or
bundles game data.

## Commands

| Command | Effect |
|---|---|
| `/load [file]` | Load a game WAD, patch WAD or `.deh` file; no argument lists the folder. |
| `/pwad <wads…>` or `/pwad none` | Pick the patch WADs to load on top, in order. |
| `/warp <map>` | Load a map directly (`e1m1`, `map07`), optionally `nomonsters`. |
| `/doommarine` | Switch between the marine and the plain player, inside a level. |
| `/doomgamma` | Cycle gamma correction (also on F10). |
| `/doomleave` | Return to the overworld. |
| `/doomvolume [sfx\|music] <0-100>` | Show or set the mod's audio levels. No arguments opens the volume menu. |
| `/doomwatch` | Freeze or resume the engine while staying in the world. |
| `/doomdemo` | Play back the WAD's recorded demos. |
| `/doomdiag` | Write the diagnostic log described below. |
| `/doomscreen` | Show the engine's own framebuffer. Diagnostic only. |

Arguments complete with Tab, including WAD and map names from the loaded set.

## Keys

| Key | Action |
|---|---|
| **M** | Open or close the DOOM menu |
| **R** | Use (doors, switches) |
| **Tab** | Toggle the automap. Keypad `+` and `-` zoom |
| **F10** | Cycle gamma correction |
| `-` and `=` | Smaller or larger interface, while playing |
| **Shift** | Run, while transformed. Caps Lock toggles always-run |
| Mouse 1 | Fire, while transformed |
| 1-7 | Select weapon, while transformed |

All of these can be rebound under Options, Controls, Key Binds, in the "Latte Doom"
category.

## Configuration

`config/latte-doom/latte-doom.properties`

| Key | Default | Meaning |
|---|---|---|
| `iwad` | *(auto-detected)* | Path to the base WAD. |
| `pwads` | *(empty)* | Patch WADs, comma separated. The menu's WADS page manages this. |
| `dehs` | *(empty)* | DEHACKED files, comma separated. Also managed from the menu. |
| `doom-skill` | `3` | Skill level, 1 to 5. |
| `doom-sfx-volume` | `1.0` | Sound-effect level, 0 to 1, separate from Minecraft's sliders. |
| `doom-music-volume` | `1.0` | Music level, 0 to 1. |
| `novert` | `true` | Mouse look doesn't move you forward or back. |
| `pause-minecraft` | `true` | Pause Minecraft while a fullscreen DOOM screen is open. |
| `place-blocks` | `true` | Allow placing blocks against a level's geometry. |

Most options are set from the in-game menu and persist here. Savegames live under
`config/latte-doom/saves/`.

`config/latte-doom/pickups.properties` maps DOOM items to what an untransformed player gets
for picking them up. It's generated with documented defaults on first run. Entries are keyed
by the item's four-letter sprite name and support `heal`, `food`, `give <item> <count>` and
`none`.

## Diagnostics

Motion problems here involve three clocks (Minecraft's tick, the engine's tic, the frame
rate), so the mod keeps a rolling recorder of player and sector motion. It writes
`logs/lattedoom-diag.log` when it spots an anomaly, and `/doomdiag` dumps it on demand.
Attaching that file to a bug report is usually enough to find the cause.

## Building

```
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

[CONTRIBUTING.md](CONTRIBUTING.md) covers the verification tasks, which need a WAD you
supply. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) covers how the engine, the renderer and
the movement code fit together.

## Licence and credits

Latte Doom is licensed under the **GNU General Public License, version 3 or later**. See
[LICENSE](LICENSE). The licence is inherited from the vendored engine, which descends from
id Software's DOOM source release.

- **id Software**, for DOOM and the source release that makes all of this possible
- **velktron / Maes**, **Good Sign**, **AXDOOMER** and contributors, for Mocha Doom, the
  Java engine vendored here
- [mapbox/earcut](https://github.com/mapbox/earcut), the polygon triangulation algorithm,
  ported here for sector geometry, ISC licence

DOOM and DOOM II are trademarks of id Software LLC. This project isn't affiliated with or
endorsed by id Software or Mojang.
