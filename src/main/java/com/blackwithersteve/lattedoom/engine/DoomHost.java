package com.blackwithersteve.lattedoom.engine;

import doom.ConfigBase;
import doom.DoomMain;
import doom.event_t;
import doom.player_t;
import doom.weapontype_t;
import doom.thinker_t;
import doom.evtype_t;
import p.mobj_t;
import g.Signals.ScanCode;
import mochadoom.DoomQuitSignal;
import mochadoom.Engine;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Owns the embedded DOOM engine. Boots Mocha Doom on a dedicated daemon thread,
 * captures every rendered frame as ARGB pixels, injects key/mouse events into the
 * engine's queue, and can freeze the engine at a frame boundary while the host
 * (Minecraft) isn't displaying it.
 *
 * This class deliberately has no Minecraft imports: the headless smoke harness
 * drives it exactly like the mod does.
 */
public final class DoomHost {

    public enum State { BOOTING, RUNNING, QUIT, CRASHED }

    private volatile DoomMain<?, ?> doom;
    private volatile Thread engineThread;
    private volatile State state = State.BOOTING;

    /** The one engine this client currently means. boot() claims it; the frame callback
     * of any other instance sees the mismatch and terminates itself. */
    private static final java.util.concurrent.atomic.AtomicReference<DoomHost> LIVE =
        new java.util.concurrent.atomic.AtomicReference<>();
    private volatile Throwable crash;
    private volatile boolean frozen;

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("lattedoom");
    // STATUE-BUG diagnostics: detect the level ticker (P_Ticker) freezing while gametic advances.
    private int diagLastGametic;
    private int diagLastLeveltime = -1;
    private volatile boolean terminate;

    // ---- player possession (M3a): the MC player's position mirrored into players[0].mo,
    // so every monster sense (sight, aim, infighting) tracks the REAL you. Applied on the
    // engine thread at the frame boundary; god-mode scaffold until damage translation lands.
    /**
     * The mirror is published as one immutable value rather than a field per component. Six
     * separate volatiles let the engine read the gate from one call and the coordinates from
     * the next, because a volatile read only orders against itself: a frame could admit a
     * live epoch and then apply the zeroes written by the release that followed it, relinking
     * the player's object to map coordinate (0,0).
     */
    private record Mirror(boolean on, double x, double y, double z, double angleDeg,
                          long epoch) {}

    private static final Mirror MIRROR_OFF = new Mirror(false, 0, 0, 0, 0, 0);

    private final java.util.concurrent.atomic.AtomicReference<Mirror> mirror =
        new java.util.concurrent.atomic.AtomicReference<>(MIRROR_OFF);

    private boolean mirrorWasOn; // engine-thread only
    /** The level epoch the client's mirror coordinates were computed against (echoed from
     * the snapshot). The engine only APPLIES mirror positions stamped with the CURRENT
     * epoch — a fresh spawn (death restart, next level, /warp reboot) must never be
     * clobbered by coordinates converted through the previous map's anchors. */
    private boolean mirrorEpochWasOk; // engine-thread only: rising-edge health re-baseline
    // ---- engine-side teleport follow (engine-thread only): after a teleport special
    // moves the player, HOLD mirror position writes until the client visibly followed
    // (its mirror lands near the destination) — else the very next frame's mirror write
    // yanks the body back through the teleporter before the client ever saw it move
    // ("you see the teleport animation but you don't teleport").
    private int teleSyncCount;
    private boolean telePending;
    private double teleDstX, teleDstY;
    private int telePendingFrames;
    /** Interactions the possessed player performed, fired through the ENGINE's own handlers. */
    private final java.util.concurrent.ConcurrentLinkedQueue<int[]> pendingCross =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean pendingAlert =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean pendingUse =
        new java.util.concurrent.atomic.AtomicBoolean();
    /** DOOM hit points the engine took off the possessed marine since the last drain. */
    private final java.util.concurrent.atomic.AtomicInteger pendingDamage =
        new java.util.concurrent.atomic.AtomicInteger();
    /** DOOM hit points the engine GAVE the marine (medikits, soulspheres) since the drain. */
    private final java.util.concurrent.atomic.AtomicInteger pendingHeal =
        new java.util.concurrent.atomic.AtomicInteger();
    /** The MC player's health in DOOM points (hearts × 5), slaved into the engine each frame. */
    private volatile int mirrorHealth = 100;
    private int lastWrittenHealth = 100; // engine-thread only
    private int lastClipTic = -1;        // engine-thread only
    /** Marine form: DOOM pickups collect only for the transformed marine, not plain Steve. */
    private volatile boolean marineMode;
    private int lastPickupTic = -1; // engine-thread only
    /** Steve scavenging: sprite names a PLAIN player may consume (from pickups.properties;
     * empty = the feature is off). Engine consumes + queues; the client converts. */
    private volatile java.util.Set<String> scavengeSprites = java.util.Set.of();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingScavenge =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private volatile int width = 320;
    private volatile int height = 200;

    private int[] renderBuf;                  // engine thread only
    private int[] sharedBuf;                  // guarded by bufLock
    private final Object bufLock = new Object();
    private long framesPublished;

    /**
     * Latest world-state snapshot, published at each page flip (null while no level runs —
     * title screen, intermission). Volatile swap: readers grab a fully-built immutable copy.
     */

    // One reusable mouse event, exactly like the stock AWT frontend uses.
    private final event_t.mouseevent_t mouseEvent =
        new event_t.mouseevent_t(evtype_t.ev_mouse, 0, 0, 0);

    private final Runnable onQuit;
    private final Consumer<Throwable> onCrash;

    private DoomHost(Runnable onQuit, Consumer<Throwable> onCrash) {
        this.onQuit = onQuit != null ? onQuit : () -> {};
        this.onCrash = onCrash != null ? onCrash : t -> {};
    }

    /**
     * Boot DOOM asynchronously. WAD loading and all engine init happen on the
     * engine thread; poll {@link #state()} until it leaves BOOTING.
     *
     * @param iwad    path to DOOM.WAD / DOOM2.WAD etc.
     * @param dataDir where default.cfg, mochadoom.cfg and savegames live
     */
    public static DoomHost boot(Path iwad, Path dataDir, List<String> extraArgs,
                                Runnable onQuit, Consumer<Throwable> onCrash) {
        return boot(iwad, dataDir, extraArgs, onQuit, onCrash, "default", -1);
    }

    /**
     * As {@link #boot(Path, Path, List, Runnable, Consumer)}, keyed to a savegame set.
     *
     * @param saveSetKey names this boot's save folder under dataDir/saves/ — the
     *                   GZDoom rule: a save belongs to the exact iwad+pwads+dehs
     *                   combination that made it
     * @param loadSlot   -1, or a slot to G_LoadGame right after engine init (the
     *                   engine's own -loadgame arg formats a Character with %d and
     *                   dies, so boot-into-save goes through here)
     */
    public static DoomHost boot(Path iwad, Path dataDir, List<String> extraArgs,
                                Runnable onQuit, Consumer<Throwable> onCrash,
                                String saveSetKey, int loadSlot) {
        final DoomHost host = new DoomHost(onQuit, onCrash);
        host.saveSetKey = saveSetKey;
        host.bootLoadSlot = loadSlot;
        LIVE.set(host);
        final Thread thread = new Thread(() -> host.run(iwad, dataDir, extraArgs), "LatteDoom-Engine");
        thread.setDaemon(true);
        host.engineThread = thread;
        thread.start();
        return host;
    }

    private String saveSetKey = "default";
    private int bootLoadSlot = -1;
    private volatile Path saveDir;

    /** Menu Save: the engine's own G_SaveGame — plain fields the game tic reads,
     * written into this boot's per-set folder on the next unfrozen tic. */
    public void requestSave(int slot, String description) {
        final DoomMain<?, ?> d = doom;
        if (d != null && state == State.RUNNING) {
            d.SaveGame(slot, description);
        }
    }

    /** Cross the exit programmatically: the engine's own G_ExitLevel, taken on its
     * next tic (plain field writes the game tic reads — the LoadGame idiom). */
    public void requestExitLevel() {
        final DoomMain<?, ?> d = doom;
        if (d != null && state == State.RUNNING) {
            d.ExitLevel();
        }
    }

    /** Menu Load: the engine's own G_LoadGame from this boot's per-set folder. */
    public void requestLoad(int slot) {
        final DoomMain<?, ?> d = doom;
        final Path dir = saveDir;
        if (d != null && dir != null && state == State.RUNNING) {
            d.LoadGame(dir.resolve("doomsav" + slot + ".dsg").toString());
        }
    }

    private void run(Path iwad, Path dataDir, List<String> extraArgs) {
        try {
            mochadoom.Engine.PLAYERS_IMMORTAL = true; // Minecraft owns dying, always
            mochadoom.Engine.TIC_TAP = this::onTic;   // per-tic world publish (smoothness)
            WorldSnapshot.newBoot(); // fresh level epoch — stale mirrors die at the gate
            // sound events out to the host (one engine per client: the static is ours)
            mochadoom.Engine.SOUND_TAP = (origin, sfxId) -> {
                if (soundEvents.size() < 64) {
                    if (origin instanceof p.mobj_t m) {
                        soundEvents.add(new int[]{sfxId, m.x >> 16, m.y >> 16, 1});
                    } else {
                        soundEvents.add(new int[]{sfxId, 0, 0, 0});
                    }
                }
            };
            Files.createDirectories(dataDir);
            ConfigBase.Files.FOLDER_OVERRIDE = dataDir.toString() + File.separator;
            // savegames live per WAD SET: the engine's own G_SaveGame path builds
            // SAVEGAMENAME + slot + ".dsg", so pointing the prefix into this boot's
            // set folder is the whole isolation
            final Path saves = dataDir.resolve("saves").resolve(saveSetKey);
            Files.createDirectories(saves);
            this.saveDir = saves;
            data.dstrings.SAVEGAMENAME = saves.resolve("doomsav").toString();

            final List<String> argv = new ArrayList<>();
            argv.add("-iwad");
            argv.add(iwad.toAbsolutePath().toString());
            argv.addAll(extraArgs);

            final Engine engine = Engine.initEmbedded(this::onFrame, argv.toArray(new String[0]));
            final DoomMain<?, ?> d = engine.getDOOM();
            this.width = d.graphicSystem.getScreenWidth();
            this.height = d.graphicSystem.getScreenHeight();
            if (bootLoadSlot >= 0) {
                // boot-into-save: the queued gameaction runs on the first game tics
                d.LoadGame(saves.resolve("doomsav" + bootLoadSlot + ".dsg").toString());
            }
            this.renderBuf = new int[width * height];
            synchronized (bufLock) {
                this.sharedBuf = new int[width * height];
            }
            this.doom = d;
            this.state = State.RUNNING;

            d.setupLoop(); // never returns normally
        } catch (DoomQuitSignal quit) {
            state = State.QUIT;
            shutdownAudio();
            onQuit.run();
        } catch (Throwable t) {
            state = State.CRASHED;
            crash = t;
            t.printStackTrace();
            shutdownAudio();
            onCrash.accept(t);
        }
    }

    private volatile int wantSfxVol = 15, wantMusicVol = 15;
    private int appliedSfxVol = -1, appliedMusicVol = -1;
    private volatile boolean hadLevel;
    private volatile boolean mapRestartPending;

    private volatile double knockDirX, knockDirY, knockThrust;
    private volatile boolean knockPending;
    private volatile int pendingPain;

    /** MINECRAFT-side damage on the marine: flash the suit and grunt like the engine
     * would have (damagecount + plpain), since P_DamageMobj never saw the hit. */
    public void requestPlayerPain(int dmgHp) {
        pendingPain = Math.min(200, pendingPain + dmgHp);
    }

    // ---- sound events: {sfxId, doomX, doomY, hasPos} per engine StartSound ----
    private final java.util.concurrent.ConcurrentLinkedQueue<int[]> soundEvents =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** All sound events since the last drain (the world owner broadcasts these). */
    public java.util.List<int[]> drainSounds() {
        if (soundEvents.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<int[]> out = new java.util.ArrayList<>();
        int[] e;
        while ((e = soundEvents.poll()) != null) {
            out.add(e);
        }
        return out;
    }

    /** The engine's last damage push on the local player: {dirDoomX, dirDoomY,
     * thrustUnitsPerTic} away from the attacker, or null if none since last drain. */
    public double[] drainKnockback() {
        if (!knockPending) {
            return null;
        }
        knockPending = false;
        return new double[]{knockDirX, knockDirY, knockThrust};
    }

    // ---- MINECRAFT -> DOOM damage (fists, swords, arrows hitting demons/barrels) ----
    private final java.util.Queue<Object[]> pendingThingDamage =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Damage the mobj with this identity, attributed to that player's possessed body
     * (monsters retaliate at whoever hit them). Runs at the next frame boundary. */
    public void requestThingDamage(int mobjId, int damageHp, java.util.UUID attacker) {
        pendingThingDamage.add(new Object[]{mobjId, damageHp, attacker});
    }

    private void applyThingDamage(DoomMain<?, ?> d) {
        Object[] req;
        while ((req = pendingThingDamage.poll()) != null) {
            final int mobjId = (Integer) req[0];
            final int dmg = (Integer) req[1];
            final java.util.UUID attacker = (java.util.UUID) req[2];
            p.mobj_t target = null;
            final var cap = d.actions.getThinkerCap();
            for (var t = cap.next; t != cap; t = t.next) {
                if (t instanceof p.mobj_t m && System.identityHashCode(m) == mobjId) {
                    target = m;
                    break;
                }
            }
            if (target == null || (target.flags & p.mobj_t.MF_SHOOTABLE) == 0) {
                // STATUE-BUG DIAG (the "can't damage" fault): a hit arrived but was dropped.
                LOGGER.warn("applyThingDamage DROP id={} dmg={} reason={}",
                    mobjId, dmg, target == null ? "target-not-found" : "not-shootable");
                continue;
            }
            // source = the attacker's body in THIS engine (players[0] = the owner,
            // players[1..3] = remote bodies) so the demon knows who to hate
            p.mobj_t source = d.players[0].mo;
            if (attacker != null) {
                final RemoteBody rb = remoteBodies.get(attacker);
                if (rb != null && rb.playerIdx > 0 && d.players[rb.playerIdx].mo != null) {
                    source = d.players[rb.playerIdx].mo;
                }
            }
            final int hpBefore = target.health;
            d.actions.DamageMobj(target, source, source, dmg);
            // if HP drops but the imp never dies, the death STATE isn't advancing (P_Ticker
            // frozen); if HP doesn't drop, DamageMobj itself no-op'd
            LOGGER.warn("applyThingDamage HIT id={} dmg={} hp {}->{}",
                mobjId, dmg, hpBefore, target.health);
        }
    }

    /** Slave the engine's audio to Minecraft's sliders (0-15 DOOM scale). */
    public void setVolumes(int sfx, int music) {
        wantSfxVol = sfx;
        wantMusicVol = music;
    }

    /** Kill the engine's own audio threads so music doesn't loop over Minecraft forever. */
    private void shutdownAudio() {
        final DoomMain<?, ?> d = doom;
        if (d == null) {
            return;
        }
        try {
            if (d.music != null) {
                d.music.PauseSong(0);
                d.music.ShutdownMusic();
            }
        } catch (Throwable t) { noteSwallowed("site1", t);
        }
        try {
            if (d.soundDriver != null) {
                d.soundDriver.ShutdownSound();
            }
        } catch (Throwable t) { noteSwallowed("site2", t);
        }
    }

    /**
     * Runs on the engine thread at every page flip (Engine.updateFrame). Publishes
     * the finished frame, then parks here while the host has us frozen — that
     * freezes DOOM at a frame boundary with zero CPU burn.
     */

    /**
     * Latte Doom's per-TIC publish (Engine.TIC_TAP): world state captured after EVERY
     * completed gametic — the modern-source-port feed. Publishing per display frame
     * starved the client interpolation (the engine loop runs slower than 35fps and in
     * bursts), which read as ~15fps motion on everything. Engine thread.
     */
    /** Inventory grants, weapon selection and typed cheats apply whenever the engine is
     * running. They were previously drained inside the possession gate, which meant a suit
     * engine in the overworld silently queued them and then applied the whole backlog on the
     * next level entry. None of them depend on the player being mirrored. */
    private void applyPlayerRequests(doom.DoomMain<?, ?> d) {
        if (d.players[0] == null) {
            return;
        }
        applyGrants(d);
        final g.Signals.ScanCode ck = pendingKeys.poll();
        if (ck != null) {
            postKey(ck, true);
            postKey(ck, false);
        }
    }

    private void onTic() {
        final DoomMain<?, ?> d = doom;
        if (d == null) {
            return;
        }
        // Publication guards: the tic tap is a STATIC on the engine, so a dying
        // predecessor's thread can invoke the CURRENT host's onTic (or a replaced
        // host its own) for a beat after a reboot. Only this host's own engine
        // thread, and only while this host is the live one, may publish.
        if (Thread.currentThread() != engineThread || LIVE.get() != this) {
            return;
        }
        // A capture hiccup must never kill the tic tap (the engine loop lives through here).
        try {
            markSeenLines(d); // automap reveal — BEFORE capture so this tic's snapshot has it
        } catch (Throwable t) { noteSwallowed("site3", t);
        }
        WorldSnapshot captured;
        try {
            final WorldSnapshot ws = WorldSnapshot.capture(d);
            if (ws != null && !remoteBodies.isEmpty()) {
                // which snapshot mobj belongs to which remote PLAYER (the friend follows
                // their own body through this — teleporter rides come back to them)
                final java.util.List<long[]> ids = new java.util.ArrayList<>();
                for (var e : remoteBodies.entrySet()) {
                    final RemoteBody rb = e.getValue();
                    if (rb.playerIdx > 0 && d.players[rb.playerIdx].mo != null) {
                        ids.add(new long[]{e.getKey().getMostSignificantBits(),
                            e.getKey().getLeastSignificantBits(),
                            System.identityHashCode(d.players[rb.playerIdx].mo)});
                    }
                }
                final int n = ids.size();
                ws.rbUuidMost = new long[n];
                ws.rbUuidLeast = new long[n];
                ws.rbMobjId = new int[n];
                for (int i = 0; i < n; i++) {
                    ws.rbUuidMost[i] = ids.get(i)[0];
                    ws.rbUuidLeast[i] = ids.get(i)[1];
                    ws.rbMobjId[i] = (int) ids.get(i)[2];
                }
            }
            captured = ws;
            if (ws != null) {
                hadLevel = true; // this engine has really been in a level (not just booting)
            }
        } catch (Throwable t) {
            captured = null;
            noteSwallowed("capture", t); // was silent, so a failing capture left no trace
        }
        // The engine stage, published ONLY here at a completed tic and read by the
        // client in place of any live gamestate access. G_DoWorldDone flips
        // gamestate to GS_LEVEL BEFORE P_SetupLevel builds the level, all inside
        // one long engine tic — a live read in that window claims "in a level"
        // while the snapshot is still null, which broke the client's between-levels
        // hold and ejected the player on level finishes. Phase and snapshot now
        // publish from the same completed tic, so that window cannot be observed.
        // ONE write: snapshot and stage reach every reader together or not at all
        published = new Published(captured, new PhaseSnap(kindOf(d), ++phaseTicks));
        // The engine's own HU.Ticker consumes plr.message and NULLS it, and it runs inside
        // Ticker() — before this tap fires. Every heads-up message was therefore already
        // gone by the time capture() looked, which is why none ever appeared. Its display
        // is switched off at boot so the field survives for us; we draw the HUD, and the
        // engine's framebuffer is never shown.
        if (d.menu != null && d.menu.getShowMessages()) {
            d.menu.setShowMessages(false);
        }
        // Messages are QUEUED, not left on the snapshot. capture() read-and-clears
        // player_t.message, so a message lives on exactly one snapshot; the client samples
        // at 20Hz while this publishes at 35Hz, so any message that landed on a snapshot
        // the client never sampled was simply lost. A queue cannot drop one.
        if (captured != null && captured.message != null) {
            pendingMessages.add(captured.message);
            while (pendingMessages.size() > 16) {
                pendingMessages.poll();
            }
        }
        try {
            // the native intermission screen's data feed (only meaningful in GS_INTERMISSION,
            // but wminfo is stable from level exit until the next level starts)
            if (d.gamestate == defines.gamestate_t.GS_INTERMISSION && d.wminfo != null
                && d.wminfo.plyr != null && d.wminfo.pnum < d.wminfo.plyr.length) {
                final InterSnap is = new InterSnap();
                is.epsd = d.wminfo.epsd;
                is.last = d.wminfo.last;
                is.next = d.wminfo.next;
                is.maxKills = Math.max(1, d.wminfo.maxkills);
                is.maxItems = Math.max(1, d.wminfo.maxitems);
                is.maxSecret = Math.max(1, d.wminfo.maxsecret);
                final doom.wbplayerstruct_t pl = d.wminfo.plyr[d.wminfo.pnum];
                is.kills = pl.skills;
                is.items = pl.sitems;
                is.secret = pl.ssecret;
                is.timeTics = pl.stime;
                is.parTics = d.wminfo.partime;
                interSnap = is;
            }
        } catch (Throwable t) { noteSwallowed("site4", t);
        }
    }

    /** Automap reveal. The software renderer marked every line it drew
     * (R_StoreWallRange: {@code line.flags |= ML_MAPPED}) and the automap shows only
     * marked lines — with the view render retired (RENDER_VIEW=false, the smoothness
     * architecture) nothing marked anything and Tab showed just the arrow. Recreate the
     * reveal with the engine's own blockmap traversal: a fan of sight rays across the
     * player's 90° FOV every tic, marking each line crossed and stopping at the first
     * solid wall or closed window — occlusion-true, like the renderer was. 61 short rays
     * per tic is far cheaper than the column renderer this replaces. */
    private static final int AUTOMAP_RAYS = 61;
    private static final int AUTOMAP_RANGE = 2048; // map units, hitscan's MISSILERANGE

    private void markSeenLines(DoomMain<?, ?> d) {
        if (d.gamestate != defines.gamestate_t.GS_LEVEL
            || d.players == null || d.players[0] == null || d.players[0].mo == null
            || d.levelLoader == null || d.levelLoader.lines == null) {
            return;
        }
        final mobj_t mo = d.players[0].mo;
        final long a0 = (mo.angle - data.Tables.ANG45) & data.Tables.BITS32;
        final long step = data.Tables.ANG90 / (AUTOMAP_RAYS - 1);
        for (int i = 0; i < AUTOMAP_RAYS; i++) {
            final int idx = data.Tables.toBAMIndex((a0 + step * i) & data.Tables.BITS32);
            final int x2 = mo.x + AUTOMAP_RANGE * data.Tables.finecosine[idx];
            final int y2 = mo.y + AUTOMAP_RANGE * data.Tables.finesine[idx];
            d.actions.PathTraverse(mo.x, mo.y, x2, y2, data.Defines.PT_ADDLINES, in -> {
                final rr.line_t l = in.line;
                if (!in.isaline || l == null) {
                    return true;
                }
                l.flags |= rr.line_t.ML_MAPPED;
                if (l.backsector == null || l.frontsector == null) {
                    return false; // solid wall: sight (and the reveal) stops here
                }
                // see-through only while the two-sided line has an open window
                return Math.min(l.frontsector.ceilingheight, l.backsector.ceilingheight)
                     > Math.max(l.frontsector.floorheight, l.backsector.floorheight);
            });
        }
    }

    private void onFrame() {
        final DoomMain<?, ?> d = doom;
        if (d == null) {
            return;
        }
        applyPlayerRequests(d);
        // Frozen-level diagnostic. The engine advances gametic every loop, but the level
        // ticker (P_Ticker: monster thinkers, movers, death states) only runs in gamestate
        // GS_LEVEL and not while paused, so leveltime is the tell. If gametic keeps
        // climbing while leveltime is stuck, every monster stands still and hits never kill
        // because death states cannot advance, all without an engine stall. Log it,
        // throttled, so a single playtest identifies the cause.
        if (state == State.RUNNING) {
            final int gt = d.gametic;
            if (gt - diagLastGametic >= 35) {
                if (diagLastLeveltime >= 0 && d.leveltime == diagLastLeveltime) {
                    LOGGER.warn("P_TICKER FROZEN (monsters=statues, hits don't kill): {}"
                        + " — leveltime stuck at {} while gametic advanced +{}",
                        debugState(), d.leveltime, gt - diagLastGametic);
                }
                diagLastGametic = gt;
                diagLastLeveltime = d.leveltime;
            }
        }
        // DOOM's audio obeys Minecraft's sliders (music was "separate from minecraft"):
        // volumes land on the engine thread, only when a slider actually moved
        applyVolumes(d);
        if (!mochadoom.Engine.RENDER_VIEW) {
            // no software view: nothing to copy, and the engine loop would spin hot with
            // its render cost gone — breathe (tics are timer-driven, unaffected)
            framesPublished++;
            LockSupport.parkNanos(2_000_000L);
        } else if (d.graphicSystem.getScreenImage() instanceof BufferedImage bi) {
            bi.getRGB(0, 0, width, height, renderBuf, 0, width);
            synchronized (bufLock) {
                final int[] t = sharedBuf;
                sharedBuf = renderBuf;
                renderBuf = t;
                framesPublished++;
            }
        }
        // Possession: overwrite the engine player's position/angle with the MC player's,
        // BEFORE the snapshot so what renders is consistent. Raw coordinate set (no blockmap
        // relink yet — sight/aim read coords directly; relink comes with real movement M3b).
        try {
            if (d.players != null && d.players[0] != null && d.players[0].mo != null) {
                final var mo = d.players[0].mo;
                // TELEPORT FOLLOW HOLD: the engine just teleported the player (fog and all)
                // — freeze mirror position writes until the client's mirror lands near the
                // destination, else the next frame's write yanks the body straight back
                // through the teleporter before the client ever saw it move.
                // One read for the whole frame. Every field below has to describe the same
                // instant, which is the point of publishing the mirror as a single value.
                final Mirror mir = mirror.get();
                final int tc = mochadoom.Engine.PLAYER_TELEPORT_COUNT;
                if (tc != teleSyncCount) {
                    teleSyncCount = tc;
                    if (mir.on()) {
                        telePending = true;
                        telePendingFrames = 0;
                        teleDstX = mo.x / 65536.0;
                        teleDstY = mo.y / 65536.0;
                    }
                }
                if (telePending
                    && (Math.hypot(mir.x() - teleDstX, mir.y() - teleDstY) < 64.0
                        || ++telePendingFrames > 90)) {
                    telePending = false;
                }
                // EPOCH GATE: only apply mirror coordinates computed against THIS level
                // instance. A fresh spawn (death restart, next level, /warp reboot) stands
                // untouched until the client has seen it — the un-gated write clobbered
                // every new spawn with the old standing spot run through the new anchors.
                final WorldSnapshot ss = published.snapshot();
                final boolean epochOk = ss != null && mir.epoch() != 0
                    && mir.epoch() == ss.levelEpoch;
                if (mir.on() && epochOk) {
                    if (!mirrorEpochWasOk) {
                        // re-engaging on a fresh instance: baseline the health diff so the
                        // rebirth's full 100 is never billed as a phantom heal
                        lastWrittenHealth = mo.health;
                        mirrorEpochWasOk = true;
                    }
                    mo.flags |= mobj_t.MF_SHOOTABLE; // possessed again: a real target
                    if (!telePending) {
                        // relink through the blockmap (P_Unset/SetThingPosition) so monster
                        // melee-range and thing collision see the true cell, not stale coords
                        d.actions.UnsetThingPosition(mo);
                        mo.x = (int) (mir.x() * 65536.0);
                        mo.y = (int) (mir.y() * 65536.0);
                        d.actions.SetThingPosition(mo);
                        // Refresh floorz and ceilingz for the new position. P_TryMove does
                        // this and the mirror bypasses it, so without the call they keep the
                        // value from wherever the engine last moved this object under its own
                        // movement code. P_ZMovement then clamps the object to a floor it is
                        // nowhere near, and every monster that aims at it, including
                        // P_SpawnMissile's vertical aim, uses that wrong height.
                        //
                        // Once per tic, not once per frame. This method runs at the display
                        // frame rate, and CheckPosition walks the blockmap and every linedef
                        // near the player, which at a few hundred frames a second is enough
                        // work on the engine thread to starve the tic loop and delay the
                        // sound. Nothing reads floorz between tics, so per tic is sufficient.
                        if (d.gametic != lastClipTic) {
                            lastClipTic = d.gametic;
                            d.actions.ThingHeightClip(mo);
                        }
                        mo.z = (int) (mir.z() * 65536.0);
                        // DAMAGE FLOORS (SIGIL E5M1 "lava didn't damage me"):
                        // PlayerInSpecialSector's gate is an EXACT fixed-point compare
                        // (mo.z != floorheight -> no damage), and a z quantized from MC
                        // float coords never lands exactly on it. Standing-close = snap.
                        final int fz = mo.subsector.sector.floorheight;
                        if (Math.abs(mo.z - fz) < (4 << 16)) {
                            mo.z = fz;
                        }
                        // The engine launches the player upward for some attacks, most
                        // visibly A_VileAttack, by writing momz on the player object. The
                        // mirror overwrites that object every frame, so the launch would be
                        // erased before it could do anything and the arch-vile would land
                        // damage with none of its signature throw. Capture an upward impulse
                        // large enough to be deliberate and hand it to the Minecraft side.
                        if (mo.momz > LAUNCH_MIN) {
                            pendingLaunch.accumulateAndGet(mo.momz, Math::max);
                        }
                        mo.momx = mo.momy = mo.momz = 0;
                        mo.angle = ((long) (mir.angleDeg() / 360.0 * 4294967296.0)) & 0xFFFFFFFFL;
                        d.players[0].viewz = mo.z + data.Defines.VIEWHEIGHT;
                    }
                    d.players[0].cheats &= ~player_t.CF_GODMODE; // possessed = mortal

                    // HEALTH TRANSLATION, both directions: the engine's health pool is
                    // slaved to Minecraft's hearts (×5). Whatever the engine changed since
                    // our last write is ITS doing — damage (monsters, barrels, crushers)
                    // or healing (medikits, soulspheres via the pickup lane below) — and
                    // gets billed/credited to MC. The engine never reaches its own death
                    // state: Minecraft owns dying.
                    final int cur = mo.health;
                    if (cur < lastWrittenHealth) {
                        pendingDamage.addAndGet(lastWrittenHealth - cur);
                    } else if (cur > lastWrittenHealth) {
                        pendingHeal.addAndGet(cur - lastWrittenHealth);
                    }
                    // issue A: plus the killing blow's remainder the immortality floor could
                    // NOT take off engine health this tic. Without it a low-health marine is
                    // billed at most (mcHp-1) and never takes the final lethal point, so
                    // Minecraft never dies. Banked at the floor site for players[0] only.
                    final int lethalOverflow = mochadoom.Engine.LETHAL_OVERFLOW.getAndSet(0);
                    if (lethalOverflow > 0) {
                        pendingDamage.addAndGet(lethalOverflow);
                    }
                    final int mcHp = Math.max(1, Math.min(200, mirrorHealth));
                    mo.health = mcHp;
                    d.players[0].health[0] = mcHp;
                    lastWrittenHealth = mcHp;

                    // ISSUE B — MC owns dying, so the engine's immortal local player never runs
                    // DOOM's death -> PlayerReborn cycle; nothing re-grants the starter kit after
                    // a Minecraft respawn. Guarantee the possessed marine always owns at least
                    // fist+pistol (idempotent; every other weapon and key is left untouched).
                    final player_t lp = d.players[0];
                    if (!lp.weaponowned[weapontype_t.wp_fist.ordinal()]
                        || !lp.weaponowned[weapontype_t.wp_pistol.ordinal()]) {
                        lp.weaponowned[weapontype_t.wp_fist.ordinal()] = true;
                        lp.weaponowned[weapontype_t.wp_pistol.ordinal()] = true;
                        if (lp.readyweapon == null
                            || lp.readyweapon.ordinal() >= lp.weaponowned.length
                            || !lp.weaponowned[lp.readyweapon.ordinal()]) {
                            lp.pendingweapon = weapontype_t.wp_pistol;
                        }
                    }

                    // PICKUPS (MARINE FORM ONLY — DOOM's items are the marine's kit; plain
                    // Steve walks over them untouched): mirrored movement bypasses
                    // P_TryMove, so touch specials ourselves — the engine's own
                    // TouchSpecialThing grants ammo, weapons, health, armor, keys, plays
                    // its sounds and removes the item.
                    // gated to TIC ADVANCEMENT: item removal is processed by the tic, so
                    // touching per-FRAME while tics stall re-grants the same item forever
                    // (the armor-0-to-200 / stuck-gold-screen report). No tics, no touches.
                    if (d.gametic != lastPickupTic) {
                        lastPickupTic = d.gametic;
                        if (marineMode) {
                            final thinker_t cap = d.actions.getThinkerCap();
                            for (thinker_t t = cap.next; t != cap; t = t.next) {
                                if (t instanceof mobj_t it && it != mo
                                    && (it.flags & mobj_t.MF_SPECIAL) != 0) {
                                    final int dist = it.radius + mo.radius;
                                    if (Math.abs(it.x - mo.x) < dist
                                        && Math.abs(it.y - mo.y) < dist) {
                                        d.actions.TouchSpecialThing(it, mo);
                                    }
                                }
                            }
                        } else if (!scavengeSprites.isEmpty()) {
                            // STEVE SCAVENGING (M-CROSSOVER slice A): a plain Minecraft
                            // player converts config-listed DOOM items into MC resources.
                            // NOT TouchSpecialThing — Steve must never receive doom
                            // ammo/armor state. Consume engine-side (spectators see it
                            // vanish), queue the sprite for the client to convert.
                            final thinker_t cap = d.actions.getThinkerCap();
                            for (thinker_t t = cap.next; t != cap; t = t.next) {
                                if (t instanceof mobj_t it && it != mo
                                    && (it.flags & mobj_t.MF_SPECIAL) != 0) {
                                    final int dist = it.radius + mo.radius;
                                    if (Math.abs(it.x - mo.x) < dist
                                        && Math.abs(it.y - mo.y) < dist) {
                                        final String spr = spriteNameOf(it);
                                        if (spr != null && scavengeSprites.contains(spr)
                                            && pendingScavenge.size() < 64) {
                                            pendingScavenge.add(spr);
                                            d.doomSound.StartSound(mo,
                                                data.sounds.sfxenum_t.sfx_itemup);
                                            d.actions.RemoveMobj(it);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    mirrorWasOn = true;

                    // interactions you performed, through the engine's OWN trigger code:
                    // walkover crossings (doors, teleports, exits) + the USE key. During a
                    // teleport hold the queued crossings came from PRE-teleport movement —
                    // firing them at the destination could bounce us straight back.
                    if (!telePending) {
                        int[] c;
                        while ((c = pendingCross.poll()) != null) {
                            if (c[0] >= 0 && c[0] < d.levelLoader.numlines) {
                                d.actions.CrossSpecialLine(d.levelLoader.lines[c[0]], c[1], mo);
                            }
                        }
                        if (pendingUse.getAndSet(false)) {
                            d.actions.UseLines(d.players[0]);
                        }
                        Integer sl;
                        while ((sl = pendingShoot.poll()) != null) {
                            if (sl >= 0 && sl < d.levelLoader.numlines) {
                                d.actions.ShootSpecialLine(mo, d.levelLoader.lines[sl]);
                            }
                        }
                        if (pendingAlert.getAndSet(false) && mo.subsector != null) {
                            d.actions.NoiseAlert(mo, mo);
                        }

                    } else {
                        pendingCross.clear();
                        pendingUse.set(false);
                        pendingAlert.set(false);
                        pendingShoot.clear();
                    }
                } else if (mir.on()) {
                    // possessed, but the mirror is stamped with a STALE level instance (the
                    // client hasn't seen the new spawn yet). Leave the freshly spawned body
                    // exactly where the engine put it, and drop interactions computed
                    // against the old map — they'd fire the wrong lines here. Possession
                    // itself continues: no statue-release.
                    pendingCross.clear();
                    pendingUse.set(false);
                    mirrorEpochWasOk = false;
                    mirrorWasOn = true;
                } else {
                    pendingCross.clear();
                    pendingUse.set(false);
                    pendingShoot.clear();  // queued against a map that is no longer standing
                    pendingAlert.set(false);
                    pendingDamage.set(0);
                    mirrorEpochWasOk = false;
                    mochadoom.Engine.LETHAL_OVERFLOW.set(0); // not possessing: drop any stale bill
                    if (mirrorWasOn) {
                        // released: the abandoned body must be a NON-ENTITY, not a decoy
                        // — god mode alone left a statue that monsters saw FIRST and
                        // locked onto, ignoring the real Minecraft player walking by.
                        // Non-shootable: A_Chase drops it and P_LookForPlayers (health 0)
                        // skips it; both restore the moment someone possesses again.
                        d.players[0].cheats |= player_t.CF_GODMODE;
                        d.players[0].mo.flags &= ~mobj_t.MF_SHOOTABLE;
                        d.players[0].health[0] = 0;
                        mirrorWasOn = false;
                    }
                }
                tickRemoteBodies(d);
                applyThingDamage(d);
                final int pain = pendingPain;
                if (pain > 0 && d.players[0].mo != null) {
                    pendingPain = 0;
                    d.players[0].damagecount = Math.min(100, d.players[0].damagecount + pain);
                    d.doomSound.StartSound(d.players[0].mo, data.sounds.sfxenum_t.sfx_plpain);
                }
                // voice permissions: marines grunt in doomguy's voice, plain players
                // keep their own (per player — 1..3 are other Minecraft people)
                final java.util.Set<Object> voiced = new java.util.HashSet<>(4);
                if (marineMode) {
                    voiced.add(d.players[0]);
                }
                for (var e : remoteBodies.entrySet()) {
                    final RemoteBody rb = e.getValue();
                    if (rb.playerIdx > 0
                        && (com.blackwithersteve.lattedoom.play.MarineRoster.SERVER.contains(e.getKey())
                            || com.blackwithersteve.lattedoom.play.MarineRoster.CLIENT.contains(e.getKey()))) {
                        voiced.add(d.players[rb.playerIdx]);
                    }
                }
                mochadoom.Engine.VOICED_PLAYERS = voiced;
                // damage thrust: translate the engine's last hit on OUR player into a
                // pending Minecraft knockback (the mirror wiped the engine-side push)
                if (mochadoom.Engine.HURT_PLAYER == d.players[0]) {
                    mochadoom.Engine.HURT_PLAYER = null;
                    knockDirX = mochadoom.Engine.HURT_DIR_X;
                    knockDirY = mochadoom.Engine.HURT_DIR_Y;
                    knockThrust = mochadoom.Engine.HURT_THRUST;
                    knockPending = true;
                }
            }
        } catch (Throwable t) { noteSwallowed("site5", t);
        }
        if (mapRestartPending) {
            // MC death law: the player respawned after dying in the level -> vanilla SP
            // restart. On the engine thread at a frame boundary, like every engine call.
            // Only a LIVE level restarts: honored mid-advance, this converted a
            // completed level into an InitNew — and every InitNew is a weapon wipe.
            mapRestartPending = false;
            try {
                if (d.gamestate == defines.gamestate_t.GS_LEVEL
                    && d.gameskill != null && d.gamemap > 0) {
                    d.DeferedInitNew(d.gameskill, d.gameepisode, d.gamemap);
                }
            } catch (Throwable t) { noteSwallowed("site6", t);
            }
        }
        // ONE live engine per client. Every replacement path is supposed to terminate
        // its predecessor, but a slow frame (a big level mid-load) can outlive the
        // bounded wait, and a survivor kept playing its level's audio under the new
        // game. Whatever the path, an engine that is no longer the current one ends
        // itself here, at its next frame.
        if (LIVE.get() != this && !terminate) {
            System.err.println("[lattedoom] orphaned engine detected, self-terminating");
            terminate = true;
            frozen = false;
        }
        if (terminate) {
            // programmatic shutdown (e.g. /doomwarp reboots the engine into another map):
            // thrown ON the engine thread, unwinds setupLoop exactly like a menu quit.
            throw new mochadoom.DoomQuitSignal();
        }
        while (frozen && state == State.RUNNING) {
            applyVolumes(d); // sliders keep working while the moment is frozen
            LockSupport.parkNanos(50_000_000L);
        }
    }

    private void applyVolumes(DoomMain<?, ?> d) {
        if (wantSfxVol != appliedSfxVol || wantMusicVol != appliedMusicVol) {
            appliedSfxVol = wantSfxVol;
            appliedMusicVol = wantMusicVol;
            try {
                final int sfx = Math.max(0, Math.min(15, appliedSfxVol));
                final int music = Math.max(0, Math.min(15, appliedMusicVol));
                d.doomSound.SetSfxVolume(sfx);
                // "MIDI too quiet" fix: DavidMusicModule.SetMusicVolume divides by 127f, so it
                // expects a 0-127 argument (every other engine caller sends 0-15 * 8). DoomHost
                // was passing raw 0-15, capping max music at ~15/127 ≈ 12% loudness. Map our
                // 0-15 into the 0-127 domain so the top of the slider is full loudness.
                d.doomSound.SetMusicVolume(Math.round(music / 15f * 127f));
            } catch (Throwable t) { noteSwallowed("site7", t);
            }
        }
    }

    /** The debug framebuffer screen wants the software view rendered (expensive). The
     * Minecraft world is the renderer otherwise, and the engine skips all drawing. */
    public void setViewRender(boolean on) {
        mochadoom.Engine.RENDER_VIEW = on;
    }

    /** Ask the engine to shut down cleanly at the next frame boundary (audio torn down, onQuit fires). */
    public void terminate() {
        terminate = true;
        frozen = false; // a parked engine must reach the next frame to see the flag
    }

    /**
     * Terminate AND block (up to {@code timeoutMs}) until the engine thread has actually
     * unwound — which is when {@link #shutdownAudio()} has run and this engine's sound/music
     * channels are silenced. Callers that immediately boot a REPLACEMENT engine (the
     * /doomwarp reboot) must use this, not bare {@link #terminate()}: otherwise the old
     * engine is still alive and audible when the new one starts, so you keep hearing the
     * previous level's monsters (and two engines briefly race on the shared audio system).
     * Best-effort: if the thread doesn't finish in time we return anyway (its onQuit guard
     * already stops a late shutdown from clobbering the new engine).
     */
    public void terminateAndAwait(long timeoutMs) {
        terminate();
        final Thread t = engineThread;
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- REMOTE BODIES (B4): other Minecraft players possess players[1..3] — DOOM is
    // four-player native. Monsters hunt them (P_LookForPlayers), their BT_ATTACK fires
    // real ballistics, BT_USE opens doors, engine damage bills their MC hearts.

    /** One remote player's live state, written by the network, read by the engine thread. */
    public static final class RemoteBody {
        public volatile double x, y, z, angleDeg;
        public volatile int buttons;      // bit0 fire, bit1 use
        public volatile int slot = -1;    // 0..6 requested weapon, -1 none
        int lastClipTic = -1;             // engine-thread only
        public volatile int healthMc = 100;
        public volatile long lastSeenMs;
        final java.util.Queue<int[]> crossings = new java.util.concurrent.ConcurrentLinkedQueue<>();
        public final java.util.concurrent.atomic.AtomicInteger pendingDamage =
            new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicInteger pendingHeal =
            new java.util.concurrent.atomic.AtomicInteger();
        int playerIdx = -1;
        int lastWrittenHealth = 100;
        int lastSlot = -1;
    }

    private final java.util.Map<java.util.UUID, RemoteBody> remoteBodies =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The network hands over a friend's latest state (crossed lines fire their triggers). */
    public void acceptPresence(java.util.UUID id, double x, double y, double z,
                               double angleDeg, int buttons, int slot, int healthMc,
                               int[][] crossedLines) {
        final RemoteBody rb = remoteBodies.computeIfAbsent(id, k -> new RemoteBody());
        rb.x = x;
        rb.y = y;
        rb.z = z;
        rb.angleDeg = angleDeg;
        rb.buttons = buttons;
        rb.slot = slot;
        rb.healthMc = healthMc;
        rb.lastSeenMs = System.currentTimeMillis();
        if (crossedLines != null) {
            for (int[] c : crossedLines) {
                rb.crossings.add(c);
            }
        }
    }

    public java.util.Map<java.util.UUID, RemoteBody> remoteBodies() {
        return remoteBodies;
    }

    /** Engine-thread: spawn/mirror/despawn every remote player body (mirror-of-mirrors). */
    private void tickRemoteBodies(DoomMain<?, ?> d) {
        final long now = System.currentTimeMillis();
        for (var e : remoteBodies.entrySet()) {
            final RemoteBody rb = e.getValue();
            if (now - rb.lastSeenMs > 1500) {
                // friend left the level (or the game): the body goes with them
                if (rb.playerIdx > 0 && d.playeringame[rb.playerIdx]) {
                    if (d.players[rb.playerIdx].mo != null) {
                        d.actions.RemoveMobj(d.players[rb.playerIdx].mo);
                        d.players[rb.playerIdx].mo = null;
                    }
                    d.playeringame[rb.playerIdx] = false;
                }
                remoteBodies.remove(e.getKey());
                continue;
            }
            if (rb.playerIdx < 0) {
                for (int i = 1; i < 4; i++) {
                    boolean taken = d.playeringame[i];
                    for (RemoteBody o : remoteBodies.values()) {
                        taken |= o.playerIdx == i;
                    }
                    if (!taken) {
                        rb.playerIdx = i;
                        break;
                    }
                }
                if (rb.playerIdx < 0) {
                    continue; // engine is full (4 players): spectate-only
                }
            }
            final int idx = rb.playerIdx;
            if (!d.playeringame[idx] || d.players[idx].mo == null) {
                if (d.playerstarts[idx] == null) {
                    continue; // map without coop starts
                }
                d.playeringame[idx] = true;
                d.players[idx].playerstate = data.Defines.PST_REBORN;
                d.actions.SpawnPlayer(d.playerstarts[idx]);
                rb.lastWrittenHealth = 100;
            }
            final var mo = d.players[idx].mo;
            if (mo == null) {
                continue;
            }
            d.actions.UnsetThingPosition(mo);
            mo.x = (int) (rb.x * 65536.0);
            mo.y = (int) (rb.y * 65536.0);
            d.actions.SetThingPosition(mo);
            if (d.gametic != rb.lastClipTic) {
                rb.lastClipTic = d.gametic;
                d.actions.ThingHeightClip(mo); // same floor refresh, likewise once per tic
            }
            mo.z = (int) (rb.z * 65536.0);
            mo.momx = mo.momy = mo.momz = 0;
            mo.angle = ((long) (rb.angleDeg / 360.0 * 4294967296.0)) & 0xFFFFFFFFL;
            d.players[idx].viewz = mo.z + data.Defines.VIEWHEIGHT;
            d.players[idx].cheats &= ~player_t.CF_GODMODE;
            // health slaved to THEIR hearts, damage billed back (same law as player 0)
            final int cur = mo.health;
            if (cur < rb.lastWrittenHealth) {
                rb.pendingDamage.addAndGet(rb.lastWrittenHealth - cur);
            } else if (cur > rb.lastWrittenHealth) {
                rb.pendingHeal.addAndGet(cur - rb.lastWrittenHealth);
            }
            final int hp = Math.max(1, Math.min(200, rb.healthMc));
            mo.health = hp;
            d.players[idx].health[0] = hp;
            rb.lastWrittenHealth = hp;
            // their inputs, through the engine's OWN ticcmd lane for this player
            int buttons = 0;
            if ((rb.buttons & 1) != 0) {
                buttons |= data.Defines.BT_ATTACK;
            }
            if ((rb.buttons & 2) != 0) {
                buttons |= data.Defines.BT_USE;
            }
            if (rb.slot >= 0 && rb.slot != rb.lastSlot) {
                buttons |= data.Defines.BT_CHANGE | (rb.slot << data.Defines.BT_WEAPONSHIFT);
                rb.lastSlot = rb.slot;
            }
            for (int k = 0; k < 3; k++) {
                final var cmd = d.netcmds[idx][(d.gametic + k) % data.Defines.BACKUPTICS];
                cmd.buttons = (char) buttons;
                cmd.forwardmove = 0;
                cmd.sidemove = 0;
                cmd.angleturn = 0;
                cmd.chatchar = 0;
            }
            int[] c;
            while ((c = rb.crossings.poll()) != null) {
                if (c[0] >= 0 && c[0] < d.levelLoader.numlines) {
                    d.actions.CrossSpecialLine(d.levelLoader.lines[c[0]], c[1], mo);
                }
            }
        }
    }

    /** Possess (or release) the engine player: coords in DOOM units, angle in DOOM degrees.
     * epoch = the levelEpoch of the snapshot the coordinates were computed against; the
     * engine ignores mirror positions from a stale level instance (0 = legacy/none). */
    public void setPlayerMirror(boolean on, double x, double y, double z, double angleDeg,
                                long epoch) {
        mirror.set(on ? new Mirror(true, x, y, z, angleDeg, epoch) : MIRROR_OFF);
    }

    /**
     * Wakes every monster that can hear the mirrored player, through the engine's own
     * P_NoiseAlert. A DOOM weapon does this from inside the engine when it fires; a
     * Minecraft one is fired outside it, so a bow or a swing has to say so explicitly or
     * the level stays asleep around a player who is plainly attacking.
     */
    public void alertMonsters() {
        pendingAlert.set(true);
    }

    /**
     * Types a cheat into the engine, one key event per character. The engine's own cheat
     * matcher in the status bar consumes them, so every code the WAD's engine supports works
     * without this class knowing any of them. Cheats are normally typed during play, which
     * is impossible here because the keyboard belongs to Minecraft.
     */
    public void typeCheat(String code) {
        for (char c : code.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            final g.Signals.ScanCode sc =
                com.blackwithersteve.lattedoom.DoomKeyMap.forChar(c);
            if (sc != null) {
                pendingKeys.add(sc);
            }
        }
    }

    /** Cheat characters waiting to be delivered, one per engine frame so the matcher sees
     * them as separate presses. */
    private final java.util.concurrent.ConcurrentLinkedQueue<g.Signals.ScanCode> pendingKeys =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Grants engine inventory to the possessed player. Every field is the engine's own, so
     * the status bar, weapon switching and ammunition limits behave exactly as if the items
     * had been picked up. A negative count means the maximum for that slot.
     */
    public void grant(String what, int amount) {
        pendingGrants.add(new Object[]{what, amount});
    }

    /** A weapon to select, or null. Posting number keys does not work for this: a down and
     * up delivered in the same frame arrive in one D_ProcessEvents batch and G_BuildTiccmd
     * never observes the key, so the switch silently does not happen. */
    private final java.util.concurrent.atomic.AtomicReference<String> pendingWeapon =
        new java.util.concurrent.atomic.AtomicReference<>();

    /** Selects a weapon by the names {@link #grant} uses. Applied on the engine thread. */
    public void selectWeapon(String name) {
        pendingWeapon.set(name);
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> pendingGrants =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private void applyGrants(doom.DoomMain<?, ?> d) {
        final var pl = d.players[0];
        final String want = pendingWeapon.getAndSet(null);
        if (want != null) {
            final int wi = weaponIndex(want);
            if (wi >= 0 && wi < weapontype_t.NUMWEAPONS.ordinal() && pl.weaponowned[wi]) {
                pl.pendingweapon = weapontype_t.values()[wi];
            }
        }
        Object[] g;
        while ((g = pendingGrants.poll()) != null) {
            final String what = (String) g[0];
            final int n = (Integer) g[1];
            switch (what) {
                case "health" -> {
                    pl.health[0] = Math.min(200, Math.max(1, n));
                    if (pl.mo != null) {
                        pl.mo.health = pl.health[0];
                    }
                }
                case "armor" -> {
                    pl.armorpoints[0] = Math.min(200, Math.max(0, n));
                    pl.armortype = pl.armorpoints[0] > 100 ? 2 : 1;
                }
                case "keys" -> java.util.Arrays.fill(pl.cards, true);
                case "backpack" -> {
                    if (!pl.backpack) {
                        pl.backpack = true;
                        for (int i = 0; i < pl.maxammo.length; i++) {
                            pl.maxammo[i] *= 2;
                        }
                    }
                }
                default -> {
                    final int wi = weaponIndex(what);
                    if (wi >= 0) {
                        pl.weaponowned[wi] = true;
                    } else {
                        final int ai = ammoIndex(what);
                        if (ai >= 0) {
                            pl.ammo[ai] = n < 0 ? pl.maxammo[ai]
                                : Math.min(pl.maxammo[ai], Math.max(0, n));
                        }
                    }
                }
            }
        }
    }

    private static int weaponIndex(String name) {
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

    private static int ammoIndex(String name) {
        return switch (name) {
            case "bullets" -> 0;
            case "shells" -> 1;
            case "cells" -> 2;
            case "rockets" -> 3;
            default -> -1;
        };
    }

    /**
     * A shot crossed this line: fire the engine's impact handler. Gun-triggered specials are
     * normally raised from inside P_LineAttack, which only DOOM weapons run, so a Minecraft
     * arrow or swing has to raise them explicitly. The engine decides whether the line's
     * special is shootable at all, so nothing needs classifying here.
     */
    public void requestShoot(int lineIdx) {
        pendingShoot.add(lineIdx);
    }

    /** Faults already reported by {@link #noteSwallowed}; each site speaks once so a
     * per-frame failure cannot flood the log. */
    private final java.util.Set<String> swallowedSeen =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Records a fault that is deliberately not allowed to propagate. These sites exist so a
     * single bad frame cannot kill the engine loop, but swallowing them silently leaves no
     * trail when something is genuinely wrong, which is how a real fault can run for a whole
     * session unnoticed.
     */
    private void noteSwallowed(String where, Throwable t) {
        if (!swallowedSeen.add(where)) {
            return;
        }
        // The engine thread must not reach Minecraft or Fabric code: the headless harnesses
        // run this class with no Fabric runtime at all, where the diagnostic writer throws.
        LOGGER.warn("Latte Doom suppressed a fault in {}", where, t);
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<Integer> pendingShoot =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** The possessed player crossed a special line: fire the engine's walkover handler. */
    public void requestCross(int lineIdx, int side) {
        pendingCross.add(new int[]{lineIdx, side});
    }

    /** The possessed player pressed USE: run the engine's P_UseLines at his position. */
    public void requestUse() {
        pendingUse.set(true);
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingMessages =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Every heads-up message since the last call, in order. Never drops one to sampling. */
    public java.util.List<String> drainMessages() {
        if (pendingMessages.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<String> out = new java.util.ArrayList<>();
        String m;
        while ((m = pendingMessages.poll()) != null) {
            out.add(m);
        }
        return out;
    }

    /** Upward momentum below this is ordinary physics rather than an attack throwing the
     * player. A_VileAttack gives 1000/mass, which is ten map units per tic for a player. */
    private static final int LAUNCH_MIN = 4 * 65536;

    private final java.util.concurrent.atomic.AtomicInteger pendingLaunch =
        new java.util.concurrent.atomic.AtomicInteger();

    /** The largest upward impulse the engine applied to the player since the last drain, in
     * map units per tic, or zero. */
    public double drainLaunch() {
        return pendingLaunch.getAndSet(0) / 65536.0;
    }

    /** DOOM hit points taken since the last drain (Minecraft turns them into hearts). */
    public int drainDamage() {
        return pendingDamage.getAndSet(0);
    }

    /** DOOM hit points healed since the last drain (medikits become hearts too). */
    public int drainHeal() {
        return pendingHeal.getAndSet(0);
    }

    /** Slave the engine's health pool to Minecraft's hearts (DOOM points, hearts × 5). */
    public void setPlayerHealth(int doomPoints) {
        mirrorHealth = doomPoints;
    }

    /** Marine form on/off: gates DOOM-native pickups (plain Steve leaves the kit alone). */
    public void setMarineMode(boolean marine) {
        marineMode = marine;
    }

    /** Which DOOM item sprites plain Steve may scavenge (empty set = off). */
    public void setScavengeSprites(java.util.Set<String> sprites) {
        scavengeSprites = sprites != null ? sprites : java.util.Set.of();
    }

    /** Sprites Steve consumed since the last drain (the client converts via config). */
    public java.util.List<String> drainScavenge() {
        if (pendingScavenge.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<String> out = new java.util.ArrayList<>(4);
        String s;
        while ((s = pendingScavenge.poll()) != null) {
            out.add(s);
        }
        return out;
    }

    /** Sprite name of a mobj ("MEDI"), DEH-extended ordinals included; null if unknown. */
    private static String spriteNameOf(mobj_t m) {
        final int ord = m.mobj_spritenum >= 0 ? m.mobj_spritenum
            : (m.mobj_sprite != null ? m.mobj_sprite.ordinal() : -1);
        if (ord < 0) {
            return null;
        }
        final data.spritenum_t[] all = data.spritenum_t.values();
        if (ord < all.length) {
            final String n = all[ord].name();
            return n.startsWith("SPR_") ? n.substring(4) : n;
        }
        return deh.DehState.spriteNameOf(ord);
    }

    /** One-line engine vitals for the freeze hunt: does the TIC advance? who paused what? */
    public String debugState() {
        final DoomMain<?, ?> d = doom;
        if (d == null) {
            return "state=" + state + " (no engine)";
        }
        return "state=" + state + " tic=" + d.gametic + " leveltime=" + d.leveltime
            + " gamestate=" + d.gamestate + " frames=" + framesPublished
            + " frozen=" + frozen
            + " paused=" + d.paused + " menu=" + d.menuactive + " demo=" + d.demoplayback;
    }

    /** Where the engine thread is RIGHT NOW — the freeze watchdog logs this to name the
     * exact wait that starves the tics (net wait, ticker, sound, wipe...). */
    public String engineStackDump() {
        final Thread t = engineThread;
        if (t == null) {
            return "(no engine thread)";
        }
        final StringBuilder sb = new StringBuilder(t.getName())
            .append(" [").append(t.getState()).append("]");
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\n  at ").append(e);
        }
        return sb.toString();
    }

    /** Latest world snapshot, or null while no level is running. Safe from any thread. */
    public WorldSnapshot worldSnapshot() {
        return published.snapshot();
    }

    /** The engine stage at a completed tic, kind on the {@link #gamestateKind()}
     * encoding — the ONLY view of the engine's stage the client may consume. */
    public record PhaseSnap(int kind, long tick) {}

    /**
     * The snapshot and the stage as ONE value, published in ONE write.
     *
     * They used to be two volatile fields written one after the other with work in
     * between, so a reader landing in that gap saw a new snapshot beside a stale stage. On
     * a level exit that reads as a level standing with no snapshot, which is exactly the
     * window the tic-boundary publication exists to close; transitionProbe caught it about
     * one run in six. A single reference makes the pair impossible to tear.
     */
    private record Published(WorldSnapshot snapshot, PhaseSnap phase) { }

    private volatile Published published = new Published(null, null);
    private long phaseTicks; // engine-thread only

    private static int kindOf(DoomMain<?, ?> d) {
        if (d == null || d.gamestate == null) {
            return -1;
        }
        return switch (d.gamestate) {
            case GS_LEVEL -> 0;
            case GS_INTERMISSION -> 1;
            case GS_FINALE -> 2;
            case GS_DEMOSCREEN -> 3;
            default -> -1;
        };
    }

    /**
     * True when the engine is ALIVE and running but not in a playable level right now —
     * the intermission tally after you cross an exit, the text finale, or the brief load
     * between maps. {@link #worldSnapshot()} returns null in this window (capture only runs
     * in GS_LEVEL) even though the engine is healthy and about to hand us the next map.
     * Callers must NOT tear the world down here: doing so drops the player into the empty
     * void dimension (the "statue freeze + fall through the world" on level completion).
     * Reads the tic-boundary publication, never the live gamestate: the live value flips
     * to GS_LEVEL before the next level exists, and this method answering false in that
     * window was the level-finish ejection bug.
     */
    public boolean isBetweenLevels() {
        final PhaseSnap p = published.phase();
        return state == State.RUNNING && p != null && p.kind() != 0;
    }

    /** The engine's exact stage, for the DOOM-shell flow (intermission view, episode end):
     * 0=in a level, 1=intermission tally, 2=text finale, 3=title/demo screen, -1=none/booting.
     * Tic-boundary publication, consistent with {@link #worldSnapshot()}. */
    public int gamestateKind() {
        final PhaseSnap p = published.phase();
        return state != State.RUNNING || p == null ? -1 : p.kind();
    }

    /** True once this engine has published at least one in-level snapshot — distinguishes a
     * REAL post-level intermission from the boot-time non-level states. */
    public boolean hadLevel() {
        return hadLevel;
    }

    /** Is the engine's own menu overlay up right now? Drives the in-level "menu only"
     * screen: it closes itself the moment the player backs out of the menu. */
    public boolean isMenuActive() {
        final DoomMain<?, ?> d = doom;
        return d != null && d.menuactive;
    }

    /** The intermission tally, straight from the engine's wminfo — what the NATIVE
     * Minecraft-rendered intermission screen draws. Volatile copy, engine thread writes. */
    public static final class InterSnap {
        public int epsd;         // 0-based episode of the FINISHED map
        public int last;         // 0-based finished map
        public int next;         // 0-based next map
        public int kills, items, secret;          // player 0 raw counts
        public int maxKills, maxItems, maxSecret; // level totals (min 1, vanilla guard)
        public int timeTics, parTics;             // level time + par, in tics
    }

    private volatile InterSnap interSnap;

    public InterSnap interSnap() {
        return interSnap;
    }

    /** The engine's current episode (1-based) — the native finale picks its text/flat by it. */
    public int episodeNow() {
        final DoomMain<?, ?> d = doom;
        return d != null ? Math.max(1, d.gameepisode) : 1;
    }

    /**
     * Vanilla single-player death law, on demand: restart the CURRENT map fresh (InitNew
     * forces every player to PST_REBORN — the fist+pistol+50-bullets rebirth). Minecraft owns
     * dying, so the engine never runs its own death->reborn cycle; the host calls this when
     * the MC player respawns after dying inside the level.
     */
    public void requestMapRestart() {
        mapRestartPending = true;
    }

    /**
     * Copy the latest published frame (ARGB) into dst. Returns the frame counter
     * so callers can skip texture uploads when nothing new arrived.
     */
    public long copyFrame(int[] dst) {
        synchronized (bufLock) {
            if (sharedBuf != null && dst.length >= sharedBuf.length) {
                System.arraycopy(sharedBuf, 0, dst, 0, sharedBuf.length);
            }
            return framesPublished;
        }
    }

    public void postKey(ScanCode sc, boolean down) {
        final DoomMain<?, ?> d = doom;
        if (d != null && sc != null && sc != ScanCode.SC_NULL) {
            d.PostEvent(down ? sc.doomEventDown : sc.doomEventUp);
        }
    }

    /**
     * Feed a relative mouse motion / button state. dx/dy are raw pixels of mouse
     * travel (GLFW convention: +dy = moving down); DOOM wants +y = forward, and the
     * stock frontend scales deltas by 4.
     */
    public void postMouse(double dx, double dy, int buttonBits) {
        final DoomMain<?, ?> d = doom;
        if (d == null) {
            return;
        }
        final int sx = (int) Math.round(dx * 4.0);
        final int sy = (int) Math.round(-dy * 4.0);
        if (mouseEvent.processed) {
            mouseEvent.x = sx;
            mouseEvent.y = sy;
        } else {
            mouseEvent.x += sx;
            mouseEvent.y += sy;
        }
        mouseEvent.buttons = buttonBits;
        mouseEvent.resetNotify();
        d.PostEvent(mouseEvent);
    }

    /** Force-release every held key inside the engine (used when the screen closes). */
    public void cancelKeys() {
        final DoomMain<?, ?> d = doom;
        if (d != null) {
            d.PostEvent(event_t.CANCEL_KEYS);
        }
    }

    /**
     * Freeze/unfreeze the engine. Frozen: the engine thread parks at the next page
     * flip and DOOM stops consuming time entirely (a paused emulator, not DOOM's
     * own pause). Held keys are cancelled so nothing sticks on resume.
     */
    public void setFrozen(boolean freeze) {
        if (freeze) {
            cancelKeys();
        }
        this.frozen = freeze;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public State state() {
        return state;
    }

    public Throwable crashCause() {
        return crash;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long framesPublished() {
        synchronized (bufLock) {
            return framesPublished;
        }
    }
}
