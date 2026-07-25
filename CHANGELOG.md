# Changelog

## [0.30.0-alpha] - 2026-07-25

First public release.

### Added

- Level geometry. The running map is built as walkable 3D geometry: sector floors and
  ceilings triangulated watertight against the wall bases, walls using the engine's
  texture pegging rules, animated flats and walls, switch textures, and the map's own sky
  texture drawn behind sky openings.
- Simulation. Monsters, projectiles, damage, pickups, keys, switches, doors, lifts,
  crushers, teleporters and level exits all run in the vendored engine. The world mirrors
  its state once per tic and never simulates anything itself.
- Interpolation. A drift-corrected clock interpolates between engine tics at the display
  frame rate. Collision runs on the same delayed timeline as the drawn geometry, so a
  player riding a moving floor does not separate from it.
- Transformed player. Engine physics, weapons, view weapon, status bar, view bob and death
  sequence, with the level restarting on death as in single-player DOOM.
- Untransformed player. Minecraft movement, clipped by the level's walls, floors, steps and
  moving sectors.
- Interface. Menu with episode and skill selection, intermission tally, episode finale,
  status bar, automap and sound-volume screen, all drawn as Minecraft screens from the
  WAD's own graphics.
- Boom and MBF formats. Generalised line types, extended fixed types, silent teleporters,
  elevators, toggle platforms, scrollers and conveyors, friction floors, pushers,
  generalised sector types, `ANIMATED` and `SWITCHES` lumps, and DEHACKED/BEX patches at
  MBF table sizes. Details in `docs/COMPATIBILITY.md`.
- Multiplayer. One client runs the engine and the others render the same level from their
  own WAD, driven by its snapshot feed. Only state crosses the network.
- Crossover mechanics. Minecraft melee and arrows damage monsters, DOOM weapons damage
  Minecraft entities, DOOM items convert to Minecraft resources through a configurable
  table, and blocks can be placed against a level's geometry.
- Diagnostics. A ring buffer of player and sector motion, written to
  `logs/lattedoom-diag.log` on an anomaly or on demand with `/doomdiag`.

### Notes

- A DOOM or DOOM II WAD supplied by the user is required. No game data is included.
- Savegames are not implemented. The menu entries are present but inert.
- Known format gaps are listed in `docs/COMPATIBILITY.md`.
