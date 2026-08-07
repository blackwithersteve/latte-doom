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
 * The client-side raised level: whichever map the ENGINE is currently running materialises as
 * true geometry floating 24 blocks above wherever the player stood when it appeared. Everything
 * here is driven by {@link WorldSnapshot}s — the engine decides which level exists, what every
 * sector's floor/ceiling height is (doors/lifts move because IT moves them) and what every
 * sector's light level is (its own p_lights thinkers). This class only mirrors.
 *
 * All state is touched exclusively on the MC client thread (tick + render).
 */
public final class LatteWorld {

    private static final Logger LOGGER = LoggerFactory.getLogger("lattedoom");

    /**
     * The scale: DOOM units per Minecraft block, the single constant every transform uses.
     * It is chosen so a two-block-tall Minecraft player fits DOOM's spaces naturally. At
     * 28 units per block the 56-unit marine is exactly 2.0 blocks, doors are 2.6 to 4.6
     * blocks, and a 24-unit step is 0.86 blocks, which an untransformed player clears.
     */
    public static final double UNITS = 28.0;

    /** Cache id for the current IWAD+PWAD set. GENERATIONAL: /doomwad bumps it, so every
     * WAD-derived cache (merged wad, sprites, GPU textures keyed by this id) reloads
     * lazily from the NEW set instead of serving the old one. */
    private static int wadGeneration;

    public static String wadId() {
        return "iwad-g" + wadGeneration;
    }

    /** Bumped every time the level ORIGIN is redefined (load/reload). The marine
     * integrator keys its keyframes to this: a stale epoch means every stored
     * origin-relative coordinate is garbage and physics must re-seed at the player —
     * the flight recorder caught a suit-level load RE-BASING the origin mid-flight and
     * the integrator "moving" the standing player 491 blocks into the sky. */
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

    /** The NATIVE shell (menu/intermission/finale screens) needs the WAD's UI art even
     * before any level ever loaded — e.g. pressing M on a fresh client. Registers lazily
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
    /** Static sector triangulation (x,y only; heights applied at bake) — built once per map. */
    private static SectorTriangulator.Result tri;
    private static double cx, cy; // doom-space map centre, the mesh transform origin
    private static String mapName;        // "e5m1" — which level the mesh currently is
    private static String failedMap;      // a map that failed to load: don't retry every tick
    /** Per-sector baked quad batches (texture key -> packed 6-float vertices). */
    private static Map<Integer, Map<String, float[]>> groups;
    /** Per-sector mesh-local AABB {minX,minY,minZ,maxX,maxY,maxZ} — for frustum culling.
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
    /** Sectors whose STATIC bake is stale; re-baked when they stop moving (not per tic). */
    private static final Set<Integer> dirtyRebake = new HashSet<>();
    /** sector -> indices into map.lines of every linedef touching it (front OR back side).
     * Precomputed once at load so the per-frame mover bake ({@link #bakeInterp}) walks a
     * handful of lines instead of rescanning the whole level's linedefs every sector, every frame. */
    private static int[][] sectorLines;
    /** The level's vertical envelope (authored extremes), for the inside-the-level test. */
    private static int minFloorH, maxCeilH;
    // ---- mover interpolation: the engine's last THREE 35Hz keyframes per sector — the
    // RENDERED glide runs one tic behind (prev2 -> prev) so a late-arriving tic never
    // stalls a door mid-motion; collision keeps the freshest heights (cur) ----
    private static double[] prevFloor2, prevCeil2, prevFloor, prevCeil, curFloor, curCeil;
    private static int lastTic = -1;
    private static long ticSeenNanos;

    // WHO-MOVED-ME diagnostic: any >10-block jump in one tick gets logged with both
    // positions + dimension — catches every mover, including ones outside this class
    private static double diagPX = Double.NaN, diagPY, diagPZ;
    private static String diagDim = "";

    /** Called every END_CLIENT_TICK: latch/refresh/drop the level to match the engine. */
    public static void clientTick(Minecraft mc, DoomHost host, Path iwadPath) {
        com.blackwithersteve.lattedoom.diag.DoomDiag.tickPlayer(mc);
        // Safety net against falling forever in the floorless level dimension: no standing
        // level, or dropped far below any level envelope, returns the player to the
        // overworld. It must not fire while a level load is in flight, because the map is
        // legitimately absent for a few ticks and rescuing then ejects the player just
        // before the incoming level delivers them. Three states count as in flight: the
        // engine between levels after an exit, a start-delivery still pending, and an
        // engine that is still booting. A booting engine reports hadLevel() false, so the
        // between-levels test alone does not cover a warp reboot.
        final boolean loadInFlight = com.blackwithersteve.lattedoom.play.Session.deliveryInFlight()
            || (host != null && host.hadLevel() && host.isBetweenLevels())
            || (host != null && host.state() == com.blackwithersteve.lattedoom.engine.DoomHost.State.BOOTING);
        if (mc.player != null && inLevelDim(mc)
            && (map == null || mc.player.getY() < -64)
            && !loadInFlight) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", String.format(
                "void rescue -> leaveLevelDim (map=%s y=%.1f)", map != null,
                mc.player.getY()));
            leaveLevelDim(mc);
        } else if (mc.player != null && inLevelDim(mc) && map == null && loadInFlight) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("level", String.format(
                "void rescue held: delivery=%s booting=%s between=%s",
                com.blackwithersteve.lattedoom.play.Session.deliveryInFlight(),
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
        com.blackwithersteve.lattedoom.play.Session.observe(host, s != null,
            mc.player != null && inLevelDim(mc));
        // WORLD vs SUIT: when someone ELSE's engine owns the shared level, the world is
        // theirs (their snapshot feed) and our own engine — if any — is just our suit
        // (weapons/ammo/HUD). The owner (and solo play) takes the engine path unchanged.
        final boolean worldIsRemote = remoteName != null && remoteOwner != null
            && mc.player != null && !remoteOwner.equals(mc.player.getUUID());
        if (worldIsRemote && mc.level != null && iwadPath != null) {
            remoteTick(mc, host, iwadPath);
            return;
        }
        if (mc.level == null || iwadPath == null || s == null) {
            // LEVEL COMPLETED, not engine death: crossing an exit puts the engine into the
            // intermission tally (or the finale), where capture() returns null even though the
            // engine is alive and loading the next map. Dropping here yanks the geometry and
            // leaves the player standing in the empty void dim — the statue-freeze + fall-through.
            // Instead HOLD: keep the standing level, pin the player, and wait for the next
            // GS_LEVEL snapshot to deliver them to the new map's start.
            // hadLevel gate: a BOOTING engine (suit boot, /warp reboot) passes through
            // non-level states too — those must never trigger holds/advances (the
            // "/doommarine teleported me into the sky" bug: a stale warpedIn + boot-init
            // states noted an advance and delivered to the suit map's start)
            final boolean inDim = mc.player != null && inLevelDim(mc);
            if (host != null && host.hadLevel() && host.isBetweenLevels()
                && (com.blackwithersteve.lattedoom.play.Session.warped() || inDim)) {
                // SELF-HEAL (the E1M1-exit ejection): standing in the level dimension IS
                // a warped session — whatever cleared the flag mid-transition, restoring
                // it here keeps the hold + delivery chain alive instead of ejecting.
                if (!com.blackwithersteve.lattedoom.play.Session.warped()) {
                    com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level",
                        "between-levels with warped=false while in dim — flag restored");
                    com.blackwithersteve.lattedoom.play.Session.setWarped(true);
                }
                if (host.gamestateKind() == 3) {
                    // TITLE, not intermission: the finale ended (episode complete) or the
                    // player chose End Game in the engine menu. DEBOUNCED: a transient
                    // title read during a transition must never eject — only a state that
                    // HOLDS for half a second is a real adventure end.
                    if (++titleTicks >= 10) {
                        titleTicks = 0;
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
                // falling through here while INSIDE the dim is the ejection precursor —
                // name the failed gate on tape before anything tears down
                com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", String.format(
                    "null-snap fallthrough IN DIM: warped=%s host=%s hadLevel=%s between=%s",
                    com.blackwithersteve.lattedoom.play.Session.warped(), host != null, host != null && host.hadLevel(),
                    host != null && host.isBetweenLevels()));
                if (host != null && host.hadLevel()
                    && host.state() == com.blackwithersteve.lattedoom.engine.DoomHost.State.RUNNING) {
                    // a RUNNING engine that has carried a level and now shows a null
                    // snapshot is IN TRANSITION, whatever the gates above concluded —
                    // hold the world and the player rather than tearing down. Dropping
                    // here was the ejection: geometry gone, void rescue, overworld.
                    holdBetweenLevels(mc);
                    return;
                }
            }
            suit = null;
            drop(); // no engine of our own and nothing shared: no level stands
            return;
        }
        suit = s;
        snap = s;
        watchdog(mc, host, s);
        final String name = engineMapName(iwadPath, s.episode, s.map);
        if (name.equals(failedMap)) {
            if (com.blackwithersteve.lattedoom.play.Session.deliveryInFlight()) {
                // the advance's target is the very map that failed: its delivery can
                // never complete. Tear down cleanly (message, revert, overworld)
                // instead of holding the player forever over a level that will not
                // build — the silent variant of this was a confirmed deadlock.
                com.blackwithersteve.lattedoom.play.Session.abortDelivery("target map failed to build");
                if (mc.player != null) {
                    mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Level " + name.toUpperCase(java.util.Locale.ROOT)
                        + " failed to build. Returning to the world."));
                }
                leaveAndClear(mc);
            }
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
            // THE DOOM SHELL, advance lane: a NEW map went live while a DOOM shell screen
            // was up and it's no demo — the intermission paged into the next level (native
            // WI screen), or the debug framebuffer screen started a game. Minecraft is the
            // renderer: close the screen and warp to the fresh map's start. (The native
            // MENU's New Game warps itself via /load's path; title demos skip via s.demo.)
            if ((mc.gui.screen() instanceof com.blackwithersteve.lattedoom.LatteDoomScreen
                || mc.gui.screen() instanceof com.blackwithersteve.lattedoom.LatteIntermissionScreen)
                && !s.demo) {
                requestStartTeleport();
                mc.gui.setScreen(null);
            }
        }
        if (com.blackwithersteve.lattedoom.play.Session.consumeAdvance(s)) {
            // we held the player through the intermission; the next level now stands —
            // deliver them to its P1 start (the same path /doomwarp delivery uses).
            // The advance IS a warped session by definition: re-assert the flag so a
            // spurious mid-advance leave cannot get this delivery discarded as stale.
            com.blackwithersteve.lattedoom.play.Session.setWarped(true);
        }
        // HARD INVARIANT (the /doommarine mid-air hunt): a start-delivery may ONLY fire
        // into a WARPED session. A suit boot (warpedIn false) must never consume one —
        // whatever stale path set the flag, refusing here makes the teleport impossible.
        // EXCEPTION: the player physically standing in the level dimension IS a warped
        // session — restore the flag and deliver, else a mid-transition flag loss
        // strands them, ejecting them to the overworld on a level exit.
        if (com.blackwithersteve.lattedoom.play.Session.deliveryPending() && !com.blackwithersteve.lattedoom.play.Session.warped()) {
            if (mc.player != null && inLevelDim(mc)) {
                LOGGER.warn("delivery owed without a warped session but player IS in the"
                    + " level dim — flag restored, delivering");
                com.blackwithersteve.lattedoom.play.Session.setWarped(true);
            } else {
                LOGGER.warn("delivery owed WITHOUT warpedIn — dropped (stale flag)");
                com.blackwithersteve.lattedoom.play.Session.abortDelivery("no warped session to deliver into");
            }
        }
        if (com.blackwithersteve.lattedoom.play.Session.deliveryPending() && com.blackwithersteve.lattedoom.play.Session.warped() && mc.player != null && map != null) {
            final com.blackwithersteve.lattedoom.engine.WorldSnapshot cs = snap;
            // DEATH-RESTART WAIT: the engine hasn't actually reloaded yet — this snapshot
            // still belongs to the instance we died in. Keep the obligation armed and
            // deliver off the first fresh-instance snapshot instead of the death spot.
            if (com.blackwithersteve.lattedoom.play.Session.deliveryHeldFor(cs)) {
                return;
            }
            // /doomwarp delivery — fires even when the same map was already standing.
            // The level lives in its OWN void dimension: this both delivers the player to
            // the P1 start AND moves them out of the overworld, so nothing else renders.
            com.blackwithersteve.lattedoom.play.Session.consumeDelivery();
            // Deliver to where the engine actually spawned the player, never to a parsed
            // start. Boom maps carry extra player-1 starts in voodoo-doll closets, and
            // parsing the THINGS lump can land the player in one of those closets. Only
            // the engine knows which start is the real one.
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
                // start would fire every special between them.
                mirrorHoldTicks = 2;
                lastMirrorX = Double.NaN;
                lastEngineX = Double.NaN;
                com.blackwithersteve.lattedoom.play.DoomMovement.forceReseed();
                // The delivery is the transformation, and leaveAndClear() is its reverse.
                // There is no marine form outside a level.
                if (!marineForm && spritesReady()) {
                    setMarineForm(true);
                    if (mc.player != null) {
                        mc.player.refreshDimensions();
                    }
                }
            }
        }
        // ANNOUNCE RECONCILE: sendLevelUp is what sets the server's level OWNER, and the
        // server routes plain-Steve's Minecraft->DOOM hits (DoomHitC2S) to that owner. warpedIn
        // can turn true AFTER the map was already raised — a /doommarine suit-boot raises the
        // map with warpedIn=false (deliberately no announce), then /doomstart warps us in.
        // load() only announces on a map-NAME change, so that later warp-in never claimed the
        // level and every plain-Steve fist/arrow got dropped. Claim it here the moment we're
        // genuinely in-level (owner-only: remote spectators returned above), idempotent via
        // announcedLevel; a pure overworld suit-boot stays warpedIn=false and is skipped.
        if (com.blackwithersteve.lattedoom.play.Session.warped() && !announcedLevel && map != null && mapName != null) {
            com.blackwithersteve.lattedoom.net.LatteNet.sendLevelUp(mapName, originX, originY, originZ);
            announcedLevel = true;
            if (LatteMesh.bspMode() && mc.player != null) {
                // the experimental renderer must never be silently on: it once sat
                // enabled across days of play and its known divergences read as bugs
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6bsp mesh:§f experimental renderer active. §e/bsp§f turns it off."));
            }
        }
        // (height sync moved to renderSync(): sampling the 35Hz engine on the 20Hz client
        // tick made doors step unevenly — the renderer advances keyframes per FRAME instead)

        // Automatic possession: inside the level the engine sees the Minecraft player, with
        // no command needed. Demo playback is excluded, because spectating must not stomp
        // the recorded player. This mirrors the Minecraft player in, detects special-line
        // crossings along the movement (fired through the engine's own handler), and honours
        // teleports coming back the other way, where the engine has moved the mobj and the
        // Minecraft player must snap to the destination.
        playMode = mc.player != null && mapName != null && map != null
            && s != null && !s.demo
            && insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (playMode && mc.player != null && mapName != null) {
            final com.blackwithersteve.lattedoom.engine.WorldSnapshot s2 = snap;
            // AUTHORITATIVE TELEPORT FOLLOW: the engine counts every real teleport of the
            // local player. Count moved -> snap, whatever the distance (the old >128-unit
            // heuristic missed short hops: "you see the teleport animation but you don't
            // teleport"). A NEW level instance adopts the count without firing.
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
                        // vanilla turns you to the destination's facing — so do we, and
                        // you arrive at a dead stop (silent Boom teleports technically
                        // carry rotated momentum; disclosed compromise: they stop too)
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
            // start-delivery just fired: the server teleport may still be settling — one
            // mirror write of the old spot (fresh epoch!) would clobber the spawn again
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
                // TELEPORT detection, speed-independent: a real DOOM teleporter jumps the
                // engine mobj a long way in ONE step WHILE the player barely moved. Fast
                // walking moves both together (the mobj trails the player), so the OLD
                // absolute ">64 from last mirror" check false-fired during sprints and
                // yanked the player back (and spammed the log with /tp). Compare the
                // engine mobj's own jump against the player's own step instead.
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

            // the bill for the engine's hits: DOOM hit points -> MC hearts at the /5 scale,
            // no mercy frames (DOOM has none); medikits/soulspheres heal hearts back.
            // Creative/spectator shrug it all off like they shrug everything.
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
                // marine form: DOOM's own thrust, straight into the momentum integrator
                com.blackwithersteve.lattedoom.play.DoomMovement.damageThrust(
                    knock[0] * knock[2], knock[1] * knock[2]);
            }
            if ((dmg > 0 || heal > 0)
                && !mc.player.getAbilities().invulnerable && !mc.player.isSpectator()) {
                // via the SERVER, not the integrated-server shortcut: on a LAN GUEST that
                // shortcut is null and silently ate all engine damage
                double kx = 0, kz = 0;
                if (!marineForm && dmg > 0 && knock != null) {
                    kx = knock[0] * 0.4; // doom dir -> world axes: wx = dx, wz = -dy
                    kz = -knock[1] * 0.4;
                }
                // The server refuses a bill over 1000 outright rather than clamping it, so
                // an overkill hit must be capped here or it deals nothing. A telefrag bills
                // ~10000 in one packet (TNT MAP30's pad grid teleports onto the voodoo
                // doll); 1000 is five times a full health bar, so the cap changes no
                // outcome, only keeps the packet inside what the server accepts.
                com.blackwithersteve.lattedoom.net.LatteNet.sendPlayerDamage(
                    mc.player.getUUID(), Math.min(dmg, 1000), Math.min(heal, 1000), kx, kz,
                    shieldCovers(mc, dmg, knock));
            }
            // STEVE SCAVENGING (M-CROSSOVER slice A): the engine consumed doom items a
            // PLAIN player walked over — convert each via pickups.properties and let the
            // server hand out the Minecraft side (heal/food/items).
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
            // MINECRAFT-side damage on a MARINE (mobs, cactus, another player's sword)
            // never passes the engine, so the suit didn't flash or grunt. Any heart drop
            // beyond what the engine billed this window is Minecraft's doing — inject it
            // as pain (damagecount + plpain). Engine hits carry a short-lived credit so
            // their own async heart-drop doesn't double-flash.
            final int hpNow = (int) Math.ceil(mc.player.getHealth() * 5.0f);
            engineDmgCredit = Math.min(400, engineDmgCredit + dmg);
            if (lastHpSeen < 0) {
                // first tick in play mode: SEED, never compare — the old fixed 200 seed
                // read a fresh player's 100 as a 100-point hit, so every first level
                // entry opened with a full-red flash and the big pain grunt
                lastHpSeen = hpNow;
            }
            if (marineForm && hpNow < lastHpSeen) {
                final int drop = lastHpSeen - hpNow;
                final int covered = Math.min(drop, engineDmgCredit);
                engineDmgCredit -= covered;
                if (drop - covered > 0) {
                    host.requestPlayerPain(drop - covered);
                }
            }
            engineDmgCredit -= engineDmgCredit / 5; // stale credit fades fast
            lastHpSeen = hpNow;
        } else {
            host.setPlayerMirror(false, 0, 0, 0, 0, 0L);
            lastMirrorX = Double.NaN;
            lastEngineX = Double.NaN;
            lastHpSeen = -1; // re-seed on the next play-mode entry (warp, restart, load)
        }
        // The death law. Dying inside the level marks the death, and the moment Minecraft
        // respawns the player the map restarts fresh: the engine's own InitNew forces the
        // PST_REBORN kit of fist, pistol and 50 bullets exactly as vanilla single-player
        // does, and the start-delivery drops the player at the level's P1 start. Without
        // this the player is left in the overworld with a live engine and no starter kit.
        if (mc.player != null && com.blackwithersteve.lattedoom.play.Session.warped()) {
            // The footprint test alone missed deaths at odd positions — a telefrag lands
            // the player inside the voodoo doll's closet, and a fall can end outside the
            // walkable box. The level dimension exists only for level sessions, so any
            // death inside it is the level's death and must restart the map, wherever the
            // body ended up.
            if (mc.player.isDeadOrDying()
                && (mc.player.level().dimension().equals(
                        com.blackwithersteve.lattedoom.net.LatteNet.DOOM_LEVEL_DIM)
                    || insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ()))) {
                com.blackwithersteve.lattedoom.play.Session.noteDeath();
            } else if (com.blackwithersteve.lattedoom.play.Session.deathPending() && !mc.player.isDeadOrDying()) {
                com.blackwithersteve.lattedoom.play.Session.clearDeath();
                host.requestMapRestart();
                // deliver to the reborn map's start — but the restart happens on the
                // ENGINE thread: hold until a snapshot from a NEW level instance
                // arrives, or the player lands at the death spot off a pre-restart one
                com.blackwithersteve.lattedoom.play.Session.requestDelivery();
                com.blackwithersteve.lattedoom.play.Session.holdDeliveryUntilAfter(snap != null ? snap.levelEpoch : 0);
            }
        }
        // The marine's link to the engine works anywhere the engine runs: weapon switching
        // and the trigger are not tied to standing inside the level, so the marine keeps
        // the gun in the overworld.
        weaponInput(mc, host);
        com.blackwithersteve.lattedoom.play.DoomCombat.tick(mc, suit); // DOOM guns vs MC mobs/players
        com.blackwithersteve.lattedoom.play.MinecraftCombat.tickArrows(mc); // MC arrows vs demons
        for (String m : host.drainMessages()) {
            LatteHud.pushMessage(m); // queued by the engine tic, never lost to sampling
        }
        final java.util.List<int[]> sounds = host.drainSounds();
        if (announcedLevel) {
            com.blackwithersteve.lattedoom.net.LatteNet.sendSnap(s); // the world feed for spectators
            com.blackwithersteve.lattedoom.net.LatteNet.sendSounds(sounds); // and its soundscape
        }
    }

    private static boolean fireWas;
    private static boolean tapFire;
    private static int lastSlot = -1;
    private static final boolean[] hotbarWasDown = new boolean[7];
    private static int lastHpSeen = -1; // -1 = unseeded (first play-mode tick seeds it)
    private static int engineDmgCredit;

    /** A left-click landed (via the cancelled vanilla attack): pull the DOOM trigger
     * even if the button is already up by the time the tick samples it. */
    public static void tapFire() {
        tapFire = true;
    }

    /**
     * WEAPON SWITCHING (marine form): hotbar slots 1-7 are the arsenal — a slot change
     * posts the DOOM number key, so scroll-wheel cycling works naturally and the engine
     * runs its own weapon-change logic (owned? has ammo?). FIRE: the attack key forwards
     * into the engine's event queue; G_BuildTiccmd turns it into BT_ATTACK and the engine
     * fires YOUR weapon with its own ballistics, autoaim, sounds and ammo.
     */
    private static void weaponInput(Minecraft mc, DoomHost host) {
        if (mc.player == null) {
            return;
        }
        if (marineForm) {
            resolveWeapon(mc, host);
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

    /** DOOM's slot table: which weapons each hotbar slot holds, in preference order. */
    private static final String[][] SLOT_WEAPONS = {
        {"chainsaw", "fist"},          // 1 — the toggle pair
        {"pistol"},                    // 2
        {"supershotgun", "shotgun"},   // 3 — the other toggle pair
        {"chaingun"},                  // 4
        {"rocket"},                    // 5
        {"plasma"},                    // 6
        {"bfg"},                       // 7
    };
    private static final int[] SLOT_INDEX = {7, 1, 8, 3, 4, 5, 6}; // engine weapon ids
    private static final int[] SLOT_ALT = {0, -1, 2, -1, -1, -1, -1}; // the pair's other half

    /**
     * Weapon switching, resolved here and applied straight to the engine.
     *
     * It used to synthesise keypresses: post a slot scancode, hold it two client ticks so
     * G_BuildTiccmd could see it, and refuse to look at any new input until the release
     * landed. That made a switch take three ticks minimum and threw away everything the
     * player did in between, which is why it felt unresponsive and why a fast scroll
     * mostly did nothing.
     *
     * Now the latest intent wins: the newest slot the player asked for this tick is
     * resolved to a weapon and handed to the engine's own pendingweapon. Nothing is
     * queued, so spinning the wheel lands on wherever you stopped rather than replaying
     * every position you passed through.
     */
    private static void resolveWeapon(Minecraft mc, DoomHost host) {
        final WorldSnapshot s = suit;
        if (s == null || s.weaponOwned == null) {
            return;
        }
        // a slot key pressed this tick beats the hotbar selection: Minecraft fires no
        // selection change when the already-selected slot's own key is pressed again, and
        // that press is exactly how DOOM asks for the other half of a pair
        int want = -1;
        boolean repeat = false;
        for (int i = 0; i < 7; i++) {
            final boolean down = mc.options.keyHotbarSlots[i].isDown();
            if (down && !hotbarWasDown[i] && want < 0) {
                want = i;
                repeat = mc.player.getInventory().getSelectedSlot() == i;
            }
            hotbarWasDown[i] = down;
        }
        final int slot = mc.player.getInventory().getSelectedSlot();
        if (want < 0 && slot != lastSlot && slot >= 0 && slot < 7) {
            want = slot; // scroll wheel, or a slot the player moved to
        }
        lastSlot = slot;
        if (want < 0) {
            return;
        }

        // Pairs toggle. Pressing 1 with the chainsaw up gives the fist back, which vanilla
        // only allows while berserk — the chainsaw otherwise wins forever and the fist
        // becomes unreachable. This port lets the pair toggle both ways: the slot key is
        // the player asking for the OTHER one.
        final int primary = SLOT_INDEX[want];
        final int alt = SLOT_ALT[want];
        int chosen = -1;
        if (alt >= 0 && repeat && s.readyWeapon == primary && owns(s, alt)
            && available(alt)) {
            chosen = alt;
        } else if (alt >= 0 && repeat && s.readyWeapon == alt && owns(s, primary)
            && available(primary)) {
            chosen = primary;
        } else {
            for (String name : SLOT_WEAPONS[want]) {
                final int id = weaponId(name);
                if (owns(s, id) && available(id)) {
                    chosen = id;
                    break;
                }
            }
        }
        if (chosen < 0 || chosen == s.readyWeapon) {
            return;
        }
        host.selectWeapon(weaponName(chosen));
    }

    /**
     * Whether this weapon exists in the loaded game at all.
     *
     * The super shotgun is DOOM II's. Vanilla's own slot-3 handling gates it on
     * {@code gamemode == commercial}; IDKFA sets every weaponowned flag regardless, so
     * without the gate an episodic IWAD hands you a super shotgun that its WAD has no
     * sprites for.
     */
    private static boolean available(int id) {
        return id != 8 || !hasEpisodes();
    }

    private static boolean owns(WorldSnapshot s, int id) {
        return id >= 0 && s.weaponOwned != null && id < s.weaponOwned.length
            && s.weaponOwned[id];
    }

    private static int weaponId(String name) {
        return switch (name) {
            case "fist" -> 0;
            case "pistol" -> 1;
            case "shotgun" -> 2;
            case "chaingun" -> 3;
            case "rocket" -> 4;
            case "plasma" -> 5;
            case "bfg" -> 6;
            case "chainsaw" -> 7;
            case "supershotgun" -> 8;
            default -> -1;
        };
    }

    private static String weaponName(int id) {
        return switch (id) {
            case 0 -> "fist";
            case 1 -> "pistol";
            case 2 -> "shotgun";
            case 3 -> "chaingun";
            case 4 -> "rocket";
            case 5 -> "plasma";
            case 6 -> "bfg";
            case 7 -> "chainsaw";
            case 8 -> "supershotgun";
            default -> "fist";
        };
    }

    /** This tick's resolved trigger state (taps included) — presence reuses it. */
    private static boolean fireHeld;

    private static double lastMirrorX = Double.NaN, lastMirrorY;
    // the engine mobj's position last snapshot — for speed-independent teleport detection
    private static double lastEngineX = Double.NaN, lastEngineY;
    // authoritative teleport follow: the engine counts real player teleports; when the
    // snapshot's count moves we snap, no distance heuristic. Adopted (not fired) on every
    // new level instance so a fresh epoch's count is never mistaken for a teleport.
    private static long seenLevelEpoch;
    private static int seenTeleportCount;
    /** Ticks to keep the mirror muted after a start-delivery: teleportPlayer goes through
     * the server, so this tick's mc.player position is still the PRE-delivery spot — one
     * mirror write of it (stamped with the fresh epoch) would clobber the spawn again. */
    private static int mirrorHoldTicks;
    /** Consecutive between-levels ticks reading TITLE — the adventure-end branch is
     * debounced so a transient title read during a transition can't eject the player. */
    private static int titleTicks;

    /** The next level load delivers the player to its P1 start (/doomwarp's arrival). This
     * is the /load intent, so we are WARPING IN — mark it now, before the map even builds,
     * so the geometry renders and the level announces (a /doommarine suit-boot does neither). */
    public static void requestStartTeleport() {
        final StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        LOGGER.info("requestStartTeleport from {}.{}:{}",
            caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1),
            caller.getMethodName(), caller.getLineNumber());
        com.blackwithersteve.lattedoom.play.Session.requestDelivery();
        com.blackwithersteve.lattedoom.play.Session.setWarped(true);
    }

    /** LOAD GAME delivery: arm the start-teleport but hold it until a snapshot from
     * a NEW level instance arrives (the death-restart wait) — the player must land
     * on the LOADED level, not the still-standing pre-load one. */
    public static void requestLoadDelivery() {
        requestStartTeleport();
        com.blackwithersteve.lattedoom.play.Session.holdDeliveryUntilAfter(snap != null ? snap.levelEpoch : 0);
    }

    /** The standing level's lump name ("e1m2"), or null. */
    public static String mapName() {
        return mapName;
    }

    /** EVERYTHING session-scoped, cleared — the world-change sweep. The /doommarine
     * mid-air ghost was cross-world static leakage: the disconnect reset existed but was
     * never wired to the event, so a NEW world inherited the old world's warp flags,
     * mirror positions and level origin, and the teleport-follow lane read the fresh
     * suit engine's first snapshot as "a DOOM teleporter moved you". */
    public static void fullSessionReset() {
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", "fullSessionReset (world change)");
        drop();
        com.blackwithersteve.lattedoom.play.Session.reset();
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
        titleTicks = 0;
        DoomAutomap.reset();
        com.blackwithersteve.lattedoom.play.DoomMovement.resetSession();
    }

    /** A SUIT boot (/doommarine with no engine): the map runs only for the gun/HUD — no
     * warp, no delivery, no death pending. Clears any stale level-session flags so the
     * boot can never teleport the player anywhere ("spawned me in the sky"). */
    public static void suitBoot() {
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", "suitBoot (flags cleared)");
        com.blackwithersteve.lattedoom.play.Session.reset();
    }

    /** Is the shared/warped level session live (drives suit music: a lone overworld suit
     * stays quiet; the level's track plays when you're actually IN the level). */
    public static boolean musicShouldPlay(Minecraft mc) {
        return com.blackwithersteve.lattedoom.play.Session.warped() || worldIsRemoteNow(mc);
    }

    /** Level-complete hold: the engine is tallying the intermission (or in the finale) and
     * about to hand us the next map. Keep the just-finished geometry standing and pin the
     * player so they don't drift or fall through the floorless void dim while the swap runs.
     * We DON'T drop() and DON'T relinquish ownership — the next GS_LEVEL snapshot loads the
     * new map and the noted advance delivers the player to its start. */
    private static void holdBetweenLevels(Minecraft mc) {
        com.blackwithersteve.lattedoom.play.Session.noteAdvance(snap != null ? snap.levelEpoch : 0);
        if (mc.player != null) {
            mc.player.setDeltaMovement(0, 0, 0); // kill gravity drift between ticks
            mc.player.fallDistance = 0;
        }
    }

    /**
     * The walkover lane: any SPECIAL line the mirrored movement segment properly crossed
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

    /** Special lines this movement crossed, as {lineIdx, side} — for the local engine or
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
            // and the line must straddle the movement segment (proper crossing)
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

    /** AUTO-computed each tick: the engine senses you whenever you're inside a live level. */
    private static boolean playMode;

    public static boolean playMode() {
        return playMode;
    }

    /**
     * The marine transformation: model swap, 41-unit eye height and the rest. Off by
     * default, so an untransformed player in the level keeps their own eyes and body and
     * only the explicit transformation changes them. Presence in a level and the
     * transformation are deliberately separate: tying the eye height to presence changes
     * the player's view the moment they walk in.
     */
    private static boolean marineForm;

    public static boolean marineForm() {
        return marineForm;
    }

    /** Can this client draw a marine body at all? (No WAD → renderers must fall back to
     * Steve rather than cancel vanilla and show NOTHING.) */
    public static boolean spritesReady() {
        return sprites != null;
    }

    public static void setMarineForm(boolean on) {
        marineForm = on;
        // announce to the server: the roster drives what OTHER players see (PLAY marine
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

    /** B2 (blocks inside levels): ray-march the DRAWN level for a placement cell.
     * Vanilla placement needs a real block face to click; the level is rendered geometry
     * in a void dimension, so the FIRST block has nothing to click against. March the
     * view ray sample by sample until it leaves open space (crosses a floor, ceiling or
     * wall) and return the LAST OPEN sample's grid cell — open air adjacent to the
     * surface, exactly where a clicked face would have put it. Null = nothing in reach. */
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

    /**
     * A client that never booted its own engine (a friend watching YOU play) still needs
     * the WAD's PLAY sprites to draw a transformed player. Loads lazily on the client
     * tick (the safe GL slot) the moment anyone appears on the marine roster. Their OWN
     * local WAD, as always — the mod ships nothing.
     */
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
            marineAssetsFailed = true; // don't retry-spam; a broken WAD stays broken
            LOGGER.error("LatteWorld: marine spectate sprites failed to load", e);
        }
    }

    // Freeze watchdog. Tics can starve while the engine thread is still alive, with
    // neither pause nor menu set. When the tic stops advancing for three seconds and the
    // client did not freeze it, dump the engine thread's stack to the log: the wait it is
    // sitting in identifies the cause.
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
        if (host.isFrozen()) {
            // a deliberate pause is not a stall — and the clock must restart at the
            // unfreeze, or leaving the menus fired the alarm before the first new tic
            wdSinceMs = now;
            return;
        }
        if (!wdReported && now - wdSinceMs > 3000
            && host.state() == DoomHost.State.RUNNING) {
            wdReported = true;
            LOGGER.error("ENGINE STALL: tic stuck for {} ms — {}\n{}",
                now - wdSinceMs, host.debugState(), host.engineStackDump());
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cLatte Doom§r — the engine's tics stalled (world frozen). Stack dumped"
                    + " to logs/latest.log — that file is the freeze answer, send it over."));
            }
        }
    }

    // The level dimension. Loaded levels live in their own void world so the overworld's
    // chunks and entities never render or tick behind them. Collision is unaffected by the
    // dimension: inside a level it runs against the DOOM map rather than Minecraft blocks
    // (DoomMovement), so the void needs nothing to stand on. The player's position before
    // entering is remembered, to put them back when the level ends.
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
     * Send the player INTO the level dimension at (x,y,z). If already there it is just a
     * reposition; entering fresh from the overworld remembers where to return them to. The
     * server does the actual cross-dimension teleport (LatteNet.EnterLevelDimC2S).
     */
    // Warped in means actually playing a level, with rendered geometry and DOOM collision,
    // as opposed to only wearing the marine form in the overworld. /load sets this through
    // enterLevelDim. /doommarine must not: it boots an engine for the gun, HUD and ammo,
    // but raising a level in a survival world would drop the player onto a DOOM floor that
    // is not there.
    /** True while the player is warped into a level (render + DOOM physics), not just
     * suited. Owned by the session machine; this accessor remains for the callers. */
    public static boolean warpedIn() {
        return com.blackwithersteve.lattedoom.play.Session.warped();
    }

    public static void enterLevelDim(Minecraft mc, double x, double y, double z) {
        if (mc.player == null) {
            return;
        }
        // WHO-MOVED-ME diagnostic (the /doommarine mid-air hunt): name the caller
        final StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", String.format(
            "enterLevelDim (%.1f, %.1f, %.1f)", x, y, z));
        com.blackwithersteve.lattedoom.diag.DoomDiag.expectJump("enterLevelDim");
        LOGGER.info("enterLevelDim -> ({}, {}, {}) from {}.{}:{}",
            String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z),
            caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1),
            caller.getMethodName(), caller.getLineNumber());
        com.blackwithersteve.lattedoom.play.Session.setWarped(true);
        if (inLevelDim(mc)) {
            teleportPlayer(mc, x, y, z); // already in the void — same-dimension reposition
            return;
        }
        returnX = mc.player.getX();
        returnY = mc.player.getY();
        returnZ = mc.player.getZ();
        haveReturn = true;
        leaveRequested = false;
        com.blackwithersteve.lattedoom.net.LatteNet.sendEnterLevelDim(x, y, z);
    }

    /**
     * Take the player back to the overworld (the level ended). No-op if we're not in the
     * dimension. The boolean guard means the owner's two teardown paths — the engine's
     * quit callback and the shared-level-down broadcast it triggers — can't double-fire.
     */
    public static void leaveLevelDim(Minecraft mc) {
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("level", "leaveLevelDim");
        com.blackwithersteve.lattedoom.diag.DoomDiag.expectJump("leaveLevelDim");
        com.blackwithersteve.lattedoom.play.Session.setWarped(false); // back to just the suit (or nothing): stop rendering
        com.blackwithersteve.lattedoom.play.Session.clearDeath(); // a stranded death flag fired a spurious restart on re-entry
        if (marineForm) {
            // EVERY dimension exit reverts the form. An ejected player who stayed a
            // roster marine kept the MC hotbar hidden and item pickup blocked in the
            // overworld, so their items appeared to be gone while still being held.
            setMarineForm(false);
            if (mc.player != null) {
                mc.player.refreshDimensions();
            }
        }
        if (!inLevelDim(mc)) {
            leaveRequested = false; // already back out (or never in): reset for next time
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

    /**
     * Move the player a LONG distance so it sticks. A bare client setPos across more than
     * ~10 blocks gets vetoed by the (integrated) server — "Steve moved wrongly!" — which
     * snapped the player straight back. setPos runs first for a same-frame view; a mod
     * network packet then asks the server to teleport authoritatively (no op/cheats
     * needed, no "moved wrongly", and NO /tp chat command — the old approach spammed
     * "unknown command" for every non-op guest, on every teleport).
     */
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
     * when NOT warped in — so a marine wearing only the suit in the overworld keeps normal
     * Minecraft/overworld footing (no invisible DOOM floor lifting them off the ground). */
    /** The inputs {@link #insideLevel} decides on, as one line for the diagnostic log. */
    public static String levelStateForLog() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return "no player";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("warped=").append(com.blackwithersteve.lattedoom.play.Session.warped())
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
     * Whether the loaded WAD set is organised into episodes. An {@code ExMy} set is; a
     * commercial {@code MAPxx} set is not, and offering it an episode page picks the wrong
     * map. This reads the WAD's own map markers rather than which menu graphics happen to be
     * registered, because those survive a change of WAD set.
     */
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
        com.blackwithersteve.lattedoom.play.Session.abortDelivery("leaving the level");
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
        if (!com.blackwithersteve.lattedoom.play.Session.warped() || map == null || mapName == null) {
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

    /** PIT_CheckThing for the walking player: does any SOLID engine thing BLOCK you at
     * (px,py)? Uses the blockmap rule (thing radius + your 16u radius). */
    public static boolean solidThingAt(double px, double py) {
        return thingAt(px, py, 16.0);
    }

    /** Are you DEEPLY inside a solid thing at (px,py) — your centre within its body? */
    public static boolean insideSolidThing(double px, double py) {
        return thingAt(px, py, 0.0);
    }

    /**
     * The DOOM-faithful thing-block test for a MOVE from (fromX,fromY) to (toX,toY): does
     * the destination push you DEEPER into any solid thing than you already were? This blocks
     * walking INTO a monster (penetration would increase), but always lets you slide along it
     * or step back out (penetration same or less) — so you can't get trapped touching one, and
     * a teleport-drop inside a body can still walk free. Replaces the old "am I touching one"
     * boolean, which either let you pass clean through or (once tightened) glued you to them.
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
                return true; // moving deeper into it — block (but sliding/backing out is fine)
            }
        }
        return false;
    }

    private static boolean thingAt(double px, double py, double margin) {
        final com.blackwithersteve.lattedoom.engine.WorldSnapshot s = snap;
        if (s == null || s.mSolid == null) {
            return false;
        }
        // on a shared level OUR OWN possessed body (players[1..3] in the owner's engine)
        // rides in the mobj table AT OUR POSITION — colliding with it froze joining
        // players solid. Other players' bodies still block (they're really there).
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
                continue; // the possessed marine is you — you don't block yourself
            }
            if (haveOwn && s.mId[i] == ownBody) {
                continue; // your own remote body isn't a wall either
            }
            thingDrawn(s, i, thingScratch);
            final double bd = s.mRadius[i] + margin; // blockmap rule: radius sum, Chebyshev
            if (Math.abs(thingScratch[0] - px) < bd && Math.abs(thingScratch[1] - py) < bd) {
                return true;
            }
        }
        return false;
    }

    /** The map's own player-1 start, as a WORLD position (for arriving in the level). */
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

    // ---- remote level: another player's engine is the truth; we raise the SAME map from
    // OUR OWN WAD at their announced origin. Static geometry (their engine's doors/lifts
    // don't reach us yet — that's the engine-authority step), but you SEE the level and
    // WALK it under DOOM collision instead of falling through the sky.
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
        // a GUEST (someone else owns this level) joins the level dimension too, so co-op
        // players share one world — MC only renders entities/marine bodies in the same
        // dimension, and cross-side combat needs everyone co-located. Deferred until the
        // map is actually raised (remoteTick), which also excludes WAD-less guests.
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
        com.blackwithersteve.lattedoom.play.Session.setWarped(false); // the shared level we were in is gone
    }

    /** A guest has a shared level to join but hasn't been moved into its dimension yet. */
    private static boolean guestEnterPending;

    /** Latest world state off the wire (the owner's engine), applied next client tick. */
    public static void acceptRemoteSnap(WorldSnapshot s) {
        remoteSnap = s;
    }

    /** /doomwarp while spectating someone else's level: YOU become the world now (last
     * warper wins — id's co-op spirit). Our engine path resumes and re-announces. */
    public static void claimWorld() {
        clearRemoteLevel();
    }

    /** THIS client's own engine state — the marine suit (weapons/ammo/HUD). On the level
     * owner it is the same object as the world snapshot; on a spectator it is their
     * private suit engine (or null, no HUD). */
    static WorldSnapshot suitSnap() {
        return suit;
    }

    /** The WORLD's snapshot (own engine or the owner's wire feed) — what demons exist. */
    public static WorldSnapshot worldSnap() {
        return snap;
    }

    /** A raw lump from the local WAD (null if absent/no WAD) — remote sfx playback. */
    public static byte[] wadLump(String name) {
        final WadFile w = wad;
        return w != null ? w.lumpBytes(name) : null;
    }

    /** The shared level's lump name, if someone else's world is up (for suit warps). */
    public static String remoteMapName() {
        return remoteName;
    }

    /** Packed lightmap coords for a player standing IN the level (sector light as block
     * light, no sky — doom rooms make their own), or -1 outside/no level. */
    public static int levelLightCoords(double wx, double wy, double wz) {
        if (map == null || !insideLevel(wx, wy, wz)) {
            return -1;
        }
        final int sec = map.sectorAt(worldToDoomX(wx), worldToDoomY(wz));
        final int block = Math.max(0, Math.min(15, lightOf(sec) * 15 / 255));
        return block << 4;
    }

    /** Our own engine died (quit/crash): tear the shared level down for everyone —
     * without this, our own echo of the share keeps a ghost level standing. */
    public static void engineQuit() {
        clearRemoteLevel();
        drop();
        // the level is gone — come back out of the void to where we started (no-op if we
        // never left the overworld, e.g. a suit engine that ran without /load)
        leaveLevelDim(Minecraft.getInstance());
    }

    /** The spectator tick: raise the level someone else announced and run it on THEIR
     * engine's snapshot feed — movers glide, lights flicker, monsters exist. Our own
     * engine (if the marine suit booted one) only supplies the HUD. */
    private static void remoteTick(Minecraft mc, DoomHost ownHost, Path iwadPath) {
        suit = ownHost != null ? ownHost.worldSnapshot() : null;
        if (remoteName.equals(failedMap)) {
            return;
        }
        if (!remoteName.equals(mapName)) {
            try {
                loadCommon(mc, remoteName, iwadPath,
                    new double[]{remoteOx, remoteOy, remoteOz});
                announcedLevel = false; // spectating now: any old claim of ours is void
                LOGGER.info("LatteWorld: raised REMOTE {} at ({}, {}, {})", remoteName,
                    (int) remoteOx, (int) remoteOy, (int) remoteOz);
            } catch (Exception e) {
                LOGGER.error("LatteWorld: remote level {} failed to load", remoteName, e);
                drop();
                failedMap = remoteName;
                return;
            }
        }
        // the map is raised now — move the guest into the level dimension (once), so they
        // stand in the same void world as the owner and every other co-op player
        if (guestEnterPending && map != null && mc.player != null) {
            final double[] start = playerStartWorld();
            if (start != null) {
                guestEnterPending = false;
                guestDeliveredMap = remoteName;
                com.blackwithersteve.lattedoom.play.DoomMovement.forceReseed();
                enterLevelDim(mc, start[0], start[1] + 0.1, start[2]);
            }
        }
        final WorldSnapshot rs = remoteSnap;
        if (rs != null) {
            snap = rs; // the owner's world: heights/lights/mobjs feed the same machinery
        }
        playMode = false; // the OWNER's engine owns our presence there, not a local mirror
        if (ownHost != null) {
            weaponInput(mc, ownHost); // the suit still fires/switches through OUR engine
            ownHost.drainSounds();    // suit sfx are muted while spectating; keep it empty
        }
        com.blackwithersteve.lattedoom.play.DoomCombat.tick(mc, suit); // spectator guns are real too
        com.blackwithersteve.lattedoom.play.MinecraftCombat.tickArrows(mc); // arrows work for guests
        presenceTick(mc, rs);
    }

    // ---- PRESENCE upstream (B4): while inside the shared level, this client streams
    // its body + inputs to the owner's engine, where it lives as players[1..3] — hunted
    // by monsters, firing real bullets, opening real doors.
    private static double presX = Double.NaN, presY;
    // our remote body's position last tick (owner's snapshot) — teleport detection
    private static double lastRbX = Double.NaN, lastRbY;
    private static boolean remoteUseQueued;

    /** R pressed while the world is someone else's: ship USE upstream this tick. */
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
        // teleporter follow: the owner's engine moved OUR body (rb mapping by mId). Same
        // speed-independent test as the host path — network lag makes the remote body
        // trail our own movement, so an absolute distance check false-fired constantly
        // for a fast-moving guest. A real teleport jumps the body while we barely moved.
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

    /** OWNER side: a spectator's presence arrived — hand it to the engine (and bill
     * their MC hearts for whatever the engine did to them since last time). */
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
                // via the server (guest-safe): the world owner bills any player's hearts
                com.blackwithersteve.lattedoom.net.LatteNet.sendPlayerDamage(who, dmg, heal, 0, 0, false);
            }
        }
    }

    /** The engine's map as a WAD lump name: E1M1-style for DOOM 1 WADs, MAP01-style for
     * DOOM 2 / commercial WADs (probe the WAD itself — the engine plays both). */
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

    /** Parse the engine's current map from the SAME WAD file the engine booted, bake the mesh. */
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
        // Composite and register the WAD's textures. This runs on the client tick, the safe
        // GL-upload slot between frames. It must never run in the deferred render phase.
        DoomRuntimeTextures.init();
        DoomRuntimeTextures.ensureLoaded(wadId());

        map = DoomMap.loadFromWad(wad, name);
        tri = LatteMesh.bspMode() && map.hasBsp()
            ? BspTriangulator.triangulate(map)
            : SectorTriangulator.triangulate(map);
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
        // The level is anchored to a DETERMINISTIC 512-block grid cell (not the player's
        // exact feet): two clients standing near each other raise the level in the SAME
        // world spot, so a second player sees the same halls in the same place. Height is
        // FIXED sky (Y 128): deriving it from the player's Y rounded DOWN, so warping while
        // standing on ~Y63 terrain buried the whole level underground ("the level
        // disappears"). A constant is also the strongest two-client contract: same height
        // everywhere, every time. The 0.0071 nudge keeps DOOM's planes off MC's block grid.
        // A REMOTE level (someone else's engine) skips all of this: their announced origin
        // IS the origin, verbatim — identical world placement on every client.
        originEpoch++; // THE LAUNCH BUG: any state stored in origin-relative DOOM units
                       // is garbage the moment these change — holders must re-seed
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
        LatteSectorBuffers.dispose(); // new map: the old S2 buffers die with it
        if (LatteSectorBuffers.ENABLED) {
            // eager build at load, not lazily on the first frame — the lazy path
            // was a one-frame whole-map hitch the moment the level appeared
            LatteSectorBuffers.buildAll(g);
        }
        // (the /doomwarp start-delivery is consumed in clientTick — it must fire even when
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
        // per-sector linedef index (same one-time cost as neighbors, mirrors that lifetime)
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
        if (forcedOrigin == null && com.blackwithersteve.lattedoom.play.Session.warped()) {
            // OUR engine owns a level we WARPED INTO: share it so other players raise it too.
            // A /doommarine suit-boot (warpedIn false) runs a map only for the gun/HUD and
            // must NOT announce it — otherwise everyone else's world raises E1M1 out of nowhere.
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
     * Mirror the engine's live sector heights into the mesh: any sector whose floor or
     * ceiling the engine moved (a door, a lift) is re-baked, along with every neighbour
     * across a shared line, whose upper and lower wall strips stretch with the opening.
     */
    // Floor-texture swaps, as used by the "raise floor and change texture" specials that
    // many vanilla maps rely on. The engine reports each sector's live floorpic as an
    // index, so those indices are paired once at load with the authored flat names for
    // every flat the map uses; a sector whose floorpic moves to another known flat can
    // then be re-skinned. Owner and solo only, since the wire snapshot omits floorpic.
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
            // One timeline: collision and physics heights follow the rendered endpoint
            // (prev), one tic behind the engine, which is the same delayed pair the
            // platform mesh and the rider pin interpolate on. Do not feed the fresh engine
            // heights here. That puts physics a full tic ahead of the drawn platform, so
            // at every mover start, stop and reversal the pin and the physics floor
            // disagree: grounding flickers, the glue drops, and the ride stutters. Delayed
            // everywhere, the platform, the collision floor and the rider agree at every
            // instant, and a single tic of mover latency is invisible.
            final double df = prevFloor != null && i < prevFloor.length ? prevFloor[i] : s.floorH[i];
            final double dc = prevCeil != null && i < prevCeil.length ? prevCeil[i] : s.ceilH[i];
            final int f = (int) Math.floor(df);
            final int c = (int) Math.floor(dc);
            boolean sectorChanged = false;
            if (f != map.floorNow(i) || c != map.ceilNow(i)) {
                // P_ChangeSector, our way: T_MovePlane moves every thing standing ON the
                // plane in the same operation. If the LOCAL player is glued to this
                // sector, carry them by exactly the floor's delta — any speed, both
                // directions. This is what keeps you ON a fast lift instead of it
                // outrunning gravity (and it rides the wire heights for spectators too).
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
                    map.setFloorFlat(i, name); // re-skin: lava floor rose into rock, etc.
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
        // PRESSED SWITCHES: adopt the engine's flipped lines; re-bake just those walls'
        // sectors (both sides). Reverts (reusable buttons) fall out of the same diff.
        if (s.switchedLines != null) {
            final Set<Integer> now = new HashSet<>();
            for (int li : s.switchedLines) {
                now.add(li);
            }
            if (!now.equals(map.switchedLines)) {
                final Set<Integer> delta = new HashSet<>(now);
                delta.addAll(map.switchedLines); // symmetric: presses AND reverts rebake
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
        // Deferred rebake. While a sector glides its static group is not drawn at all,
        // since bakeInterp is, yet re-baking it and its neighbours every tic on the render
        // thread costs a frame hitch per mover at 35Hz. Mark it dirty here and re-bake only
        // once the sector stops moving. The neighbour fan-out uses the precomputed table
        // rather than a full linedef scan.
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
                LatteSectorBuffers.rebuild(i, one); // S2: keep this sector's GPU buffer current
            }
        }
    }

    /**
     * Draw the level's persistent GPU buffers (S2). Called from the LevelRenderer.render TAIL
     * mixin, which hands us the camera straight from the render's own CameraRenderState — the
     * VIEW ROTATION matrix + camera position, deterministic and independent of the deferred
     * pipeline's global modelview (capturing pose.last().pose() in the submit hook gave NO
     * rotation, so the geometry didn't turn with the mouse). No-op unless /persist is on.
     */
    public static void drawPersistent(org.joml.Matrix4f viewRotation, double cx, double cy,
                                      double cz,
                                      net.minecraft.client.renderer.culling.Frustum frustum) {
        if (!LatteSectorBuffers.ENABLED || !com.blackwithersteve.lattedoom.play.Session.warped() || groups == null || viewRotation == null) {
            return;
        }
        // camera-relative like the classic path: rotate by the view, then translate the level
        // origin by (origin - cam). Vertices are baked in mesh-local coords.
        final org.joml.Matrix4f mv = new org.joml.Matrix4f(viewRotation).translate(
            (float) (originX - cx), (float) (originY - cy), (float) (originZ - cz));
        final WorldSnapshot s = snap;
        LatteSectorBuffers.draw(mv, s != null ? s.tic : 0, frustum, sectorBounds,
            movingSectors(), originX, originY, originZ);
    }

    /** Release the mesh (engine left the level). Textures stay registered — they're per-WAD. */
    private static void drop() {
        LatteSectorBuffers.dispose(); // free the S2 GPU buffers for this map
        if (announcedLevel) {
            // our shared level goes away for everyone (warp reboot, quit, world leave)
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
        // NB: don't clear warpedIn here — drop() also fires mid-/load-reboot (engine briefly
        // has no snapshot), and clearing it would blank the incoming level. leaveLevelDim/
        // clearRemoteLevel own the "we actually left" transition; the map==null/groups==null
        // guards make a stale warpedIn harmless in the meantime.
        lastMirrorX = Double.NaN; // stale mirror must not fake a teleport on the next level
        lastEngineX = Double.NaN;
    }

    // ---- renderer access (render thread, same client thread) ----

    static Map<Integer, Map<String, float[]>> groups() {
        return groups;
    }

    /** Engine-live sector light (0-255) for the shade of a vertex; the ENGINE animates
     * these. Without an engine (a REMOTE shared level) the WAD's authored light levels
     * shade the world — not fullbright. */
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
     * 35Hz — when a new snapshot tic appears, yesterday's targets become the previous
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
     * {x, y, z} in map units. Objects with no previous keyframe, and steps large enough to
     * be a teleport, report their current position unchanged. */
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
        final long t0 = LatteFrameStats.t();
        try {
            renderSyncInner();
        } finally {
            LatteFrameStats.sync(t0);
        }
    }

    private static void renderSyncInner() {
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
            riderFollow(); // the platform glides every FRAME; so does its rider
            return;
        }
        // roll the pair and TAG each keyframe with the engine tic it belongs to — the
        // interpolation below is time-driven, not arrival-driven. The RENDERED pair is
        // prev -> cur (the NEWEST data): the tape showed the old prev2 -> prev pair
        // starving the renderer a full publish behind while cur sat unused (alpha pinned
        // at the cap, lurching at every roll — "consistent but choppy").
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
        // The synthesized engine clock. Arrival-keyed timelines cannot be made smooth: the
        // engine thread's tic production jitters under JVM scheduling, and the render
        // thread notices arrivals only at frame quantization. Any scheme that nudges
        // individual arrivals relocates that jitter rather than removing it. Instead one
        // continuous engine-time clock runs here (baseTic anchored at baseNanos, drift
        // corrected 2% per roll, hard resync past three tics to cover an unfreeze) and the
        // mesh renders at a fixed latency behind the newest keyframe. Jitter up to that
        // budget is absorbed entirely, at a cost of roughly two tics (57ms) of mover
        // latency, which is invisible on doors and lifts.
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

    /** The smooth render clock in engine tics — for per-frame effects that follow DOOM
     * time (the marine camera's P_CalcHeight bob). */
    public static double renderTics() {
        return engineTimeTics();
    }

    /** Flight-recorder lane: one line per frame while anything moves — the exact drawn
     * height of the first moving sector, with the clock state. The motion curve on tape. */
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
                return; // one sector per frame is enough signal
            }
        }
    }

    /** The rider glides WITH the rendered platform: while glued to a sector whose
     * RENDERED pair is moving, the player's Y follows the same interpolation the mesh
     * uses — per frame, not per keyframe (the keyframe jumps were the "jumpy" ride). */
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
        if (!com.blackwithersteve.lattedoom.play.Session.warped() || !inLevelDim(mc) || mc.player.isPassenger()
            || mc.player.getAbilities().flying
            || !insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
            com.blackwithersteve.lattedoom.play.DoomMovement.releaseGlue();
            return;
        }
        // Lock the rider to the platform mesh's OWN timeline. setPos alone is not enough: MC
        // renders the eye as getPosition(partialTick) = lerp(partialTick, yo, getY()) — the
        // 20Hz render clock against a stale tic-start yo, a DIFFERENT clock than the 35Hz
        // alpha() the floor mesh is baked at — so the surface slid under the player every
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
     * World Y of the glued sector's RENDERED floor surface this frame — the exact
     * {@code prev2 -> prev @ alpha()} the platform mesh ({@link #bakeInterp}) is baked at — or
     * NaN when the local player isn't standing on a sector that is currently gliding. Both
     * {@link #riderFollow} (per frame) and the DoomMovement physics tick (once per client tick)
     * read this so they write the SAME vertical; otherwise the per-tick physics height
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
    // the synthesized engine clock + tic-tagged keyframe pair
    private static long baseNanos;
    private static int baseTic;
    private static int ticOfPrev2, ticOfPrev, ticOfCur;

    /** Render latency behind the newest keyframe, in tics: the jitter budget. With the
     * per-TIC publish (Engine.TIC_TAP) the feed is steady 35Hz, so 1.5 is plenty —
     * ~43ms of mover/monster latency, invisible. */
    private static final double RENDER_LATENCY_TICS = 1.5;

    /** Progress through the NEWEST tagged pair (prev -> cur) at the synthesized engine
     * time minus the fixed latency. Time-driven: any arrival jitter within the budget is
     * mathematically invisible. Soft cap 1.15 = brief extrapolation, never a stall. */
    static double alpha() {
        if (ticOfCur <= ticOfPrev) {
            return 1.0;
        }
        final double t = engineTimeTics() - RENDER_LATENCY_TICS;
        return Math.max(0.0, Math.min(1.15,
            (t - ticOfPrev) / (double) (ticOfCur - ticOfPrev)));
    }

    /** Continuous engine time in tics (integer tic + intra-tic phase) — the clock for
     * anything that must glide at ANY frame rate (weapon bob sway). Tic-STEPPED effects
     * (8-tic flat animations) keep using snap.tic: stepping is the authentic look. */
    static double ticTime() {
        return engineTimeTics();
    }

    /** Sectors gliding this frame (RENDERED pair differs) plus wall-sharing neighbors. */
    static Set<Integer> movingSectors() {
        if (curFloor == null) {
            return Set.of();
        }
        Set<Integer> moving = null;
        for (int i = 0; i < curFloor.length; i++) {
            // A sector needs per-frame interpolation the moment its FRESHEST height differs
            // from the rendered pair (leading edge, cur != prev) through the last gliding tic
            // (trailing edge, prev != prev2). Testing only prev != prev2 lagged one tic behind
            // syncHeights' static rebake, so the FIRST motion tic snapped the mesh straight to
            // the new height (then jumped back to start interpolating) — the door/lift judder.
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

    /** Bake one sector THIS FRAME at the interpolated height, ONE TIC BEHIND the engine
     * (prev2 -> prev): the pair being rendered is always complete, so a late tic can
     * never stall a glide mid-door. 28ms of mover latency is invisible. */
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

    /** The viewer's extralight in light-BYTE units: vanilla adds extralight per
     * lightnum BAND, and (light + 16*e) >> 4 == (light >> 4) + e exactly. */
    static int extraLightBytes() {
        final com.blackwithersteve.lattedoom.engine.WorldSnapshot s = snap;
        return s != null ? Math.max(0, s.extraLight) * 16 : 0;
    }

    // sprite-pass access
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
