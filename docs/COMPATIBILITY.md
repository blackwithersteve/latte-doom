# Map format compatibility

What Latte Doom supports and what it doesn't. The gaps are listed explicitly: a map using
an unsupported feature usually still plays, but that feature will be missing or inert.

## Summary

| Format | Status |
|---|---|
| Vanilla DOOM / DOOM II | Supported |
| Boom | Supported, with the visual gaps listed below |
| MBF | Supported except for the extended code pointers |
| MBF21 | Not supported |

Support here means **feature support**: maps play as intended. Frame-accurate demo
compatibility is a stricter property and is not claimed for any format.

## Vanilla

All standard line and sector types, switches, doors, lifts, crushers, stairs, teleporters,
damaging floors, secrets, level exits, keys and the full item and monster set, since these
are simulated by the vendored engine itself. Animated flats and walls, switch pairs, sky
selection per episode and per map, and DEHACKED patches are all handled.

## Boom

Supported:

- **Generalised line types**, the full 0x2F80 and above range: floors, ceilings, doors,
  locked doors, lifts, stairs and crushers, with their trigger, speed, model and change
  parameters.
- **Extended fixed types 142 to 269**, covering the additional door, floor, ceiling, lift,
  stair, crusher, light and exit variants.
- **Silent teleporters**, including the line-to-line and reversed variants.
- **Elevators** (types 227 to 238) and **toggle platforms** (211 and 212).
- **Scrolling** walls, floors and ceilings, and **carrying conveyors**, including the
  conveyor behaviour that scripted maps rely on to move objects across trigger lines.
- **Friction floors** and **wind, current and point pushers**, applied both to the engine's
  own objects and to players on the Minecraft side.
- **Generalised sector types**: the damage and secret bit fields.
- **Pass-through use lines.**
- **`ANIMATED` and `SWITCHES` lumps**, so a WAD's own animation and switch definitions
  replace the built-in tables, with per-animation speeds honoured.
- **Deep BSP and extended node formats** as provided by the engine.

Not yet implemented:

| Feature | Types | Effect on a map |
|---|---|---|
| Deep water / fake floors | 242 | The sector renders as an ordinary sector; no underwater view. |
| Translucent mid-textures | 260, `TRANMAP` | Affected textures draw opaque. |
| Light transfers | 213, 261 | Sector light stays as authored. |
| Scrolling flat visuals | (none) | Objects are carried correctly, but the floor texture does not appear to move. |

## MBF

Supported:

- **DEHACKED and BEX patches at MBF table sizes**: extended states, things and sprites
  beyond the vanilla enumerations, including patched weapons, frames, sounds, ammunition,
  strings and par times. This is what allows total conversions with custom monsters and
  weapons to run.
- **Sky handling** for episodes beyond the base game's own count, including a patch WAD's
  own music and sky lumps.

Not yet implemented:

| Feature | Effect on a map |
|---|---|
| `A_Spawn`, `A_RandomJump`, `A_PlaySound`, `A_Mushroom` code pointers | These actions are recognised but do nothing. Anything a map spawns or triggers through them will not appear. |
| Sky transfer line types 271 and 272 | The map's normal sky is used instead. |
| Dog and other MBF-specific sounds | Mapped to their nearest existing equivalents. |

## MBF21

Not supported. Maps requiring MBF21 flags, line types or code pointers are outside the
current scope.

## Savegames

Not implemented. The menu shows the Load and Save entries in their authentic positions, but
they are inert.

## Reporting a compatibility problem

Include the WAD and map name, what the feature should do, and what happened instead. If the
problem involves motion or position, attach `logs/lattedoom-diag.log`, which records the
relevant state automatically.
