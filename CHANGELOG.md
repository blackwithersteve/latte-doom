# Changelog

## [0.35.0-alpha] - 2026-07-25

### Added

- Boom sky transfer (linedef specials 271 and 272), with mirrored skies and sidedef column
  offsets.

### Fixed

- Monsters blocked movement ahead of their own sprites. Collision now reads the same
  interpolated positions the renderer draws.
- Patch WAD animations alternated with base game frames. Sequence ranges now resolve to the
  last definition of each name.

### Known gaps

- Vertical sky offsets and per-opening sky selection are not implemented.
- UMAPINFO is not supported.

## [0.30.0-alpha] - 2026-07-25

First public release.

### Added

- Levels rendered as walkable 3D geometry: watertight sector floors and ceilings, engine
  texture pegging, animated flats and walls, switch textures, sky.
- All simulation in the vendored engine: monsters, projectiles, damage, pickups, keys,
  switches, doors, lifts, crushers, teleporters, exits.
- Interpolation between engine tics, with collision on the same timeline as the geometry.
- Transformed player: engine physics, weapons, view weapon, status bar, view bob, death and
  level restart.
- Untransformed player: Minecraft movement clipped by the level.
- Interface drawn from the WAD: menu, intermission, finale, status bar, automap, sound
  volume.
- Boom and MBF support: generalised and extended line types, friction, pushers, scrollers,
  elevators, `ANIMATED`, `SWITCHES`, DEHACKED/BEX.
- Multiplayer: one client runs the engine, the others render from its snapshot feed.
- Crossover: Minecraft weapons damage monsters, DOOM weapons damage entities, item
  conversion, block placement.
- Diagnostics: motion ring buffer written on anomalies or on demand with `/doomdiag`.

### Notes

- Requires a DOOM or DOOM II WAD supplied by the user. No game data is included.
- Savegames are not implemented.
- Format gaps are listed in `docs/COMPATIBILITY.md`.
