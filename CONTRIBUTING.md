# Contributing

## Building

Latte Doom targets Minecraft 26.2 with Fabric and requires **JDK 25**.

```
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

The jar is written to `build/libs/`. To run a development client:

```
./gradlew runClient
```

A second instance, for testing multiplayer locally, is available as `runClient2`.

## Game data

No game data ships with this repository, and none may be added; see [LEGAL.md](LEGAL.md).
Place your own IWAD in the run directory's `config/latte-doom/` folder, and any patch WADs
in `config/latte-doom/pwads/`.

The verification tasks take a WAD path from the caller rather than assuming one:

```
./gradlew doomSmoke   -Pwad=/path/to/DOOM.WAD
./gradlew triProbe    -Pwad=/path/to/DOOM.WAD [-Pmap=E1M1]
./gradlew moveProbe   -Pwad=/path/to/DOOM.WAD [-Pmap=E1M1]
./gradlew arenaProbe  -Pwad=/path/to/DOOM.WAD
./gradlew dehProbe    -PdehFile=/path/to/patch.deh
```

`doomSmoke` and `arenaProbe` also accept the WAD through the `DOOM_WAD` environment
variable.

## Verification

| Task | What it proves |
|---|---|
| `doomSmoke` | The engine boots headlessly, produces frames, accepts input, freezes and resumes, and publishes usable snapshots. Prints `SMOKE OK` on success. |
| `triProbe` | Every map in a WAD triangulates watertight, so no gaps can appear between floors and walls. |
| `moveProbe` | The collision port keeps a player inside real sectors from every start, in every direction, on every map. |
| `arenaProbe` | The engine runs monster AI on a generated map: a monster spawns, thinks, pursues a moving target and attacks. |
| `dehProbe` | A DEHACKED or BEX patch parses and applies against the real engine tables. |

Please run `./gradlew build` and `doomSmoke` before opening a pull request. If your change
touches geometry or movement, run `triProbe` and `moveProbe` as well.

## Code layout

```
src/main/java/com/blackwithersteve/lattedoom/
  engine/    engine ownership and the state snapshot
  render/    level geometry, textures, sprites, HUD, automap
  play/      movement, collision and the two combat directions
  net/       payloads and handlers for both sides
  mixin/     hooks into Minecraft's client and server
  harness/   headless verification entry points
  diag/      the motion flight recorder
src/mochadoom/   the vendored engine (GPL)
```

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how these fit together and defines
the terms used throughout the source.

## Working on the vendored engine

`src/mochadoom` is a vendored copy of Mocha Doom. Patch it as little as possible, and mark
every modification with the single grep-able form used throughout:

```java
// Latte Doom patch: <what>. <why, in one sentence.>
```

so that `grep -rn "Latte Doom patch:" src/mochadoom` lists all of them. Never alter or
remove an upstream copyright header.

## Style

- Java 25, four-space indentation, lines up to 100 characters. `.editorconfig` carries the
  details, and line endings are LF throughout.
- Comments explain **why** a constraint exists, particularly where behaviour must match the
  engine's exactly or where two clocks have to agree. Prefer one clear sentence over a note
  that only makes sense to whoever wrote it.
- Public classes and non-obvious public methods carry javadoc.
- Where a behaviour comes from the engine, name the engine function it follows, for example
  `P_TryMove` or `R_DrawPSprite`, so it can be compared against the original.

## Reporting bugs

Include the WAD and map, the Minecraft and mod versions, and what you expected. For motion,
position or timing problems attach `logs/lattedoom-diag.log`: the mod records those
automatically, and `/doomdiag` writes the buffer on demand.
