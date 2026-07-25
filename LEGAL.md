# LEGAL: the source port contract

Latte Doom is a **source port**. Like Chocolate Doom, GZDoom, Woof and Mocha Doom before
it, it is lawful for one reason: **it ships game code, never game data.** The mod cannot
run without an IWAD the user already owns. That is the design, not a limitation, and every
contributor works under the rules below.

## The rules

1. **No id Software content in the repository or the built jar.**
   No textures, sprites, flats, patches, sounds, music, demos, level data, palettes,
   `COLORMAP` or `ENDOOM`. Nothing extracted from `DOOM.WAD` or `DOOM2.WAD`, in any format,
   however converted, resized, re-encoded, or intended only for testing.

2. **Everything composites at runtime from the user's own WAD.**
   Players supply a legally obtained IWAD (Steam, GOG, original discs) in
   `config/latte-doom/`. With no WAD present the mod idles and explains why (rule 5).

3. **Lump names are fine; lump contents are not.**
   Code may reference `STBAR`, `PLAYA1`, `E1M1`, state tables, thing types and physics
   constants: that is engine knowledge, published under the GPL with id's source release.
   The bytes behind those names stay in the user's WAD.

4. **The engine is GPL, so this project is GPL.**
   The vendored Mocha Doom sources descend from id's GPL source release, so Latte Doom is
   GPL-3.0-or-later (see the `license` field in `fabric.mod.json`). Keep it that way and
   keep the upstream authors credited: id Software, velktron/Maes, Good Sign, AXDOOMER
   and contributors.

5. **A missing or mismatched WAD produces a clear message, never a workaround.**
   The join handshake tells players without a WAD that they need their own copy. Never
   bundle, download or otherwise fetch game data on a player's behalf; pointing at Steam
   or GOG is the only correct answer.

6. **Multiplayer synchronises state, never content.**
   The network layer may carry positions, heights, tics, sprite names and indices, map
   names and origins. It must never carry composited textures, sprite pixels, audio or any
   other WAD-derived bytes: each client renders from its own WAD. This is also why the
   WAD-mismatch notice exists.

7. **Test fixtures follow the same law.**
   The harnesses (`triProbe`, `moveProbe`, `doomSmoke`, `arenaProbe`, `dehProbe`) take a
   WAD path from the caller. Never commit a WAD, a WAD fragment, or generated fixtures
   derived from one, including baked meshes of real levels and palette-derived translucency
   maps.

8. **PWADs are the user's business.**
   The `-file` mechanism loads whatever the user provides at runtime. Free community WADs
   such as Freedoom may be recommended in documentation for players without an IWAD, but
   are never bundled without first checking their licence terms.

## Authorship

Upstream engine credit is covered by rule 4. Beyond that, this project is written and
maintained by blackwithersteve, with AI assistance used throughout to speed up
development. Everything committed is reviewed and is covered by the verification gates
described in `CONTRIBUTING.md`.

