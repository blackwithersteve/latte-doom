# Changelog

## [0.50.0-alpha] - 2026-07-31

### Added

- Savegames: six slots in the menu, kept separately per WAD combination.
- Mouse support in the menus.
- A WAD selection page in the menu.
- Standalone `.deh` and `.bex` file support.
- The sky renders as real geometry.
- An Options page: screen size, light boost, sound volume, and a Crispness page
  ported from Crispy Doom.
- Gamma correction on F10 and `/doomgamma`.
- Three status bar sizes.
- Sound and music volume sliders.
- Level stats, messages and pickup lines in the WAD's small font.
- The automap fills the screen.
- An experimental BSP-accurate mesh behind `/bsp`.
- Headless verification tasks for saves, bosses, DEHACKED and the mesh.

### Changed

- Entering a level transforms the player; leaving reverts it. `/doommarine` toggles
  the form inside a level only.
- Menus pause the game in singleplayer.
- Loading a WAD set closes the menu.
- Weapon sprites sit at the original heights, with a smoother bob.
- Free look can be turned off.

### Fixed

- Engine sounds played far too quiet.
- Intermission sounds played twice.
- Saving could crash the engine.
- Entering a level flashed damage that was never taken.
- DEHACKED weapon patches could crash weapon raising.
- The menu sliders showed seams in windowed mode.
- A rebooted engine could keep playing the previous level's audio.
- DEHACKED patches persisted into the next game loaded.
- Music volume topped out too low.

## [0.42.0-alpha] - 2026-07-26

### Fixed

- Heretic, Hexen and Strife WADs were treated as playable game data. They are structurally
  valid WADs with a palette and maps, so every test for a base WAD passed and the engine
  only failed later on lumps that are not there. Both load commands now recognise them and
  name the game.

## [0.41.0-alpha] - 2026-07-26

### Fixed

- `/warp` reported the wrong map naming scheme. It read the scheme from a level that
  happened to be raised, so before any level had loaded it defaulted to the DOOM 1 layout
  and asked for `E1M1` even with a DOOM II WAD loaded. It now reads the configured WAD, and
  a bare warp number such as `/warp 01` is accepted for either game.

## [0.40.0-alpha] - 2026-07-26

### Added

- Commands to grant engine inventory and type cheat codes: `/lgive` for weapons, ammo, keys
  and armour, and `/doomcheat` for any code the engine recognises.
- `/doomleave` returns to the overworld. A guest leaves; the level's owner also takes it down.
- `/lattedoom` lists the common commands, `/lattedoom advanced` the rest.
- Speed effects apply to a transformed player.
- Shields block engine damage when raised towards the attack.
- Minecraft attacks alert monsters and trigger shootable line specials.
- DOOM weapons picked up by an untransformed player convert to Minecraft rewards by tier,
  through a new `loot` action in `pickups.properties`.
- `melee-scale` and `sound-driver` settings.

### Fixed

- Untransformed players were clipped at the marine's size and caught on ceilings and steps.
  They now use their own height and width.
- Minecraft attacks aimed at engine positions rather than drawn ones and missed moving
  targets entirely.
- The arch-vile's throw never reached the player, and monster projectiles could be aimed at
  a floor height the player did not occupy.
- A Nightmare game left later games with fast monsters until the process exited.
- The menu offered episode selection for WADs that have no episodes.
- Engine audio continued after leaving a world, and could fall a second behind after a
  level change.
- Blocks placed inside a level fell out of the world when broken, and survived a map change.
- The status bar could be absent while the vanilla interface was already hidden.

### Security

- The snapshot decoder sized allocations from unvalidated counts, so a small packet could
  exhaust server memory before any authorisation ran.
- Level ownership, which authorises engine damage, could be claimed without validation, and
  the damage it authorised had no participant check or rate limit.
- Self-teleport, dimension entry and exit, scavenging and engine hits are bounded and
  rate-limited.

### Changed

- The WAD commands explain what a file is when they refuse it, accept quoted paths and names
  containing spaces, and require a base WAD to contain levels.
- Rejoining re-reads the WAD folder, so adding game data and rejoining now works as the
  join message says.

## [0.37.0-alpha] - 2026-07-25

### Fixed

- Monsters aimed projectiles above or below the player. The mirrored player object kept the
  floor height from wherever the engine last moved it, and the vertical clamp applied that
  height every tic. Floor and ceiling are now refreshed whenever the mirror repositions a
  player, local or remote.

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
