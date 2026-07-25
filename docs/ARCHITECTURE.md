# Architecture

Latte Doom embeds a DOOM engine in Minecraft's process and renders the level that engine is
running as world geometry. This document describes how the pieces fit together, the
terminology used throughout the source, and the constraints that shaped the design.

## The central rule

**The engine is the authority on everything it can decide.** Collision against level
geometry, monster AI, projectiles, damage, line specials, sector movement, the random
number generator and the 35 Hz tic all live in the vendored engine. The Minecraft side
renders, provides input, plays audio, and mirrors the player's position back into the
engine.

Nothing about DOOM's behaviour is reimplemented. Where a behaviour must exist on the
Minecraft side, such as the view bob or friction for a transformed player, it is
implemented from the engine's own formulas rather than approximated.

## Terminology

| Term | Meaning |
|---|---|
| **Snapshot** | A consistent copy of engine state taken between tics; the only view of the engine the rendering side reads. |
| **Mirroring** | Writing the Minecraft player's position into the engine's own player object, so monsters sense the real player. |
| **Transformed player** | A player who has become the marine: DOOM physics, weapons, status bar and view weapon. |
| **Untransformed player** | An ordinary Minecraft player, who can still walk a level and fight with Minecraft's own means. |
| **Level epoch** | An identifier for one instance of a loaded level, used to reject stale mirrored positions. |
| **Map units** | The engine's coordinates. 28 map units correspond to one Minecraft block. |

## Threads

Three clocks run concurrently, and most of the subtlety in the codebase comes from
reconciling them.

- **The engine thread** runs the engine's own loop at 35 tics per second. It is the only
  thread that touches engine state.
- **The client thread** runs Minecraft's 20 Hz tick: it reads the newest snapshot, mirrors
  the player, and applies movement.
- **The render thread** runs at the display's frame rate and interpolates between snapshots.

The engine publishes a snapshot after **every** tic, rather than per rendered frame.
Publishing per frame makes the keyframe stream as irregular as the frame rate, which no
amount of interpolation can smooth out. The engine's own software renderer is not run at
all, since Minecraft is the renderer; this frees most of the engine thread's budget and
keeps its tic rate steady.

## Engine host

`engine/DoomHost` owns the engine: it boots it on a daemon thread, publishes a snapshot
after each tic, injects input into the engine's event queue, mirrors players in, drains
damage and pickups back out, and can freeze the engine at a frame boundary. It has no
Minecraft imports, so the headless harnesses drive it exactly as the mod does.

`engine/WorldSnapshot` is the state copy itself: sector heights, light levels and flats;
every map object with its position, angle, sprite and flags; the player's own state and
weapon; and the automap, friction and pusher data the client needs. Everything is copied
into primitives, so no engine object escapes the engine thread.

## Rendering

`render/LatteWorld` is the hub. It raises a level when the engine reports one, holds the
transformations between map and world coordinates, keeps the interpolation clock, and
applies each snapshot to the standing geometry.

The geometry pipeline is:

1. **`DoomMap`** parses the map lumps from the WAD the player supplied.
2. **`SectorTriangulator`** turns each sector into polygons with holes, traced from its
   linedef boundary loops, and triangulates them with the vendored `Earcut` port. Walls are
   later built from those same linedef vertices, so a floor rim and the base of its wall
   share vertices exactly and no gaps can appear between them.
3. **`LatteMesh`** emits the vertex data: one quad per wall surface, with the engine's own
   texture pegging rules, and sector polygons for floors and ceilings. Textures use
   repeating samplers, so no geometry has to be subdivided per texture repeat.
4. **`DoomRuntimeTextures`** composites the WAD's textures, flats, sprites and interface
   graphics into GPU textures when a level loads. Nothing is bundled with the mod.
5. **`LatteWorldRenderer`** submits the result each frame, together with the sprites and
   the sky, into Minecraft's own deferred-submit pass.

Sky ceilings are left open by the mesh builder and filled by a camera-centred cylinder
carrying the map's sky texture, mapped with the engine's own angular rules.

### The interpolation clock

Sector motion arrives at 35 Hz and must look smooth at any frame rate. Interpolating
against the arrival times of keyframes does not work: the engine thread's tic production
jitters under thread scheduling, and the render thread only observes arrivals at frame
boundaries.

Instead a continuous clock is maintained, anchored to a tic and a timestamp, drift-corrected
per keyframe and resynchronised outright when it drifts more than a few tics, which also
recovers after a pause. Geometry is rendered at a fixed latency behind the newest keyframe,
so jitter within that budget is absorbed entirely, at the cost of a latency small enough not
to be perceptible.

Crucially, **collision heights follow the same delayed timeline as the drawn geometry**. If
the physics used the newest heights while the geometry drew the previous ones, a player
riding a moving floor would be grounded against a surface that is not where it appears.

## Player mirroring

The Minecraft player's position is written into the engine's player object every frame, so
every sense a monster uses (sight, aim, infighting) tracks the real player. Health is
slaved in both directions: engine damage is billed to Minecraft health, and healing items
credit it back.

Two problems arise from mirroring, both solved by the same mechanism:

- **Stale positions after a level change.** A fresh spawn would be overwritten immediately
  by the player's previous position, reinterpreted through the new level's origin. Each
  snapshot therefore carries a **level epoch**; the client echoes it back with its mirrored
  position, and the engine side ignores positions stamped with a stale epoch.
- **Engine-side teleports.** When a teleporter moves the engine's player object, the next
  mirror write would move it straight back. The engine counts real teleports of the local
  player, the snapshot carries that count, and the client follows unconditionally when it
  changes, while the engine holds mirror writes until the client has caught up.

## Movement

`play/DoomMovement` implements two modes, described in full in its class documentation.

An **untransformed player** keeps Minecraft's movement: vanilla `travel()` runs untouched
and the resulting displacement is then re-applied under the level's collision. Minecraft's
own acceleration is kept, while the level's walls, floors, steps and moving sectors all
apply.

A **transformed player** runs the engine's physics directly: its thrust and friction
integrated in 35 Hz substeps, no air control and no jump. Since 35 does not divide evenly
into Minecraft's 20 Hz, the rendered position is the interpolation between the last two tic
states, which keeps the apparent speed constant.

`play/DoomCollision` is the engine's own `P_CheckPosition`, `P_TryMove` and `P_SlideMove` in
map units: destination-box testing against linedefs, a 56-unit height requirement, a
24-unit step limit and wall sliding. It has no Minecraft dependencies, so the movement
harness can exercise it headlessly.

Blocks placed inside a level are additional solid geometry for both modes, applied through
Minecraft's own collision. Monsters and projectiles do not see them: the engine has no
knowledge of them, and that limitation is deliberate rather than incidental.

## Combat

Damage crosses in both directions.

- `play/DoomCombat` watches the engine's own discharges, meaning ammunition decrements and
  weapon animation frames, then casts equivalent rays in Minecraft space with DOOM's ranges,
  spreads and damage dice, so DOOM weapons hurt Minecraft entities.
- `play/MinecraftCombat` tests Minecraft melee swings and arrows against the snapshot's
  shootable objects and reports hits to the engine, where the engine's own damage routine
  applies them, so monsters retaliate correctly and barrels explode.

## Networking

`net/LatteNet` defines every payload and both sides' handlers. One client runs the engine
and announces the level it has raised, with its origin; other clients raise the same level
and render it from the snapshots and sound events that client sends. Damage, item
conversion, block placement and teleports are applied server-side.

Only state is transmitted: positions, heights, tics, sprite indices, map names. No
WAD-derived content ever crosses the network, because every client renders from its own
copy of the game data. This is a licensing requirement as much as a design one; see
[LEGAL.md](../LEGAL.md).

## Interface

The menu, intermission, finale, status bar, automap and volume screens are Minecraft
screens drawn from the WAD's own graphics, following the engine's layouts. The engine's
framebuffer is never shown in normal play; `/doomscreen` exposes it for diagnosis only.

The automap deserves a note: it shows only lines the player has seen, which the engine
normally marks as its renderer draws them. Since that renderer does not run here, the host
reproduces the reveal by casting a fan of sight rays across the player's field of view each
tic, stopping at solid walls and closed openings.

## Modifications to the vendored engine

The engine is vendored under `src/mochadoom` and patched as little as possible. Every
modification is marked with a single grep-able form:

```
grep -rn "Latte Doom patch:" src/mochadoom
```

The patches fall into four groups: hooks that publish state or accept input; changes that
let Minecraft own the player's death, so the engine's own death cycle never runs for a
mirrored player; Boom and MBF feature work; and defensive hardening, so that a malformed
WAD produces a logged failure rather than terminating the process inside Minecraft.

Upstream copyright headers are never altered.
