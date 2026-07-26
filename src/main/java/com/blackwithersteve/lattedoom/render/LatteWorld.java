package com.blackwithersteve.lattedoom.render;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client-side level: whichever map the engine is running is built as real geometry in
 * the world, at a deterministic origin so that every client places it identically.
 *
 * <p>Everything here is driven by {@link WorldSnapshot}. The engine decides which level
 * exists, what each sector's floor and ceiling heights are, so doors and lifts move because
 * it moves them, and what each sector's light level is, from its own light thinkers. This
 * class only mirrors that state; it never simulates.
 *
 * <p>It also owns the interpolation clock that keeps 35 Hz engine motion smooth at the
 * display's frame rate, the player-mirroring handshake, and the transformations between map
 * coordinates and world coordinates.
 *
 * <p>All state is touched exclusively on the Minecraft client thread, during the tick and
 * the render pass.
 */
public final class LatteWorld {

    private static final Logger LOGGER = LoggerFactory.getLogger("lattedoom");

    /** Map units per Minecraft block, used by every coordinate transform. At 28, DOOM's
     * 56-unit player height is exactly 2 blocks and a 24-unit step is 0.86, so a Minecraft
     * player fits through everything the original does. */
    public static final double UNITS = 28.0;

    /** Cache identifier for the current WAD set. It is generational: changing the WAD set
     * increments it, so every cache derived from a WAD: the merged file, the sprite table
     * and the GPU textures, all keyed by this identifier: reloads lazily from the new set
     * rather than serving the previous one. */
    private static int wadGeneration;

    public static String wadId() {
        return "iwad-g" + wadGeneration;
    }

    /** Incremented whenever the level origin changes. Stored origin-relative coordinates
     * refer to the old origin once it does, so holders must re-seed or they displace the
     * player by the difference. */
    private static int originEpoch;

    public static int originEpoch() {
        return originEpoch;
    }

    private static WadFile wad;
    /** Extra WADs (SIGIL etc.) merged on top of the IWAD, mirroring the engine's -file
     * set so the added maps and art render. Set at client init; /doomwad changes it. */
    private static java.util.List<java.nio.file.Path> pwads = java.util.List.of();

    public static void setPwads(java.util.List<java.nio.file.Path> list) {
        pwads = list != null ? list : java.util.List.of();
    }

    /** /doomwad changed the WAD set: forget every cached WAD-derived thing and drop the
     * standing level. The next engine boot (and mesh/HUD load) reads the new set. */
    public static void reloadWadSet(java.util.List<java.nio.file.Path> newPwads) {
        WadFile.evict(wadId()); // the old merged set must not pile up in the cache
        wadGeneration++;
        setPwads(newPwads);
        wad = null;
        sprites = null;
        marineAssetsFailed = false;
        drop();
    }

    /** Load (once, cached) the IWAD with the configured PWADs merged on top. */
    private static WadFile openWad(java.nio.file.Path iwadPath) throws java.io.IOException {
        return WadFile.loadMerged(wadId(), iwadPath, pwads);
    }

    /** The native shell (menu/intermission/finale screens) needs the WAD's UI art even
     * before any level ever loaded: e.g. pressing M on a fresh client. Registers lazily
     * on the client tick (the safe GL slot); cheap no-op once the set is loaded. */
    public static void ensureUiAssets(Minecraft mc, java.nio.file.Path iwadPath) {
        if (iwadPath == null || mc.level == null) {
            return;
        }
        try {
            if (wad == null) {
                wad = openWad(iwadPath);
            }
            DoomRuntimeTextures.init();
            DoomRuntimeTextures.ensureLoaded(wadId());
        } catch (Exception e) {
            LOGGER.error("LatteWorld: UI assets failed to load", e);
        }
    }
    private static DoomMap map;
    /** The WAD's sprite table (frame/rotation/flip), built once per WAD. */
    private static SpriteSet sprites;
    /** Static sector triangulation (x,y only; heights applied at bake): built once per map. */
    private static SectorTriangulator.Result tri;
    private static double cx, cy; // doom-space map centre, the mesh transform origin
    private static String mapName;        // "e5m1", which level the mesh currently is
    private static String failedMap;      // a map that failed to load: don't retry every tick
    /** Per-sector baked quad batches (texture key -> packed 6-float vertices). */
    private static Map<Integer, Map<String, float[]>> groups;
    /** Per-sector mesh-local AABB {minX,minY,minZ,maxX,maxY,maxZ}: for frustum culling.
     * Kept in step with {@link #groups} (rebuilt on load + every mover rebake). */
    private static Map<Integer, double[]> sectorBounds;

    /** Mesh-local bounding box of a baked sector's geometry (all its texture batches). */
    private static double[] computeBounds(Map<String, float[]> group) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (float[] v : group.values()) {
            for (int i = 0; i + 2 < v.length; i += 6) {
                minX = Math.min(minX, v[i]);     maxX = Math.max(maxX, v[i]);
                minY = Math.min(minY, v[i + 1]); maxY = Math.max(maxY, v[i + 1]);
                minZ = Math.min(minZ, v[i + 2]); maxZ = Math.max(maxZ, v[i + 2]);
            }
        }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    static Map<Integer, double[]> sectorBounds() {
        return sectorBounds;
    }
    /** Latest engine snapshot (renderer reads sector light from it per frame). */
    private static WorldSnapshot snap;
    /** World position of the map centre at DOOM height 0. */
    private static double originX, originY, originZ;
    /** Sector adjacency (shared lines), cached per map for the moving-neighbor rebakes. */
    private static int[][] neighbors;
    /** Sectors whose static bake is stale; re-baked when they stop moving (not per tic). */
    private static final Set<Integer> dirtyRebake = new HashSet<>();
    /** Sector -> indices into map.lines of every linedef touching it (front OR back side).
     * Precomputed once at load so the per-frame mover bake ({@link #bakeInterp}) walks a
     * handful of lines instead of rescanning the whole level's linedefs every sector, every frame. */
    private static int[][] sectorLines;
    /** The level's vertical envelope (authored extremes), for the inside-the-level test. */
    private static int minFloorH, maxCeilH;
    // ---- mover interpolation: the engine's last three 35Hz keyframes per sector: the
    // rendered glide runs one tic behind (prev2 -> prev) so a late-arriving tic never
    // stalls a door mid-motion; collision keeps the freshest heights (cur) ----
    private static double[] prevFloor2, prevCeil2, prevFloor, prevCeil, curFloor, curCeil;
    private static int lastTic = -1;
    private static long ticSeenNanos;

    // Position-change diagnostic: any jump over 10 blocks in one tick is logged with both
    // positions and the dimension, which covers movers outside this class as well.
    private static double diagPX = Double.NaN, diagPY, diagPZ;
    private static String diagDim = "";

    /** Called every END_CLIENT_TICK: latch/refresh/drop the level to match the engine. */
    public static void clientTick(Minecraft mc, DoomHost host, Path iwadPath) {
        com.blackwithersteve.lattedoom.diag.DoomDiag.tickPlayer(mc);
        // Return to the overworld rather than falling forever in the empty level dimension.
        // This must not fire while a level load is in flight, because the map is legitimately
        // absent for a few ticks and rescuing then ejects the player just before the incoming
        // level places them. Three states count as in flight: the engine between levels after
        // an exit, a start-delivery still pending, and an engine that is still booting. A
        // booting engine reports hadLevel() false, so the between-levels test alone does not
        // cover a warp reboot.
        final boolean loadInFlight = startTeleport
            || (host != null && host.hadLevel() && host.isBetweenLevels())
            || (host != null && host.state()
                == com.blackwithersteve.lattedoom.engine.DoomHost.State.BOOTING);
        if (mc.player != null && inLevelDim(mc)
            && (map == null || mc.player.getY() < -64)
            && !loadInFlight) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", String.format(
                "void rescue -> leaveLevelDim (map=%s y=%.1f)", map != null, mc.player.getY()));
            leaveLevelDim(mc);
        } else if (mc.player != null && inLevelDim(mc) && map == null && loadInFlight) {
            // The rescue was withheld: record which of the in-flight states held it, so a
            // load that never completes can be told apart from one that did.
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("level", String.format(
                "void rescue held: start=%s booting=%s between=%s", startTeleport,
                host != null && host.state()
                    == com.blackwithersteve.lattedoom.engine.DoomHost.State.BOOTING,
                host != null && host.hadLevel() && host.isBetweenLevels()));
        }
        if (mc.player != null) {
            final String dim = mc.player.level().dimension().identifier().toString();
            if (!Double.isNaN(diagPX)) {
                final double d = Math.abs(mc.player.getX() - diagPX)
                    + Math.abs(mc.player.getY() - diagPY) + Math.abs(mc.player.getZ() - diagPZ);
                if (d > 10 || !dim.equals(diagDim)) {
                    LOGGER.warn("PLAYER JUMPED: ({}, {}, {}) [{}] -> ({}, {}, {}) [{}]",
                        String.format("%.1f", diagPX), String.format("%.1f", diagPY),
                        String.format("%.1f", diagPZ), diagDim,
                        String.format("%.1f", mc.player.getX()),
                        String.format("%.1f", mc.player.getY()),
                        String.format("%.1f", mc.player.getZ()), dim);
                }
            }
            diagPX = mc.player.getX();
            diagPY = mc.player.getY();
            diagPZ = mc.player.getZ();
            diagDim = dim;
        }
        ensureRemoteMarineAssets(mc, iwadPath);
        final WorldSnapshot s = host != null ? host.worldSnapshot() : null;
        // World vs suit: when someone else's engine owns the shared level, the world is
        // theirs, from their snapshot feed, and this client's own engine, if one is
        // running, supplies only weapons and the HUD
        // (weapons/ammo/HUD). The owner (and solo play) takes the engine path unchanged.
        final boolean worldIsRemote = remoteName != null && remoteOwner != null
            && mc.player != null && !remoteOwner.equals(mc.player.getUUID());
        if (worldIsRemote && mc.level != null && iwadPath != null) {
            remoteTick(mc, host, iwadPath);
            return;
        }
        if (mc.level == null || iwadPath == null || s == null) {
            // A null snapshot here means the level was completed, not that the engine died:
            // Crossing an exit moves it to the intermission or finale, where no snapshot is
            // produced while the next map loads. Tearing the level down now would leave the
            // player in an empty dimension, so hold instead and wait for the next in-level
            // snapshot. The hadLevel check matters because a booting engine also passes
            // through non-level states, and those must not trigger a hold or an advance.
            final boolean inDim = mc.player != null && inLevelDim(mc);
            if (host != null && host.hadLevel() && host.isBetweenLevels()
                && (warpedIn || inDim)) {
                // Standing in the level dimension is itself proof of a warped session, so
                // the flag is restored rather than trusted. If something cleared it during
                // the transition, the hold and delivery chain continues instead of ejecting.
                if (!warpedIn) {
                    com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level",
                        "between-levels with warpedIn=false while in dim: flag restored");
                    warpedIn = true;
                }
                if (host.gamestateKind() == 3) {
                    // Title, not intermission: the finale ended (episode complete) or the
                    // player chose End Game in the engine menu. debounced: a transient
                    // title read during a transition must never eject: only a state that
                    // holds for half a second is a real adventure end.
                    if (++titleTicks >= 10) {
                        titleTicks = 0;
                        // The adventure is over, so the guest-delivery record is cleared:
                        // the next shared level to arrive counts as a first join again.
                        guestDeliveredMap = "";
                        leaveLevelDim(mc);
                        drop();
                        suit = null;
                        return;
                    }
                } else {
                    titleTicks = 0;
                }
                holdBetweenLevels(mc);
                return;
            }
            if (inDim) {
                // Falling through here while inside the dim is the ejection precursor:
                // Record which condition failed before anything is torn down.
                com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", String.format(
                    "null-snap fallthrough IN DIM: warped=%s host=%s hadLevel=%s between=%s",
                    warpedIn, host != null, host != null && host.hadLevel(),
                    host != null && host.isBetweenLevels()));
            }
            suit = null;
            drop(); // no local engine and no shared level: nothing to stand
            return;
        }
        suit = s;
        snap = s;
        watchdog(mc, host, s);
        final String name = engineMapName(iwadPath, s.episode, s.map);
        if (name.equals(failedMap)) {
            return;
        }
        if (!name.equals(mapName)) {
            try {
                load(mc, name, iwadPath);
            } catch (Exception e) {
                LOGGER.error("LatteWorld: level {} failed to load", name, e);
                com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("error", "level " + name + " failed: " + e);
                drop();
                failedMap = name;
                return;
            }
            // A new map went live while an intermission or diagnostic screen was open, and
            // this is not demo playback: close the screen and deliver the player to the new
            // map's start. New Game from the menu takes the load path instead.
            if ((mc.gui.screen() instanceof com.blackwithersteve.lattedoom.LatteDoomScreen
                || mc.gui.screen() instanceof com.blackwithersteve.lattedoom.LatteIntermissionScreen)
                && !s.demo) {
                requestStartTeleport();
                mc.gui.setScreen(null);
            }
        }
        if (levelAdvancePending) {
            // The player was held through the intermission and the next level now stands,
            // deliver them to its P1 start (the same path /doomwarp delivery uses)
            levelAdvancePending = false;
            startTeleport = true;
        }
        // A start-of-level delivery may only fire for a session that entered a level; an
        // engine booted only for weapons and the HUD must never consume one. The exception
        // is a player already standing in the level dimension, which is proof of such a
        // session, so the flag is restored rather than the delivery dropped.
        if (startTeleport && !warpedIn) {
            if (mc.player != null && inLevelDim(mc)) {
                LOGGER.warn("startTeleport without warpedIn but player IS in the level dim"
                    + ": flag restored, delivering");
                warpedIn = true;
            } else {
                LOGGER.warn("startTeleport was set WITHOUT warpedIn: dropped (stale flag)");
                startTeleport = false;
            }
        }
        if (startTeleport && warpedIn && mc.player != null && map != null) {
            final com.blackwithersteve.lattedoom.engine.WorldSnapshot cs = snap;
            // Death-restart wait: the engine has not reloaded yet, so this snapshot still
            // belongs to the instance the player died in. The request stays pending and is
            // consumed off the first snapshot from a new instance.
            if (deliverAfterEpoch != 0 && cs != null && cs.levelEpoch == deliverAfterEpoch) {
                return;
            }
            deliverAfterEpoch = 0;
            // /doomwarp delivery: fires even when the same map was already standing.
            // The level lives in its own void dimension: this both delivers the player to
            // the P1 start and moves them out of the overworld, so nothing else renders.
            startTeleport = false;
            // Deliver the player to where the engine spawned them, never to a
            // start position parsed from the map. Boom-format maps commonly contain extra
            // player-1 starts inside sealed closets, used to drive scripted effects, and
            // only the engine knows which of them is the real one.
            double[] start = null;
            if (cs != null && cs.playerMobj >= 0) {
                start = new double[]{doomToWorldX(cs.px), doomToWorldH(cs.pz), doomToWorldZ(cs.py)};
            }
            if (start == null) {
                start = playerStartWorld();
            }
            if (start != null) {
                enterLevelDim(mc, start[0], start[1] + 0.1, start[2]);
                // Mute the mirror while the server teleport settles, and re-seed the
                // crossing tracker: a line traced from the pre-delivery spot to the new
                // start would fire every special between them
                mirrorHoldTicks = 2;
                lastMirrorX = Double.NaN;
                lastEngineX = Double.NaN;
                com.blackwithersteve.lattedoom.play.DoomMovement.forceReseed();
            }
        }
        // Announcing the level is what makes the server route other players' hits to this
        // client's engine. The announcement on load only fires when the map name changes, and
        // a player can enter a level that was already raised, so claim ownership here as soon
        // as they are genuinely in one. Idempotent, and skipped for spectators.
        if (warpedIn && !announcedLevel && map != null && mapName != null) {
            com.blackwithersteve.lattedoom.net.LatteNet.sendLevelUp(mapName, originX, originY, originZ);
            announcedLevel = true;
        }
        // (height sync moved to renderSync(): sampling the 35Hz engine on the 20Hz client
        // tick made doors step unevenly: the renderer advances keyframes per FRAME instead)

        // Mirror the player into the engine so it senses them, fire any special lines their
        // movement crossed, and follow teleports back the other way. Demo playback is
        // excluded, since mirroring would overwrite the recorded player.
        playMode = mc.player != null && mapName != null && map != null
            && s != null && !s.demo
            && insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (playMode && mc.player != null && mapName != null) {
            final com.blackwithersteve.lattedoom.engine.WorldSnapshot s2 = snap;
            // Authoritative teleport follow: the engine counts every real teleport of the
            // local player. Count moved -> snap, whatever the distance (the old >128-unit
            // heuristic missed short hops, where the teleport fog appeared but the player
            // stayed put. A new level instance adopts the count without acting on it.
            if (s2 != null) {
                if (s2.levelEpoch != seenLevelEpoch) {
                    seenLevelEpoch = s2.levelEpoch;
                    seenTeleportCount = s2.teleportCount;
                } else if (s2.teleportCount != seenTeleportCount) {
                    seenTeleportCount = s2.teleportCount;
                    if (s2.playerMobj >= 0) {
                        final double tex = s2.mx[s2.playerMobj], tey = s2.my[s2.playerMobj];
                        final double tea = s2.mAngleDeg[s2.playerMobj];
                        teleportPlayer(mc, doomToWorldX(tex),
                            doomToWorldH(s2.mz[s2.playerMobj]), doomToWorldZ(tey));
                        // The original turns the player to the destination's facing, and
                        // they arrive stationary. Boom's silent teleports should carry
                        // rotated momentum through; that is not implemented, so they also
                        // arrive stationary.
                        mc.player.setYRot((float) (-(tea + 90.0)));
                        mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                        host.setPlayerMirror(true, tex, tey, s2.mz[s2.playerMobj], tea,
                            s2.levelEpoch);
                        com.blackwithersteve.lattedoom.play.DoomMovement.forceReseed();
                        lastMirrorX = tex;
                        lastMirrorY = tey;
                        lastEngineX = tex;
                        lastEngineY = tey;
                        return;
                    }
                }
            }
            // A start-delivery just fired and the server teleport may still be settling.
            // One mirror write of the old position, stamped with the fresh epoch, would
            // overwrite the spawn.
            if (mirrorHoldTicks > 0) {
                mirrorHoldTicks--;
                return;
            }
            final double ang = -mc.player.getYRot() - 90.0;
            final double dx = worldToDoomX(mc.player.getX());
            final double dy = worldToDoomY(mc.player.getZ());
            final double dh = worldToDoomH(mc.player.getY());
            if (s2 != null && s2.playerMobj >= 0 && !Double.isNaN(lastMirrorX)
                && !Double.isNaN(lastEngineX)) {
                final double ex = s2.mx[s2.playerMobj], ey = s2.my[s2.playerMobj];
                // Speed-independent teleport detection: a teleporter moves the engine's
                // object a long way in one step while the player barely moves, whereas fast
                // walking moves both together. Comparing the object's jump against the
                // player's own step avoids false positives while sprinting.
                final double engineJump = Math.hypot(ex - lastEngineX, ey - lastEngineY);
                final double playerStep = Math.hypot(dx - lastMirrorX, dy - lastMirrorY);
                if (engineJump > 128 && engineJump > playerStep * 3.0 + 96) {
                    // Re-seed as the authoritative follow does. The keyframes and the glued
                    // sector describe where the player left, not where they arrived, and a
                    // glue left pointing at the departure sector pulls them back towards its
                    // floor at the destination.
                    com.blackwithersteve.lattedoom.play.DoomMovement.forceReseed();
                    teleportPlayer(mc, doomToWorldX(ex),
                        doomToWorldH(s2.mz[s2.playerMobj]), doomToWorldZ(ey));
                    host.setPlayerMirror(true, ex, ey, s2.mz[s2.playerMobj], ang,
                        s2.levelEpoch);
                    lastMirrorX = ex;
                    lastMirrorY = ey;
                    lastEngineX = ex;
                    lastEngineY = ey;
                    return;
                }
            }
            if (s2 != null && s2.playerMobj >= 0) {
                lastEngineX = s2.mx[s2.playerMobj];
                lastEngineY = s2.my[s2.playerMobj];
            }
            if (!Double.isNaN(lastMirrorX)) {
                detectCrossings(host, lastMirrorX, lastMirrorY, dx, dy);
            }
            host.setPlayerMirror(true, dx, dy, dh, ang,
                s2 != null ? s2.levelEpoch : 0L);
            host.setPlayerHealth((int) Math.ceil(mc.player.getHealth() * 5.0f));
            lastMirrorX = dx;
            lastMirrorY = dy;

            // Engine damage is charged to the Minecraft health bar at a 5:1 scale, with no
            // invulnerability frames, since DOOM has none. Medikits and soulspheres heal it
            // back. Creative and spectator mode are exempt.
            // An engine attack that throws the player upward, most visibly the arch-vile's.
            // Momentum is in map units per tic; Minecraft velocity is blocks per tick.
            final double launch = host.drainLaunch();
            if (launch > 0 && mc.player != null && !mc.player.isSpectator()) {
                if (marineForm) {
                    // The engine's own integrator owns a transformed player's vertical.
                    com.blackwithersteve.lattedoom.play.DoomMovement.launch(launch);
                } else {
                    final double vy = launch * (35.0 / 20.0) / UNITS;
                    mc.player.setDeltaMovement(mc.player.getDeltaMovement().add(0, vy, 0));
                    mc.player.hurtMarked = true;
                }
            }
            final int dmg = host.drainDamage();
            final int heal = host.drainHeal();
            final double[] knock = host.drainKnockback();
            if (dmg > 0 && knock != null && marineForm) {
                // Marine form: DOOM's own thrust, straight into the momentum integrator
                com.blackwithersteve.lattedoom.play.DoomMovement.damageThrust(
                    knock[0] * knock[2], knock[1] * knock[2]);
            }
            if ((dmg > 0 || heal > 0)
                && !mc.player.getAbilities().invulnerable && !mc.player.isSpectator()) {
                // Routed through the server rather than the integrated-server shortcut,
                // which is null on a LAN guest and drops the damage.
                double kx = 0, kz = 0;
                if (!marineForm && dmg > 0 && knock != null) {
                    kx = knock[0] * 0.4; // doom dir -> world axes: wx = dx, wz = -dy
                    kz = -knock[1] * 0.4;
                }
                com.blackwithersteve.lattedoom.net.LatteNet.sendPlayerDamage(
                    mc.player.getUUID(), dmg, heal, kx, kz,
                    shieldCovers(mc, dmg, knock));
            }
            // The engine consumed DOOM items an untransformed player walked over. Each is
            // converted through pickups.properties and the server grants the Minecraft
            // side: health, food or items.
            if (!marineForm && !mc.player.isSpectator()) {
                for (String spr : host.drainScavenge()) {
                    final com.blackwithersteve.lattedoom.PickupConfig.Action a =
                        com.blackwithersteve.lattedoom.PickupConfig.action(spr);
                    if (a != null) {
                        com.blackwithersteve.lattedoom.net.LatteNet.sendScavenge(
                            a.heal(), a.food(), a.item(), a.count());
                    }
                }
            }
            // Damage dealt by Minecraft itself never passes through the engine, so the
            // status bar would not react to it. Any health loss beyond what the engine
            // billed this window is reported to it as pain. Engine hits carry a short-lived
            // credit so their own health loss is not counted twice.
            final int hpNow = (int) Math.ceil(mc.player.getHealth() * 5.0f);
            engineDmgCredit = Math.min(400, engineDmgCredit + dmg);
            if (marineForm && hpNow < lastHpSeen) {
                final int drop = lastHpSeen - hpNow;
                final int covered = Math.min(drop, engineDmgCredit);
                engineDmgCredit -= covered;
                if (drop - covered > 0) {
                    host.requestPlayerPain(drop - covered);
                }
            }
            engineDmgCredit -= engineDmgCredit / 5;
            lastHpSeen = hpNow;
        } else {
            host.setPlayerMirror(false, 0, 0, 0, 0, 0L);
            lastMirrorX = Double.NaN;
            lastEngineX = Double.NaN;
        }
        // Dying inside a level is recorded here. On respawn the map restarts and the
        // engine's own level initialisation restores the starting weapons, which is what
        // single-player DOOM does, and the player is delivered to the level's start.
        if (mc.player != null && warpedIn) {
            if (mc.player.isDeadOrDying()
                && insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
                diedInLevel = true;
            } else if (diedInLevel && !mc.player.isDeadOrDying()) {
                diedInLevel = false;
                host.requestMapRestart();
                startTeleport = true; // deliver to the reborn map's start
                // The restart happens on the ENGINE thread: hold the delivery until a
                // snapshot from a new level instance arrives, or the player is delivered
                // straight to his death spot off a pre-restart snapshot
                deliverAfterEpoch = snap != null ? snap.levelEpoch : 0;
            }
        }
        // The suit link works wherever the engine runs: weapon switching and the trigger are
        // not tied to standing inside a level, so a transformed player in the overworld keeps
        // the weapon.
        weaponInput(mc, host);
        com.blackwithersteve.lattedoom.play.DoomCombat.tick(mc, suit); // DOOM guns vs MC mobs/players
        com.blackwithersteve.lattedoom.play.MinecraftCombat.tickArrows(mc); // MC arrows vs demons
        final java.util.List<int[]> sounds = host.drainSounds();
        if (announcedLevel) {
            com.blackwithersteve.lattedoom.net.LatteNet.sendSnap(s);
            com.blackwithersteve.lattedoom.net.LatteNet.sendSounds(sounds);
        }
    }

    private static boolean fireWas;
    private static boolean tapFire;
    private static int lastSlot = -1;
    private static g.Signals.ScanCode pendingRelease;
    private static int releaseIn;
    private static int lastHpSeen = 200;
    private static int engineDmgCredit;

    /** A left-click landed (via the cancelled vanilla attack): pull the DOOM trigger
     * even if the button is already up by the time the tick samples it. */
    public static void tapFire() {
        tapFire = true;
    }

    /** Weapon selection and firing for a transformed player. A hotbar slot change posts the
     * matching number key, so the scroll wheel works and the engine applies its own rules
     * about which weapons are owned and loaded. The attack key is forwarded into the
     * engine's event queue, which fires the weapon with its own ballistics and ammunition. */
    private static void weaponInput(Minecraft mc, DoomHost host) {
        if (mc.player == null) {
            return;
        }
        // Weapon keys have to be held across a tic boundary. A down and up posted in the
        // same frame arrive in one D_ProcessEvents batch, so G_BuildTiccmd never observes
        // the key and the switch does not happen.
        if (pendingRelease != null) {
            if (--releaseIn <= 0) {
                host.postKey(pendingRelease, false);
                pendingRelease = null;
            }
        } else if (marineForm) {
            final int slot = mc.player.getInventory().getSelectedSlot();
            if (slot != lastSlot && slot >= 0 && slot < 7) {
                final g.Signals.ScanCode[] keys = {
                    g.Signals.ScanCode.SC_1, g.Signals.ScanCode.SC_2, g.Signals.ScanCode.SC_3,
                    g.Signals.ScanCode.SC_4, g.Signals.ScanCode.SC_5, g.Signals.ScanCode.SC_6,
                    g.Signals.ScanCode.SC_7};
                host.postKey(keys[slot], true);
                pendingRelease = keys[slot];
                releaseIn = 2; // ~3.5 tics of hold, long enough for the ticcmd builder
                lastSlot = slot;
            }
        }
        final boolean fire = marineForm && mc.gui.screen() == null
            && (mc.options.keyAttack.isDown() || tapFire);
        tapFire = false;
        fireHeld = fire;
        if (fire != fireWas) {
            host.postKey(g.Signals.ScanCode.SC_LCTRL, fire);
            fireWas = fire;
        }
    }

    /** This tick's resolved trigger state (taps included): presence reuses it. */
    private static boolean fireHeld;

    private static double lastMirrorX = Double.NaN, lastMirrorY;
    // The engine mobj's position last snapshot: for speed-independent teleport detection
    private static double lastEngineX = Double.NaN, lastEngineY;
    // Authoritative teleport follow: the engine counts player teleports and the player is
    // moved whenever the count changes, with no distance heuristic. A new level instance
    // adopts the current count without acting on it.
    private static long seenLevelEpoch;
    private static int seenTeleportCount;
    /** Ticks to keep the mirror muted after a start-delivery: teleportPlayer goes through
     * the server, so this tick's mc.player position is still the pre-delivery spot: one
     * mirror write of it (stamped with the fresh epoch) would clobber the spawn again. */
    private static int mirrorHoldTicks;
    /** The level epoch at the moment a death restart was requested. The engine restarts on
     * its own thread, so the start-delivery waits for a snapshot carrying a different epoch;
     * consuming a pre-restart snapshot delivers the player to the spot where they died. */
    private static long deliverAfterEpoch;
    private static boolean startTeleport;
    /** Set while the engine is between levels (intermission/finale); when the next GS_LEVEL
     * snapshot arrives the player is delivered to the new map's start. */
    private static boolean levelAdvancePending;
    /** The player died inside the level; on respawn the map restarts, as in single-player. */
    private static boolean diedInLevel;
    /** Consecutive between-levels ticks reading title: the adventure-end branch is
     * debounced so a transient title read during a transition can't eject the player. */
    private static int titleTicks;

    /** The next level load delivers the player to its P1 start (/doomwarp's arrival). This
     * indicates an intent to enter the level, so it is marked before the map is built,
     * so the geometry renders and the level announces (a /doommarine suit-boot does neither). */
    public static void requestStartTeleport() {
        final StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        LOGGER.info("requestStartTeleport from {}.{}:{}",
            caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1),
            caller.getMethodName(), caller.getLineNumber());
        startTeleport = true;
        warpedIn = true;
    }

    /** Clears every piece of session state on a world change. Without this, a new world
     * inherits the previous one's warp flags, mirrored positions and level origin, none of
     * which describe it. */
    public static void fullSessionReset() {
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", "fullSessionReset (world change)");
        drop();
        warpedIn = false;
        startTeleport = false;
        levelAdvancePending = false;
        diedInLevel = false;
        suit = null;
        remoteName = null;
        remoteOwner = null;
        failedMap = null;
        playMode = false;
        diagPX = Double.NaN;
        lastMirrorX = Double.NaN;
        lastEngineX = Double.NaN;
        seenLevelEpoch = 0;
        seenTeleportCount = 0;
        mirrorHoldTicks = 0;
        deliverAfterEpoch = 0;
        titleTicks = 0;
        DoomAutomap.reset();
        com.blackwithersteve.lattedoom.play.DoomMovement.resetSession();
    }

    /** A suit boot ({@code /doommarine} with no engine) runs a map only for the weapon and
     * HUD: no warp, no delivery, no pending death. Stale level-session flags are cleared so
     * the boot cannot teleport the player. */
    public static void suitBoot() {
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", "suitBoot (flags cleared)");
        warpedIn = false;
        startTeleport = false;
        levelAdvancePending = false;
        diedInLevel = false;
    }

    /** Is the shared/warped level session live (drives suit music: a lone overworld suit
     * stays quiet; the level's track plays only inside a level). */
    public static boolean musicShouldPlay(Minecraft mc) {
        return warpedIn || worldIsRemoteNow(mc);
    }

    /** Holds the player in place while the engine tallies the intermission and loads the
     * next map. The finished level stays standing and ownership is kept, so the player has
     * something to stand on until the next level arrives and delivers them to its start. */
    private static void holdBetweenLevels(Minecraft mc) {
        levelAdvancePending = true;
        if (mc.player != null) {
            mc.player.setDeltaMovement(0, 0, 0); // no gravity drift between ticks
            mc.player.fallDistance = 0;
        }
    }

    /**
     * Walkover specials: any special line the mirrored movement segment properly crossed
     * fires the engine's own P_CrossSpecialLine (doors, lifts, teleporters, exits) with
     * vanilla's side convention (side 0 = started in front).
     */
    private static void detectCrossings(com.blackwithersteve.lattedoom.engine.DoomHost host,
                                        double x0, double y0, double x1, double y1) {
        final java.util.List<int[]> crossed = collectCrossings(x0, y0, x1, y1);
        for (int[] c : crossed) {
            host.requestCross(c[0], c[1]);
        }
    }

    /** Special lines this movement crossed, as {lineIdx, side}: for the local engine or
     * for shipping upstream to the level owner's engine. */
    private static java.util.List<int[]> collectCrossings(double x0, double y0,
                                                          double x1, double y1) {
        if (map == null || (x0 == x1 && y0 == y1)) {
            return java.util.List.of();
        }
        java.util.List<int[]> out = null;
        for (int i = 0; i < map.lines.size(); i++) {
            final DoomMap.Line l = map.lines.get(i);
            if (l.special() == 0) {
                continue;
            }
            if (Math.max(x0, x1) < Math.min(l.x1(), l.x2())
                || Math.min(x0, x1) > Math.max(l.x1(), l.x2())
                || Math.max(y0, y1) < Math.min(l.y1(), l.y2())
                || Math.min(y0, y1) > Math.max(l.y1(), l.y2())) {
                continue;
            }
            final int sideStart = pointSide(x0, y0, l);
            if (sideStart == pointSide(x1, y1, l)) {
                continue; // didn't cross the line's plane
            }
            // And the line must straddle the movement segment (proper crossing)
            final double mx = x1 - x0, my = y1 - y0;
            final double c1 = mx * (l.y1() - y0) - my * (l.x1() - x0);
            final double c2 = mx * (l.y2() - y0) - my * (l.x2() - x0);
            if (c1 * c2 > 0) {
                continue;
            }
            if (out == null) {
                out = new java.util.ArrayList<>(4);
            }
            out.add(new int[]{i, sideStart});
        }
        return out == null ? java.util.List.of() : out;
    }

    /** P_PointOnLineSide: 0 = front (the right of v1->v2), 1 = back. */
    private static int pointSide(double px, double py, DoomMap.Line l) {
        final double cross = (l.x2() - l.x1()) * (py - l.y1()) - (l.y2() - l.y1()) * (px - l.x1());
        return cross <= 0 ? 0 : 1;
    }

    /** Recomputed each tick: the engine senses the player whenever a level is live. */
    private static boolean playMode;

    public static boolean playMode() {
        return playMode;
    }

    /** Whether the local player is transformed. Off by default: walking into a level does
     * not transform anyone, since presence and transformation are separate. */
    private static boolean marineForm;

    public static boolean marineForm() {
        return marineForm;
    }

    /** Whether this client can draw a marine body. Without a WAD the renderers fall back to
     * the vanilla avatar instead of cancelling it and drawing nothing. */
    public static boolean spritesReady() {
        return sprites != null;
    }

    public static void setMarineForm(boolean on) {
        marineForm = on;
        // Announce to the server: the roster drives what other players see (PLAY marine
        // body) and the server-side rules (no MC item pickup for marines)
        com.blackwithersteve.lattedoom.net.LatteNet.sendMarineForm(on);
    }

    // ---- world <-> doom transforms (origin = map centre at DOOM height 0, 32 u/block) ----

    public static double worldToDoomX(double wx) {
        return (wx - originX) * UNITS + cx;
    }

    public static double worldToDoomY(double wz) {
        return cy - (wz - originZ) * UNITS;
    }

    public static double worldToDoomH(double wy) {
        return (wy - originY) * UNITS;
    }

    public static double doomToWorldX(double x) {
        return originX + (x - cx) / UNITS;
    }

    public static double doomToWorldZ(double y) {
        return originZ + (cy - y) / UNITS;
    }

    public static double doomToWorldH(double h) {
        return originY + h / UNITS;
    }

    /** Finds a block placement cell against the level's geometry. Minecraft needs a real
     * block face to place against and a level offers none, so the view ray is marched until
     * it leaves open space and the last open sample's grid cell is returned. Null when
     * nothing is in reach. */
    public static net.minecraft.core.BlockPos raycastPlaceCell(Minecraft mc) {
        if (map == null || mc.player == null) {
            return null;
        }
        final net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
        final net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle();
        final double reach = 4.5, step = 0.05;
        double lox = Double.NaN, loy = 0, loz = 0;
        for (double d = 0.0; d <= reach; d += step) {
            final double wx = eye.x + look.x * d;
            final double wy = eye.y + look.y * d;
            final double wz = eye.z + look.z * d;
            final int sec = map.sectorAt(worldToDoomX(wx), worldToDoomY(wz));
            final double dh = worldToDoomH(wy);
            final boolean open = sec >= 0
                && dh > map.floorNow(sec) && dh < map.ceilNow(sec);
            if (open) {
                lox = wx;
                loy = wy;
                loz = wz;
            } else if (!Double.isNaN(lox)) {
                return net.minecraft.core.BlockPos.containing(lox, loy, loz);
            }
        }
        return null;
    }

    private static boolean marineAssetsFailed;

    /** Loads the sprites needed to draw a transformed player, for a client that never
     * booted an engine of its own. Done lazily on the client tick, from that client's own
     * WAD. */
    private static void ensureRemoteMarineAssets(Minecraft mc, Path iwadPath) {
        if (sprites != null || marineAssetsFailed || mc.level == null || iwadPath == null
            || com.blackwithersteve.lattedoom.play.MarineRoster.CLIENT.isEmpty()) {
            return;
        }
        try {
            if (wad == null) {
                wad = openWad(iwadPath);
            }
            sprites = SpriteSet.load(wad);
            DoomRuntimeTextures.init();
            DoomRuntimeTextures.ensureLoaded(wadId());
            LOGGER.info("LatteWorld: marine sprites loaded for spectating ({} frames)", sprites.size());
        } catch (Exception e) {
            marineAssetsFailed = true; // one attempt only
            LOGGER.error("LatteWorld: marine spectate sprites failed to load", e);
        }
    }

    // Freeze watchdog. A stalled engine presents as a frozen world with paused=false and
    // menu=false: the tic count stops advancing while the engine thread is still alive. If
    // that lasts 3 s without a deliberate freeze, the engine thread's stack is written to
    // the log, which identifies the wait it is blocked in.
    private static int wdTic = Integer.MIN_VALUE;
    private static long wdSinceMs;
    private static boolean wdReported;

    private static void watchdog(Minecraft mc, DoomHost host, WorldSnapshot s) {
        final long now = System.currentTimeMillis();
        if (s.tic != wdTic) {
            wdTic = s.tic;
            wdSinceMs = now;
            wdReported = false;
            return;
        }
        if (!wdReported && now - wdSinceMs > 3000 && !host.isFrozen()
            && host.state() == DoomHost.State.RUNNING) {
            wdReported = true;
            LOGGER.error("ENGINE STALL: tic stuck for {} ms, {}\n{}",
                now - wdSinceMs, host.debugState(), host.engineStackDump());
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cLatte Doom§r: the engine's tics stalled (world frozen). Stack dumped"
                    + " to logs/latest.log: that file is the freeze answer, send it over."));
            }
        }
    }

    // ---- Levels are raised in their own empty dimension, so the surrounding world's
    // chunks and entities neither render nor tick behind them. Collision is unaffected:
    // Inside a level it runs against the map rather than against blocks, so an empty
    // dimension is all that is required. The player's previous position is remembered so
    // they can be returned to it. ----
    private static final net.minecraft.resources.Identifier DOOM_DIM_ID =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("lattedoom", "doom_level");
    private static double returnX, returnY, returnZ;
    private static boolean haveReturn;
    private static boolean leaveRequested;

    /** Is the local player currently inside the private level dimension? */
    public static boolean inLevelDim(Minecraft mc) {
        return mc.player != null && mc.level != null
            && mc.level.dimension().identifier().equals(DOOM_DIM_ID);
    }

    /**
     * Send the player into the level dimension at (x,y,z). If already there it is just a
     * reposition; entering fresh from the overworld remembers where to return them to. The
     * server does the actual cross-dimension teleport (LatteNet.EnterLevelDimC2S).
     */
    // Whether a level is being played, with its geometry and collision, as opposed
    // to only being transformed. Loading a level sets this; transforming does not, since
    // that must never raise geometry in the surrounding world or reposition the player.
    private static boolean warpedIn;

    /** True while the player is warped into a level (render + DOOM physics), not just suited. */
    public static boolean warpedIn() {
        return warpedIn;
    }

    public static void enterLevelDim(Minecraft mc, double x, double y, double z) {
        if (mc.player == null) {
            return;
        }
        // Diagnostic for unexplained position changes: record the caller
        final StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", String.format(
            "enterLevelDim (%.1f, %.1f, %.1f)", x, y, z));
        com.blackwithersteve.lattedoom.diag.DoomDiag.expectJump("enterLevelDim");
        LOGGER.info("enterLevelDim -> ({}, {}, {}) from {}.{}:{}",
            String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z),
            caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1),
            caller.getMethodName(), caller.getLineNumber());
        warpedIn = true;
        if (inLevelDim(mc)) {
            teleportPlayer(mc, x, y, z); // already there: same-dimension reposition
            return;
        }
        returnX = mc.player.getX();
        returnY = mc.player.getY();
        returnZ = mc.player.getZ();
        haveReturn = true;
        leaveRequested = false;
        com.blackwithersteve.lattedoom.net.LatteNet.sendEnterLevelDim(x, y, z);
    }

    /** Returns the player to the overworld when a level ends. Does nothing if they are not
     * in the level dimension. The guard stops the two teardown paths, the engine's quit
     * callback and the broadcast it triggers, from firing twice. */
    public static void leaveLevelDim(Minecraft mc) {
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", "leaveLevelDim");
        com.blackwithersteve.lattedoom.diag.DoomDiag.expectJump("leaveLevelDim");
        warpedIn = false; // suit only from here on: stop rendering the level
        if (!inLevelDim(mc)) {
            leaveRequested = false; // not in the level dimension: clear the latch
            return;
        }
        if (leaveRequested) {
            return; // a return is already in flight; the async teleport hasn't landed yet
        }
        final double rx = haveReturn ? returnX : mc.player.getX();
        final double ry = haveReturn ? returnY : mc.player.getY();
        final double rz = haveReturn ? returnZ : mc.player.getZ();
        leaveRequested = true;
        com.blackwithersteve.lattedoom.net.LatteNet.sendLeaveLevelDim(rx, ry, rz);
    }

    /** Moves the player far enough that the server would otherwise reject it. A client-side
     * position change beyond about ten blocks is vetoed and snapped back, so the position is
     * set locally for the current frame and the server is then asked to perform the teleport
     * authoritatively. */
    public static void teleportPlayer(Minecraft mc, double x, double y, double z) {
        final StackTraceElement tpc = Thread.currentThread().getStackTrace()[2];
        com.blackwithersteve.lattedoom.diag.DoomDiag.rec("tp", String.format(
            "teleportPlayer -> (%.2f, %.2f, %.2f) from %s:%d",
            x, y, z, tpc.getMethodName(), tpc.getLineNumber()));
        com.blackwithersteve.lattedoom.diag.DoomDiag.expectJump("teleportPlayer " + tpc.getMethodName());
        if (mc.player == null) {
            return;
        }
        mc.player.setPos(x, y, z);
        com.blackwithersteve.lattedoom.net.LatteNet.sendTeleport(x, y, z);
    }

    /** Is this world position within the raised level's playable envelope? Always false
     * when not warped in, so a marine wearing only the suit in the overworld keeps normal
     * Minecraft/overworld footing (no invisible DOOM floor lifting them off the ground). */
    /** The inputs {@link #insideLevel} decides on, as one line for the diagnostic log. */
    public static String levelStateForLog() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return "no player";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("warped=").append(warpedIn)
          .append(" map=").append(mapName)
          .append(" epoch=").append(originEpoch);
        if (map != null) {
            sb.append(String.format(" doom=(%.0f, %.0f, %.0f) x[%d..%d] y[%d..%d] h[%d..%d]",
                worldToDoomX(mc.player.getX()), worldToDoomY(mc.player.getZ()),
                worldToDoomH(mc.player.getY()),
                map.minX, map.maxX, map.minY, map.maxY, minFloorH, maxCeilH));
        }
        return sb.toString();
    }

    /**
     * Whether a raised shield covers this hit. Minecraft decides that from the direction the
     * damage came from, and engine damage carries no attacker entity for it to read, so the
     * test is made here against the knockback vector the engine reports, which points from
     * the attacker towards the player. A hit from behind is not covered, as in vanilla.
     */
    private static boolean shieldCovers(Minecraft mc, int dmg, double[] knock) {
        if (dmg <= 0 || knock == null || mc.player == null || !mc.player.isBlocking()) {
            return false;
        }
        final double ax = knock[0], az = -knock[1]; // attacker to player, in world axes
        final double len = Math.hypot(ax, az);
        if (len < 1.0e-9) {
            return false;
        }
        final net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle();
        return (look.x * -ax + look.z * -az) / len > 0.0;
    }

    /** Whether this client's own engine is the one hosting the standing level. */
    public static boolean ownsLevel() {
        return announcedLevel;
    }

    /**
     * Returns the player to the overworld. A guest only leaves: the level belongs to another
     * client's engine and stays up for everyone still inside it. The owner also takes the
     * level down, which withdraws it from every other client and stops the engine, because
     * nothing else is driving it once its host walks away.
     */
    public static void leaveAndClear(Minecraft mc) {
        final boolean owner = announcedLevel;
        leaveLevelDim(mc);
        if (owner) {
            drop();
            final var host = com.blackwithersteve.lattedoom.LatteDoomClient.host();
            if (host != null) {
                com.blackwithersteve.lattedoom.LatteDoomClient.stopEngine();
            }
        } else {
            clearRemoteLevel();
        }
        setMarineForm(false);
    }

    /**
     * Whether the loaded WAD set is organised into episodes. An {@code ExMy} set is; a
     * commercial {@code MAPxx} set is not, and offering it an episode page picks the wrong
     * map. This reads the WAD's own map markers rather than which menu graphics happen to be
     * registered, because those survive a change of WAD set.
     */
    public static boolean hasEpisodes() {
        try {
            final WadFile w = wad;
            if (w == null) {
                return true; // nothing loaded yet: assume the DOOM 1 layout
            }
            for (String m : w.mapNames()) {
                if (m.length() == 4 && (m.charAt(0) == 'E' || m.charAt(0) == 'e')) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean insideLevel(double wx, double wy, double wz) {
        if (!warpedIn || map == null || mapName == null) {
            return false;
        }
        final double dx = worldToDoomX(wx);
        final double dy = worldToDoomY(wz);
        if (dx < map.minX - 64 || dx > map.maxX + 64 || dy < map.minY - 64 || dy > map.maxY + 64) {
            return false;
        }
        final double h = worldToDoomH(wy);
        return h > minFloorH - 64 && h < maxCeilH + 256;
    }

    /** PIT_CheckThing for the walking player: whether any solid engine object blocks
     * (px, py), under the blockmap rule of the object's radius plus the player's 16. */
    public static boolean solidThingAt(double px, double py) {
        return thingAt(px, py, 16.0);
    }

    /** Whether (px,py) lies inside a solid object's body rather than merely touching it. */
    public static boolean insideSolidThing(double px, double py) {
        return thingAt(px, py, 0.0);
    }

    /**
     * The thing-block test for a move from (fromX,fromY) to (toX,toY): does
     * the destination move the player deeper into a solid object than they already are? This
     * blocks walking into a monster, where the overlap would increase, while always allowing
     * sliding along it or stepping back out, where the overlap stays equal or decreases, so a
     * player can never become trapped against one and can still walk out of an object they
     * were teleported inside. A plain contact test cannot do both.
     */
    public static boolean increasesThingOverlap(double fromX, double fromY,
                                                double toX, double toY) {
        final com.blackwithersteve.lattedoom.engine.WorldSnapshot s = snap;
        if (s == null || s.mSolid == null) {
            return false;
        }
        int ownBody = 0;
        boolean haveOwn = false;
        final var mc = Minecraft.getInstance();
        if (s.rbMobjId != null && mc.player != null) {
            final java.util.UUID me = mc.player.getUUID();
            for (int i = 0; i < s.rbMobjId.length; i++) {
                if (s.rbUuidMost[i] == me.getMostSignificantBits()
                    && s.rbUuidLeast[i] == me.getLeastSignificantBits()) {
                    ownBody = s.rbMobjId[i];
                    haveOwn = true;
                    break;
                }
            }
        }
        for (int i = 0; i < s.mobjCount; i++) {
            if (!s.mSolid[i] || (i == s.playerMobj && playMode)) {
                continue;
            }
            if (haveOwn && s.mId[i] == ownBody) {
                continue;
            }
            thingDrawn(s, i, thingScratch);
            final double tx = thingScratch[0], ty = thingScratch[1];
            final double bd = s.mRadius[i] + 16.0; // blockmap rule (Chebyshev)
            final double dTo = Math.max(Math.abs(tx - toX), Math.abs(ty - toY));
            if (dTo >= bd) {
                continue; // destination clears this thing entirely
            }
            final double dFrom = Math.max(Math.abs(tx - fromX), Math.abs(ty - fromY));
            if (dTo < dFrom - 1.0e-4) {
                return true; // moving deeper into it: block (but sliding/backing out is fine)
            }
        }
        return false;
    }

    private static boolean thingAt(double px, double py, double margin) {
        final com.blackwithersteve.lattedoom.engine.WorldSnapshot s = snap;
        if (s == null || s.mSolid == null) {
            return false;
        }
        // On a shared level this client's own mirrored body appears in the object table at
        // the player's position, so treating it as solid would trap them. Other players'
        // bodies still block.
        int ownBody = 0;
        boolean haveOwn = false;
        final var mc = Minecraft.getInstance();
        if (s.rbMobjId != null && mc.player != null) {
            final java.util.UUID me = mc.player.getUUID();
            for (int i = 0; i < s.rbMobjId.length; i++) {
                if (s.rbUuidMost[i] == me.getMostSignificantBits()
                    && s.rbUuidLeast[i] == me.getLeastSignificantBits()) {
                    ownBody = s.rbMobjId[i];
                    haveOwn = true;
                    break;
                }
            }
        }
        for (int i = 0; i < s.mobjCount; i++) {
            if (!s.mSolid[i] || (i == s.playerMobj && playMode)) {
                continue; // the possessed marine is this player
            }
            if (haveOwn && s.mId[i] == ownBody) {
                continue; // this player's own remote body
            }
            thingDrawn(s, i, thingScratch);
            final double bd = s.mRadius[i] + margin; // blockmap rule: radius sum, Chebyshev
            if (Math.abs(thingScratch[0] - px) < bd && Math.abs(thingScratch[1] - py) < bd) {
                return true;
            }
        }
        return false;
    }

    /** The map's own player-1 start, as a world position (for arriving in the level). */
    public static double[] playerStartWorld() {
        if (map == null) {
            return null;
        }
        for (DoomMap.Thing t : map.things) {
            if (t.type() == 1) {
                final int sec = map.sectorAt(t.x(), t.y());
                final double floor = sec >= 0 ? map.floorNow(sec) : 0;
                return new double[]{doomToWorldX(t.x()), doomToWorldH(floor), doomToWorldZ(t.y())};
            }
        }
        return null;
    }

    public static DoomMap map() {
        return mapRef();
    }

    // ---- Remote level: another player's engine is authoritative, and this client raises
    // the same map from its own WAD at the origin that client announced, so the level is
    // visible and walkable under DOOM collision. ----
    private static String remoteName;
    private static double remoteOx, remoteOy, remoteOz;
    private static java.util.UUID remoteOwner;
    private static volatile WorldSnapshot remoteSnap;
    private static WorldSnapshot suit;
    private static boolean announcedLevel;

    /** The shared map this client was last delivered into, so a level change re-delivers. */
    private static String guestDeliveredMap = "";

    public static void setRemoteLevel(String name, double ox, double oy, double oz,
                                      java.util.UUID owner) {
        final boolean changed = !name.equals(remoteName);
        remoteName = name;
        remoteOx = ox;
        remoteOy = oy;
        remoteOz = oz;
        remoteOwner = owner;
        // A guest (someone else owns this level) joins the level dimension too, so co-op
        // players share one world: MC only renders entities/marine bodies in the same
        // dimension, and cross-side combat needs everyone co-located. Deferred until the
        // map is raised (remoteTick), which also excludes guests without a WAD.
        final var mc = Minecraft.getInstance();
        final boolean guest = mc.player != null && owner != null
            && !owner.equals(mc.player.getUUID());
        // Deliver on the first join and again on every level change, so a co-op guest is
        // carried into the next map with the owner instead of being left at coordinates
        // that belong to the previous one.
        guestEnterPending = guest && (changed || !name.equals(guestDeliveredMap));
    }

    public static void clearRemoteLevel() {
        remoteName = null;
        remoteOwner = null;
        remoteSnap = null;
        guestEnterPending = false;
        warpedIn = false; // the shared level is gone
    }

    /** A guest has a shared level to join but hasn't been moved into its dimension yet. */
    private static boolean guestEnterPending;

    /** Latest world state off the wire (the owner's engine), applied next client tick. */
    public static void acceptRemoteSnap(WorldSnapshot s) {
        remoteSnap = s;
    }

    /** {@code /doomwarp} while spectating another player's level: this client takes over as
     * the level owner (last warper wins), resumes its own engine path and re-announces. */
    public static void claimWorld() {
        clearRemoteLevel();
    }

    /** This client's own engine state: the marine suit (weapons/ammo/HUD). On the level
     * owner it is the same object as the world snapshot; on a spectator it is their
     * private suit engine (or null, no HUD). */
    static WorldSnapshot suitSnap() {
        return suit;
    }

    /** The world snapshot in force: this client's own engine, or the owner's wire feed. */
    public static WorldSnapshot worldSnap() {
        return snap;
    }

    /** A raw lump from the local WAD (null if absent/no WAD): remote sfx playback. */
    public static byte[] wadLump(String name) {
        final WadFile w = wad;
        return w != null ? w.lumpBytes(name) : null;
    }

    /** The shared level's lump name, if someone else's world is up (for suit warps). */
    public static String remoteMapName() {
        return remoteName;
    }

    /** Packed lightmap coordinates for a player standing inside the level: the sector's
     * light as block light and no sky contribution, or -1 when outside a level. */
    public static int levelLightCoords(double wx, double wy, double wz) {
        if (map == null || !insideLevel(wx, wy, wz)) {
            return -1;
        }
        final int sec = map.sectorAt(worldToDoomX(wx), worldToDoomY(wz));
        final int block = Math.max(0, Math.min(15, lightOf(sec) * 15 / 255));
        return block << 4;
    }

    /** The local engine quit or crashed. Tears the shared level down for everyone; without
     * this, each client's copy of the level would stay standing. */
    public static void engineQuit() {
        clearRemoteLevel();
        drop();
        // The level is gone, so return to the position held before entering the level
        // dimension. A no-op for a player who never left the overworld, such as a suit
        // engine running without /load.
        leaveLevelDim(Minecraft.getInstance());
    }

    /** Spectator tick: raises the level another client announced and drives it from that
     * client's snapshot feed, which carries sector motion, light levels and objects. A local
     * suit engine, if one booted, supplies only the HUD. */
    private static void remoteTick(Minecraft mc, DoomHost ownHost, Path iwadPath) {
        suit = ownHost != null ? ownHost.worldSnapshot() : null;
        if (remoteName.equals(failedMap)) {
            return;
        }
        if (!remoteName.equals(mapName)) {
            try {
                loadCommon(mc, remoteName, iwadPath,
                    new double[]{remoteOx, remoteOy, remoteOz});
                announcedLevel = false; // spectating: drop any earlier ownership claim
                LOGGER.info("LatteWorld: raised REMOTE {} at ({}, {}, {})", remoteName,
                    (int) remoteOx, (int) remoteOy, (int) remoteOz);
            } catch (Exception e) {
                LOGGER.error("LatteWorld: remote level {} failed to load", remoteName, e);
                drop();
                failedMap = remoteName;
                return;
            }
        }
        // The map is raised now: move the guest into the level dimension (once), so they
        // stand in the same void world as the owner and every other co-op player
        if (guestEnterPending && map != null && mc.player != null) {
            final double[] start = playerStartWorld();
            if (start != null) {
                guestEnterPending = false;
                guestDeliveredMap = remoteName;
                // The physics keyframes still describe the previous map, or the overworld.
                com.blackwithersteve.lattedoom.play.DoomMovement.forceReseed();
                enterLevelDim(mc, start[0], start[1] + 0.1, start[2]);
            }
        }
        final WorldSnapshot rs = remoteSnap;
        if (rs != null) {
            snap = rs; // the owner's feed drives the same heights/lights/objects path
        }
        playMode = false; // the owner's engine holds this client's presence, not a mirror
        if (ownHost != null) {
            weaponInput(mc, ownHost); // the suit still fires through the local engine
            ownHost.drainSounds();    // suit sfx are muted while spectating
        }
        com.blackwithersteve.lattedoom.play.DoomCombat.tick(mc, suit);
        com.blackwithersteve.lattedoom.play.MinecraftCombat.tickArrows(mc);
        presenceTick(mc, rs);
    }

    // Presence upstream: while inside a shared level this client streams its body and
    // inputs to the owner's engine, where it occupies one of players[1..3] and is a full
    // participant in that engine's simulation.
    private static double presX = Double.NaN, presY;
    // This client's mirrored body position from the previous tick, used to detect teleports
    private static double lastRbX = Double.NaN, lastRbY;
    private static boolean remoteUseQueued;

    /** R pressed while the world is someone else's: ship use upstream this tick. */
    public static void queueRemoteUse() {
        remoteUseQueued = true;
    }

    public static boolean worldIsRemoteNow(Minecraft mc) {
        return remoteName != null && remoteOwner != null && mc.player != null
            && !remoteOwner.equals(mc.player.getUUID());
    }

    private static void presenceTick(Minecraft mc, WorldSnapshot rs) {
        if (mc.player == null || map == null
            || !insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
            presX = Double.NaN;
            lastRbX = Double.NaN;
            remoteUseQueued = false;
            return;
        }
        final double dx = worldToDoomX(mc.player.getX());
        final double dy = worldToDoomY(mc.player.getZ());
        final double dh = worldToDoomH(mc.player.getY());
        // Teleporter follow: the owning engine moved this client's body. Same
        // speed-independent test as the host path: network lag makes the remote body
        // trail the player's own movement, so an absolute distance check triggers constantly
        // for a fast-moving player. A real teleport moves the body while the player does not.
        if (rs != null && rs.rbMobjId != null && !Double.isNaN(presX)) {
            double bx = Double.NaN, by = 0, bz = 0;
            final java.util.UUID me = mc.player.getUUID();
            find:
            for (int i = 0; i < rs.rbMobjId.length; i++) {
                if (rs.rbUuidMost[i] == me.getMostSignificantBits()
                    && rs.rbUuidLeast[i] == me.getLeastSignificantBits()) {
                    for (int m = 0; m < rs.mobjCount; m++) {
                        if (rs.mId[m] == rs.rbMobjId[i]) {
                            bx = rs.mx[m];
                            by = rs.my[m];
                            bz = rs.mz[m];
                            break find;
                        }
                    }
                    break;
                }
            }
            if (!Double.isNaN(bx) && !Double.isNaN(lastRbX)) {
                final double bodyJump = Math.hypot(bx - lastRbX, by - lastRbY);
                final double playerStep = Math.hypot(dx - presX, dy - presY);
                if (bodyJump > 128 && bodyJump > playerStep * 3.0 + 96) {
                    // Same re-seed as the owner's own follow: the physics keyframes and the
                    // glued sector belong to where this guest left, not where they arrived.
                    com.blackwithersteve.lattedoom.play.DoomMovement.forceReseed();
                    teleportPlayer(mc, doomToWorldX(bx), doomToWorldH(bz), doomToWorldZ(by));
                    presX = Double.NaN;
                    lastRbX = Double.NaN;
                    remoteUseQueued = false;
                    return; // next tick sends from the destination
                }
            }
            lastRbX = bx;
            lastRbY = by;
        }
        final java.util.List<int[]> crossed = Double.isNaN(presX)
            ? java.util.List.of() : collectCrossings(presX, presY, dx, dy);
        int buttons = 0;
        if (fireHeld || (marineForm && mc.options.keyAttack.isDown()
            && mc.gui.screen() == null)) {
            buttons |= 1;
        }
        if (remoteUseQueued) {
            buttons |= 2;
            remoteUseQueued = false;
        }
        final int slot = marineForm ? mc.player.getInventory().getSelectedSlot() : -1;
        com.blackwithersteve.lattedoom.net.LatteNet.sendPresence(dx, dy, dh,
            -mc.player.getYRot() - 90.0, buttons, slot,
            (int) Math.ceil(mc.player.getHealth() * 5.0f),
            crossed.toArray(new int[0][]));
        presX = dx;
        presY = dy;
    }

    /** Owner side: a guest's presence packet arrived. Hands it to the engine and charges
     * their Minecraft health with whatever damage the engine dealt since the last packet. */
    public static void acceptPresence(java.util.UUID who, double x, double y, double z,
                                      double angleDeg, int buttons, int slot,
                                      int healthMc, int[][] crossings) {
        final Minecraft mc = Minecraft.getInstance();
        final var host = com.blackwithersteve.lattedoom.LatteDoomClient.host();
        if (host == null || !announcedLevel) {
            return;
        }
        host.acceptPresence(who, x, y, z, angleDeg, buttons, slot, healthMc, crossings);
        final var rb = host.remoteBodies().get(who);
        if (rb != null) {
            final int dmg = rb.pendingDamage.getAndSet(0);
            final int heal = rb.pendingHeal.getAndSet(0);
            if (dmg > 0 || heal > 0) {
                // Routed through the server so the level owner can damage any player
                com.blackwithersteve.lattedoom.net.LatteNet.sendPlayerDamage(who, dmg, heal, 0, 0, false);
            }
        }
    }

    /** The engine's map as a WAD lump name: E1M1-style for DOOM 1 WADs, MAP01-style for
     * DOOM 2 / commercial WADs (probe the WAD itself: the engine plays both). */
    private static String engineMapName(Path iwadPath, int episode, int map) {
        final String exmy = "e" + episode + "m" + map;
        try {
            if (wad == null) {
                wad = openWad(iwadPath);
            }
            return wad.hasLump(exmy) ? exmy
                : String.format(java.util.Locale.ROOT, "map%02d", map);
        } catch (Exception e) {
            return exmy;
        }
    }

    /** Parse the engine's current map from the same WAD file the engine booted, bake the mesh. */
    private static void load(Minecraft mc, String name, Path iwadPath) throws Exception {
        loadCommon(mc, name, iwadPath, null);
    }

    private static void loadCommon(Minecraft mc, String name, Path iwadPath,
                                   double[] forcedOrigin) throws Exception {
        if (wad == null) {
            wad = openWad(iwadPath);
        }
        if (sprites == null) {
            sprites = SpriteSet.load(wad);
            LOGGER.info("LatteWorld: sprite table has {} frames", sprites.size());
        }
        // Composite and register the WAD's textures. This runs on the client tick, which is
        // the safe upload slot between frames; uploading during the deferred render phase is
        // not.
        DoomRuntimeTextures.init();
        DoomRuntimeTextures.ensureLoaded(wadId());

        map = DoomMap.loadFromWad(wad, name);
        tri = SectorTriangulator.triangulate(map);
        for (String issue : tri.issues()) {
            LOGGER.info("LatteWorld [{}]: {}", name, issue);
        }
        cx = (map.minX + map.maxX) / 2.0;
        cy = (map.minY + map.maxY) / 2.0;
        int minFloor = Integer.MAX_VALUE;
        int maxCeil = Integer.MIN_VALUE;
        for (DoomMap.Sector sec : map.sectors) {
            minFloor = Math.min(minFloor, sec.floorH());
            maxCeil = Math.max(maxCeil, sec.ceilH());
        }
        minFloorH = minFloor;
        maxCeilH = maxCeil;
        // The level is anchored to a fixed 512-block grid cell rather than to the player's
        // exact position, so two clients near each other raise it in the same place. The
        // height is a constant rather than derived from the player's own: deriving it buries
        // the level when the player stands on low terrain, and a constant guarantees every
        // client agrees. The small offset keeps the level's planes off the block grid. A
        // level owned by another client uses their announced origin verbatim instead.
        originEpoch++; // state stored in origin-relative units is invalid past this point;
                       // holders must re-seed against the new origin
        if (forcedOrigin != null) {
            originX = forcedOrigin[0];
            originY = forcedOrigin[1];
            originZ = forcedOrigin[2];
        } else {
            final double eps = 0.0071;
            final var p = mc.player;
            final double ax = p != null ? Math.floor(p.getX() / 512.0) * 512.0 + 256.0 : 0.0;
            final double az = p != null ? Math.floor(p.getZ() / 512.0) * 512.0 + 256.0 : 0.0;
            originX = ax + eps;
            originY = 128.0 + 24.0 - minFloor / UNITS + eps;
            originZ = az + eps;
        }

        final Map<Integer, Map<String, float[]>> g = new HashMap<>();
        final Map<Integer, double[]> bounds = new HashMap<>();
        for (int i = 0; i < map.sectors.size(); i++) {
            final Map<String, float[]> one = LatteMesh.buildFor(map, tri, i, cx, cy);
            if (!one.isEmpty()) {
                g.put(i, one);
                bounds.put(i, computeBounds(one));
            }
        }
        groups = g;
        sectorBounds = bounds;
        LatteSectorBuffers.dispose(); // a new map: the persistent buffers rebuild lazily next frame
        // (the /doomwarp start-delivery is consumed in clientTick: it must fire even when
        // the map was already raised, e.g. warping to the same level again)
        // sector adjacency for moving-neighbor rebakes (a wall in N faces M's opening)
        final Map<Integer, Set<Integer>> adj = new HashMap<>();
        for (DoomMap.Line l : map.lines) {
            if (l.frontSector() >= 0 && l.backSector() >= 0 && l.frontSector() != l.backSector()) {
                adj.computeIfAbsent(l.frontSector(), k -> new HashSet<>()).add(l.backSector());
                adj.computeIfAbsent(l.backSector(), k -> new HashSet<>()).add(l.frontSector());
            }
        }
        neighbors = new int[map.sectors.size()][];
        for (int i = 0; i < neighbors.length; i++) {
            final Set<Integer> ns = adj.get(i);
            neighbors[i] = ns == null ? new int[0] : ns.stream().mapToInt(Integer::intValue).toArray();
        }
        // Per-sector linedef index (same one-time cost as neighbors, mirrors that lifetime)
        final List<List<Integer>> byline = new ArrayList<>(map.sectors.size());
        for (int i = 0; i < map.sectors.size(); i++) {
            byline.add(new ArrayList<>());
        }
        for (int li = 0; li < map.lines.size(); li++) {
            final DoomMap.Line l = map.lines.get(li);
            final int fs = l.frontSector(), bs = l.backSector();
            if (fs >= 0 && fs < byline.size()) {
                byline.get(fs).add(li);
            }
            if (bs >= 0 && bs < byline.size() && bs != fs) {
                byline.get(bs).add(li);
            }
        }
        sectorLines = new int[map.sectors.size()][];
        for (int i = 0; i < sectorLines.length; i++) {
            sectorLines[i] = byline.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        prevFloor = prevCeil = curFloor = curCeil = null; // keyframes re-seed on first frame
        lastTic = -1;
        // Sector indices from the previous map are meaningless against this one's arrays,
        // and syncHeights would re-bake them straight into an out-of-bounds read.
        dirtyRebake.clear();
        mapName = name;
        failedMap = null;
        if (forcedOrigin == null && warpedIn) {
            // This client's engine owns a level the player has entered: announce it so that
            // other players raise the same level.
            // A suit boot (warpedIn false) runs a map only for the weapon and HUD, so it must
            // not announce: every other client would raise that map.
            com.blackwithersteve.lattedoom.net.LatteNet.sendLevelUp(name, originX, originY, originZ);
            announcedLevel = true;
            com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", "raised " + name
            + " origin=(" + Math.round(originX) + ", " + Math.round(originY) + ", "
            + Math.round(originZ) + ")");
        LOGGER.info("LatteWorld: raised {} ({} sectors, {} baked groups) at ({}, {}, {})",
                name, map.sectors.size(), g.size(), (int) originX, (int) originY, (int) originZ);
        }
    }

    /**
     * Mirror the engine's live sector heights into the mesh: any sector the engine moved,
     * such as a door or a lift, is re-baked, plus every neighbour across a shared
     * line (their upper/lower wall strips stretch with the opening).
     */
    // Floor texture changes, from the specials that raise a floor and change its texture.
    // The engine reports each sector's floor texture as an index, and those indices are
    // paired with the map's flat names once at load, so a sector can be re-textured when its
    // index moves to another known flat. Local only: the network snapshot omits them.
    private static short[] lastFloorPic;
    private static Map<Integer, String> floorPicNames;

    private static void syncHeights(WorldSnapshot s) {
        final int n = Math.min(s.floorH.length, map.sectors.size());
        final boolean pics = !s.remote && s.floorPic != null && s.floorPic.length >= n;
        if (pics && (lastFloorPic == null || lastFloorPic.length != n)) {
            lastFloorPic = new short[n];
            floorPicNames = new HashMap<>();
            for (int i = 0; i < n; i++) {
                lastFloorPic[i] = s.floorPic[i];
                floorPicNames.putIfAbsent((int) s.floorPic[i], map.sectors.get(i).floorFlat());
            }
        }
        Set<Integer> changed = null;
        for (int i = 0; i < n; i++) {
            // A single timeline for everything that must agree. Collision heights follow
            // the rendered endpoint, one tic behind the engine, which is the same delayed
            // pair the sector mesh and the rider update interpolate between. Using the
            // newest engine heights here instead puts the physics a full tic ahead of the
            // drawn floor: at every start, stop and reversal of a moving sector the rider
            // and the collision floor disagree, the grounded state flickers, the floor
            // association is dropped and the ride stutters. Delaying all three keeps the
            // drawn floor, the collision floor and the rider consistent at every instant,
            // at the cost of one tic of latency, which is not perceptible.
            final double df = prevFloor != null && i < prevFloor.length ? prevFloor[i] : s.floorH[i];
            final double dc = prevCeil != null && i < prevCeil.length ? prevCeil[i] : s.ceilH[i];
            final int f = (int) Math.floor(df);
            final int c = (int) Math.floor(dc);
            boolean sectorChanged = false;
            if (f != map.floorNow(i) || c != map.ceilNow(i)) {
                // The equivalent of P_ChangeSector: plane movement carries everything standing
                // on the plane in the same operation. A player glued to this sector is moved by
                // the floor's exact delta, at any speed and in both directions, so a fast lift
                // cannot outrun gravity. Spectators ride the wire heights the same way.
                if (i == com.blackwithersteve.lattedoom.play.DoomMovement.gluedSector()) {
                    com.blackwithersteve.lattedoom.diag.DoomDiag.rec("carry", String.format(
                        "sector=%d floor %d -> %d (delta %d)", i, map.floorNow(i), f,
                        f - map.floorNow(i)));
                    com.blackwithersteve.lattedoom.play.DoomMovement.rideFloor(f - map.floorNow(i));
                }
                map.setLive(i, f, c);
                sectorChanged = true;
            }
            if (pics && s.floorPic[i] != lastFloorPic[i]) {
                lastFloorPic[i] = s.floorPic[i];
                final String name = floorPicNames.get((int) s.floorPic[i]);
                if (name != null && !name.equals(map.floorFlatNow(i))) {
                    map.setFloorFlat(i, name);
                    sectorChanged = true;
                }
            }
            if (sectorChanged) {
                if (changed == null) {
                    changed = new HashSet<>();
                }
                changed.add(i);
            }
        }
        if (changed == null) {
            changed = Set.of();
        }
        // Pressed switches: adopt the engine's flipped lines and re-bake only the sectors on
        // both sides of those walls. Reverts on reusable buttons fall out of the same diff.
        if (s.switchedLines != null) {
            final Set<Integer> now = new HashSet<>();
            for (int li : s.switchedLines) {
                now.add(li);
            }
            if (!now.equals(map.switchedLines)) {
                final Set<Integer> delta = new HashSet<>(now);
                delta.addAll(map.switchedLines); // presses and reverts both rebake
                map.switchedLines.clear();
                map.switchedLines.addAll(now);
                for (int li : delta) {
                    if (li >= 0 && li < map.lines.size()) {
                        final DoomMap.Line l = map.lines.get(li);
                        if (l.frontSector() >= 0) {
                            dirtyRebake.add(l.frontSector());
                        }
                        if (l.backSector() >= 0) {
                            dirtyRebake.add(l.backSector());
                        }
                    }
                }
            }
        }
        // Deferred rebuild. While a sector is moving its static geometry is not drawn at
        // all, since the interpolated version is used instead, so rebuilding it and its
        // neighbours on every tic only costs render-thread time and produces a periodic
        // frame hitch for each moving sector. Mark them here and rebuild once the sector
        // stops. The neighbour lookup uses the precomputed table rather than scanning
        // every linedef.
        for (int i : changed) {
            dirtyRebake.add(i);
            if (neighbors != null && i < neighbors.length) {
                for (int nb : neighbors[i]) {
                    dirtyRebake.add(nb);
                }
            }
        }
        if (dirtyRebake.isEmpty()) {
            return;
        }
        final Set<Integer> movingNow = movingSectors();
        final Set<Integer> rebake = new HashSet<>();
        for (final java.util.Iterator<Integer> it = dirtyRebake.iterator(); it.hasNext(); ) {
            final int i = it.next();
            if (!movingNow.contains(i)) {
                rebake.add(i);
                it.remove();
            }
        }
        for (int i : rebake) {
            final Map<String, float[]> one = LatteMesh.buildFor(map, tri, i, cx, cy);
            if (one.isEmpty()) {
                groups.remove(i);
                if (sectorBounds != null) {
                    sectorBounds.remove(i);
                }
            } else {
                groups.put(i, one);
                if (sectorBounds != null) {
                    sectorBounds.put(i, computeBounds(one)); // keep the cull box in step
                }
            }
            if (LatteSectorBuffers.ENABLED) {
                LatteSectorBuffers.rebuild(i, one); // keep this sector's persistent GPU buffer current
            }
        }
    }

    /**
     * Draw the level's persistent GPU buffers. Called from the {@code LevelRenderer.render}
     * tail mixin, which supplies the camera from the render state: the view rotation matrix
     * and camera position, independent of the deferred pipeline's global modelview. Reading
     * {@code pose.last().pose()} in the submit hook instead yields no rotation, leaving the
     * geometry fixed as the camera turns. No-op unless the persistent path is enabled.
     */
    public static void drawPersistent(org.joml.Matrix4f viewRotation, double cx, double cy,
                                      double cz,
                                      net.minecraft.client.renderer.culling.Frustum frustum) {
        if (!LatteSectorBuffers.ENABLED || !warpedIn || groups == null || viewRotation == null) {
            return;
        }
        // Camera-relative: rotate by the view, then translate the level
        // origin by (origin - cam). Vertices are baked in mesh-local coords.
        final org.joml.Matrix4f mv = new org.joml.Matrix4f(viewRotation).translate(
            (float) (originX - cx), (float) (originY - cy), (float) (originZ - cz));
        final WorldSnapshot s = snap;
        LatteSectorBuffers.draw(mv, s != null ? s.tic : 0, frustum, sectorBounds,
            movingSectors(), originX, originY, originZ);
    }

    /** Release the mesh once the engine leaves the level. Textures stay registered per WAD. */
    private static void drop() {
        LatteSectorBuffers.dispose();
        if (announcedLevel) {
            // The shared level is withdrawn for everyone, on a reload, quit or world change.
            com.blackwithersteve.lattedoom.net.LatteNet.sendLevelDown();
            announcedLevel = false;
        }
        map = null;
        tri = null;
        mapName = null;
        groups = null;
        sectorBounds = null;
        snap = null;
        neighbors = null;
        sectorLines = null;
        dirtyRebake.clear();
        baseNanos = 0;
        ticOfPrev2 = ticOfPrev = 0;
        prevFloor = prevCeil = curFloor = curCeil = null;
        lastTic = -1;
        lastFloorPic = null; // rebuild the floorpic->name table for the next map
        floorPicNames = null;
        // warpedIn is deliberately not cleared here: drop() also runs during a /load reboot,
        // where the engine is briefly without a snapshot, and clearing it would blank the
        // incoming level. leaveLevelDim and clearRemoteLevel own the transition out of a
        // level; the null map and group state make a stale warpedIn harmless until then.
        lastMirrorX = Double.NaN; // stale mirror must not fake a teleport on the next level
        lastEngineX = Double.NaN;
    }

    // ---- renderer access (render thread, same client thread) ----

    static Map<Integer, Map<String, float[]>> groups() {
        return groups;
    }

    /** Live sector light (0-255) for vertex shading. The engine animates these; on a remote
     * shared level with no local engine, the WAD's authored light levels are used instead of
     * full brightness. */
    static int lightOf(int sector) {
        final WorldSnapshot s = snap;
        if (s != null && sector >= 0 && sector < s.light.length) {
            return s.light[sector];
        }
        final DoomMap m = map;
        if (m != null && sector >= 0 && sector < m.sectors.size()) {
            return Math.max(0, Math.min(255, m.sectors.get(sector).light()));
        }
        return 255;
    }

    static double originX() {
        return originX;
    }

    static double originY() {
        return originY;
    }

    static double originZ() {
        return originZ;
    }

    /**
     * Per rendered FRAME (render thread): advance the mover keyframes at the engine's own
     * 35 Hz: when a new snapshot tic appears, the previous targets become the previous
     * keyframe, the discrete cache re-bakes (collision-truth heights), and the wall clock
     * marks the tic's arrival so {@link #alpha()} can interpolate the glide between them.
     */
    // Object-position keyframes, the same prev -> cur pair the sector heights use. The
    // sprite pass and the thing collision both read the interpolated value, so a monster's
    // blocking box sits where its body is drawn. Testing the newest snapshot instead puts
    // the box the full render latency ahead of the sprite, which stops the player short of
    // a moving monster by up to a block.
    private static int thingTic = -1;
    private static Map<Integer, double[]> prevThing = new HashMap<>();
    private static Map<Integer, double[]> curThing = new HashMap<>();
    /** One tic at top speed covers about 30 map units, so a larger step is a teleport or a
     * respawn and snaps rather than interpolating. */
    private static final double THING_SNAP = 128.0;
    /** Scratch for {@link #thingDrawn}; the client thread is the only caller. */
    private static final double[] thingScratch = new double[3];

    private static void rollThingKeyframes(
            com.blackwithersteve.lattedoom.engine.WorldSnapshot s) {
        if (s.tic == thingTic) {
            return;
        }
        prevThing = curThing;
        curThing = new HashMap<>(Math.max(16, s.mobjCount * 2));
        for (int i = 0; i < s.mobjCount; i++) {
            curThing.put(s.mId[i], new double[]{s.mx[i], s.my[i], s.mz[i]});
        }
        thingTic = s.tic;
    }

    /** The drawn position of snapshot object {@code i}, written into {@code out} as
     * {x, y, z} in map units. An object with no previous keyframe, and a step large enough
     * to be a teleport, reports its current position unchanged. */
    public static void thingDrawn(com.blackwithersteve.lattedoom.engine.WorldSnapshot s,
                                  int i, double[] out) {
        out[0] = s.mx[i];
        out[1] = s.my[i];
        out[2] = s.mz[i];
        final double[] pv = prevThing.get(s.mId[i]);
        if (pv == null
            || Math.abs(pv[0] - out[0]) + Math.abs(pv[1] - out[1]) >= THING_SNAP) {
            return;
        }
        final double a = alpha();
        out[0] = pv[0] + (out[0] - pv[0]) * a;
        out[1] = pv[1] + (out[1] - pv[1]) * a;
        out[2] = pv[2] + (out[2] - pv[2]) * a;
    }

    static void renderSync() {
        final com.blackwithersteve.lattedoom.engine.WorldSnapshot s = snap;
        if (s == null || map == null || groups == null) {
            return;
        }
        final int n = Math.min(s.floorH.length, map.sectors.size());
        if (curFloor == null || curFloor.length != n) {
            prevFloor2 = new double[n];
            prevCeil2 = new double[n];
            prevFloor = new double[n];
            prevCeil = new double[n];
            curFloor = new double[n];
            curCeil = new double[n];
            for (int i = 0; i < n; i++) {
                prevFloor2[i] = prevFloor[i] = curFloor[i] = s.floorH[i];
                prevCeil2[i] = prevCeil[i] = curCeil[i] = s.ceilH[i];
            }
            lastTic = s.tic;
            ticOfPrev2 = ticOfPrev = ticOfCur = s.tic; // degenerate until the first roll
            baseNanos = 0;                  // clock re-anchors on the next roll
            rollThingKeyframes(s);
            return;
        }
        if (s.tic == lastTic) {
            traceMover();
            riderFollow(); // the platform is interpolated per frame, so is its rider
            return;
        }
        // Roll the pair and tag each keyframe with the engine tic it belongs to, since the
        // interpolation below is time-driven rather than arrival-driven. The rendered pair is
        // the two newest keyframes. Using the older pair leaves the renderer a full publish
        // behind with cur unused, which pins alpha at its cap and lurches at every roll.
        ticOfPrev2 = ticOfPrev;
        ticOfPrev = ticOfCur > 0 ? ticOfCur : lastTic;
        ticOfCur = s.tic;
        rollThingKeyframes(s);
        for (int i = 0; i < n; i++) {
            prevFloor2[i] = prevFloor[i];
            prevCeil2[i] = prevCeil[i];
            prevFloor[i] = curFloor[i];
            prevCeil[i] = curCeil[i];
            curFloor[i] = s.floorH[i];
            curCeil[i] = s.ceilH[i];
        }
        // A synthesised engine clock. Interpolating against the arrival times of
        // keyframes cannot be smooth, because the engine thread's tic production jitters
        // under thread scheduling and the render thread only observes arrivals at frame
        // boundaries; correcting per keyframe merely redistributes that jitter.
        //
        // Instead a continuous clock is maintained, anchored at a tic and a timestamp,
        // drift-corrected by 2 percent per keyframe and resynchronised outright when it
        // is more than three tics out, which also recovers after a pause. Geometry is then
        // rendered at a fixed latency behind the newest keyframe, so jitter within that
        // budget is absorbed entirely. The cost is roughly two tics, about 57 ms, of
        // latency on moving sectors, which is not perceptible.
        final long now = System.nanoTime();
        if (baseNanos == 0) {
            baseNanos = now;
            baseTic = s.tic;
        } else {
            final double err = (now - baseNanos) / TIC_NANOS + baseTic - s.tic;
            if (Math.abs(err) > 3.0) {
                baseNanos = now;
                baseTic = s.tic;
            } else {
                baseNanos += (long) (err * TIC_NANOS * 0.02);
            }
        }
        lastTic = s.tic;
        syncHeights(s); // discrete cache + collision truth, at the full 35Hz now
        traceMover();
        riderFollow();
    }

    /** Continuous engine time in tics, from the drift-corrected synthesized clock. */
    private static double engineTimeTics() {
        if (baseNanos == 0) {
            return lastTic;
        }
        return baseTic + (System.nanoTime() - baseNanos) / TIC_NANOS;
    }

    /** The smooth render clock in engine tics: for per-frame effects that follow DOOM
     * time (the marine camera's P_CalcHeight bob). */
    public static double renderTics() {
        return engineTimeTics();
    }

    /** One log line per frame while anything moves: the drawn height of the first moving
     * sector and the clock state, which records the motion curve for later inspection. */
    private static void traceMover() {
        if (curFloor == null) {
            return;
        }
        for (int i = 0; i < curFloor.length; i++) {
            if (prevFloor[i] != prevFloor2[i] || curFloor[i] != prevFloor[i]) {
                com.blackwithersteve.lattedoom.diag.DoomDiag.rec("mover", String.format(
                    "s=%d drawn=%.2f a=%.3f pair=[%.1f->%.1f] tics=[%d->%d] et=%.2f",
                    i, prevFloor[i] + (curFloor[i] - prevFloor[i]) * alpha(), alpha(),
                    prevFloor[i], curFloor[i], ticOfPrev, ticOfCur, engineTimeTics()));
                return; // one sector per frame is enough
            }
        }
    }

    /** The rider glides with the rendered platform: while glued to a sector whose
     * rendered pair is moving, the player's Y follows the same interpolation the mesh
     * uses, applied per frame rather than per keyframe; per-keyframe updates make the ride
     * visibly step. */
    private static void riderFollow() {
        final double wy = riddenSurfaceWorldY();
        if (Double.isNaN(wy)) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // The ride only applies while the level's rules do. This writes the player's Y
        // directly every frame, so without the same gate the rest of the movement code uses
        // it keeps pulling them onto a sector they are no longer standing on: after leaving
        // the level, while flying, or while riding a Minecraft vehicle. The glue is also
        // dropped here so the next frame has nothing to follow.
        if (!warpedIn || !inLevelDim(mc) || mc.player.isPassenger()
            || mc.player.getAbilities().flying
            || !insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
            com.blackwithersteve.lattedoom.play.DoomMovement.releaseGlue();
            return;
        }
        // Lock the rider to the platform mesh's own timeline. setPos alone is not enough: MC
        // renders the eye as getPosition(partialTick) = lerp(partialTick, yo, getY()): the
        // 20Hz render clock against a stale tic-start yo, a different clock than the 35Hz
        // alpha() the floor mesh is baked at, so the surface slid under the player every
        // frame. Pinning yo collapses that lerp so the eye sits exactly on the rendered floor.
        com.blackwithersteve.lattedoom.diag.DoomDiag.framePlayerY(mc.player.getY(), "pre-pin");
        com.blackwithersteve.lattedoom.diag.DoomDiag.rec("pin", String.format(
            "wy=%.3f playerY=%.3f gs=%d alpha=%.3f",
            wy, mc.player.getY(), com.blackwithersteve.lattedoom.play.DoomMovement.gluedSector(), alpha()));
        mc.player.setPos(mc.player.getX(), wy, mc.player.getZ());
        mc.player.yo = wy;
        com.blackwithersteve.lattedoom.play.DoomMovement.syncRiddenY(wy);
        com.blackwithersteve.lattedoom.diag.DoomDiag.framePlayerY(wy, "post-pin");
    }

    /**
     * World Y of the glued sector's rendered floor surface this frame: the exact
     * {@code prev2 -> prev @ alpha()} the platform mesh ({@link #bakeInterp}) is baked at: or
     * NaN when the local player isn't standing on a sector that is currently gliding. Both
     * {@link #riderFollow} (per frame) and the DoomMovement physics tick (once per client tick)
     * read this so they write the same vertical; otherwise the per-tick physics height
     * (fresh-floor timeline) and the per-frame mesh height (alpha timeline) fight and the ride
     * pops once per tic.
     */
    public static double riddenSurfaceWorldY() {
        final int gs = com.blackwithersteve.lattedoom.play.DoomMovement.gluedSector();
        if (gs < 0 || prevFloor == null || gs >= prevFloor.length
            || (curFloor[gs] == prevFloor[gs] && prevFloor[gs] == prevFloor2[gs])) {
            return Double.NaN;
        }
        return doomToWorldH(prevFloor[gs] + (curFloor[gs] - prevFloor[gs]) * alpha());
    }

    private static final double TIC_NANOS = 1.0e9 / 35.0;
    // The synthesized engine clock + tic-tagged keyframe pair
    private static long baseNanos;
    private static int baseTic;
    private static int ticOfPrev2, ticOfPrev, ticOfCur;

    /** Render latency behind the newest keyframe, in tics; this is the jitter budget. The
     * per-tic publish ({@code Engine.TIC_TAP}) delivers a steady 35 Hz feed, so 1.5 tics,
     * about 43 ms, is sufficient. */
    private static final double RENDER_LATENCY_TICS = 1.5;

    /** Progress through the newest tagged pair (prev -> cur) at the synthesised engine time
     * minus the fixed latency. Being time-driven, arrival jitter within the budget has no
     * effect. The 1.15 soft cap allows brief extrapolation rather than a stall. */
    static double alpha() {
        if (ticOfCur <= ticOfPrev) {
            return 1.0;
        }
        final double t = engineTimeTics() - RENDER_LATENCY_TICS;
        return Math.max(0.0, Math.min(1.15,
            (t - ticOfPrev) / (double) (ticOfCur - ticOfPrev)));
    }

    /** Continuous engine time in tics (integer tic plus intra-tic phase), for anything that
     * must interpolate at the display frame rate, such as weapon bob. Effects that step per
     * tic, such as the 8-tic flat animations, keep using snap.tic. */
    static double ticTime() {
        return engineTimeTics();
    }

    /** Sectors gliding this frame (rendered pair differs) plus wall-sharing neighbors. */
    static Set<Integer> movingSectors() {
        if (curFloor == null) {
            return Set.of();
        }
        Set<Integer> moving = null;
        for (int i = 0; i < curFloor.length; i++) {
            // A sector needs per-frame interpolation the moment its freshest height differs
            // from the rendered pair (leading edge, cur != prev) through the last gliding tic
            // (trailing edge, prev != prev2). Testing only prev != prev2 lagged one tic behind
            // syncHeights' static rebake, so the first motion tic snapped the mesh straight to
            // the new height (then jumped back to start interpolating): the door/lift judder.
            if (curFloor[i] != prevFloor[i] || prevFloor[i] != prevFloor2[i]
                || curCeil[i] != prevCeil[i] || prevCeil[i] != prevCeil2[i]) {
                if (moving == null) {
                    moving = new HashSet<>();
                }
                moving.add(i);
                for (int nb : neighbors[i]) {
                    moving.add(nb);
                }
            }
        }
        return moving == null ? Set.of() : moving;
    }

    /** Bake one sector this frame at the interpolated height, one tic behind the engine
     * (prev2 -> prev), so the rendered pair is always complete and a late tic cannot stall a
     * sector mid-motion. The cost is 28 ms of mover latency. */
    static Map<String, float[]> bakeInterp(int sector, double a) {
        final LatteMesh.HeightFn hf = new LatteMesh.HeightFn() {
            public double floor(int s2) {
                return s2 < curFloor.length
                    ? prevFloor[s2] + (curFloor[s2] - prevFloor[s2]) * a
                    : map.floorNow(s2);
            }

            public double ceil(int s2) {
                return s2 < curCeil.length
                    ? prevCeil[s2] + (curCeil[s2] - prevCeil[s2]) * a
                    : map.ceilNow(s2);
            }
        };
        final int[] lines = (sectorLines != null && sector >= 0 && sector < sectorLines.length)
            ? sectorLines[sector] : null;
        return LatteMesh.buildForInterp(map, tri, sector, cx, cy, hf, lines);
    }

    // Sprite-pass access
    static com.blackwithersteve.lattedoom.engine.WorldSnapshot snap() {
        return snap;
    }

    static DoomMap mapRef() {
        return map;
    }

    static SpriteSet sprites() {
        return sprites;
    }

    static double cx() {
        return cx;
    }

    static double cy() {
        return cy;
    }

    private LatteWorld() {}
}
