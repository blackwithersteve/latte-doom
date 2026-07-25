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
 * Owns the embedded engine: boots it on a daemon thread, publishes a {@link WorldSnapshot}
 * after every tic, injects input into its event queue, mirrors the Minecraft player into its
 * player object, and can freeze it at a frame boundary. No Minecraft imports, so the
 * headless harnesses drive it the same way the mod does.
 */
public final class DoomHost {

    public enum State { BOOTING, RUNNING, QUIT, CRASHED }

    private volatile DoomMain<?, ?> doom;
    private volatile Thread engineThread;
    private volatile State state = State.BOOTING;
    private volatile Throwable crash;
    private volatile boolean frozen;

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("lattedoom");
    // Diagnostics for the level ticker freezing while the tic counter still advances.
    private int diagLastGametic;
    private int diagLastLeveltime = -1;
    private volatile boolean terminate;

    // ---- Player mirroring: the Minecraft player's position is written into the engine's
    // own player object, so that every sense a monster uses (sight, aim, infighting)
    // tracks the real player. Applied on the engine thread at a frame boundary. ----
    private volatile boolean mirrorOn;
    private boolean mirrorWasOn; // engine-thread only
    private volatile double mirrorX, mirrorY, mirrorZ, mirrorAngleDeg;
    /** The level epoch the client's mirrored coordinates were computed against, echoed back
     * from the snapshot. Only positions stamped with the current epoch are applied, so a
     * fresh spawn is never overwritten by coordinates converted through the previous map's
     * origin. */
    private volatile long mirrorEpoch;
    private boolean mirrorEpochWasOk; // engine-thread only: rising-edge health re-baseline
    // ---- Teleport follow, engine thread only. After a teleport moves the player, mirror
    // position writes are held until the client's own mirror lands near the destination.
    // Otherwise the next write returns the player through the teleporter before the client
    // ever saw it move, so the fog plays but the player stays put. ----
    private int teleSyncCount;
    private boolean telePending;
    private double teleDstX, teleDstY;
    private int telePendingFrames;
    /** Interactions the mirrored player performed, fired through the engine's handlers. */
    private final java.util.concurrent.ConcurrentLinkedQueue<int[]> pendingCross =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean pendingUse =
        new java.util.concurrent.atomic.AtomicBoolean();
    /** Engine hit points taken off the mirrored player since the last drain. */
    private final java.util.concurrent.atomic.AtomicInteger pendingDamage =
        new java.util.concurrent.atomic.AtomicInteger();
    /** Engine hit points granted by healing items since the last drain. */
    private final java.util.concurrent.atomic.AtomicInteger pendingHeal =
        new java.util.concurrent.atomic.AtomicInteger();
    /** The Minecraft player's health in engine points, written into the engine each frame. */
    private volatile int mirrorHealth = 100;
    private int lastWrittenHealth = 100; // engine-thread only
    /** Whether the local player is transformed; DOOM items are collected only then. */
    private volatile boolean marineMode;
    private int lastPickupTic = -1; // engine-thread only
    /** Sprite names an untransformed player may consume, from the item conversion table;
     * an empty set disables the feature. The engine consumes and queues them, and the
     * client performs the conversion. */
    private volatile java.util.Set<String> scavengeSprites = java.util.Set.of();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingScavenge =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private volatile int width = 320;
    private volatile int height = 200;

    private int[] renderBuf;                  // engine thread only
    private int[] sharedBuf;                  // guarded by bufLock
    private final Object bufLock = new Object();
    private long framesPublished;

    /** Latest world-state snapshot, null while no level is running. Swapped atomically, so
     * readers always see a fully built copy. */
    private volatile WorldSnapshot snapshot;

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
     * Boots the engine asynchronously. WAD loading and engine initialisation happen on the
     * engine thread; poll {@link #state()} until it leaves {@code BOOTING}.
     *
     * @param iwad    path to the base WAD
     * @param dataDir directory holding the engine's configuration and savegames
     */
    public static DoomHost boot(Path iwad, Path dataDir, List<String> extraArgs,
                                Runnable onQuit, Consumer<Throwable> onCrash) {
        final DoomHost host = new DoomHost(onQuit, onCrash);
        final Thread thread = new Thread(() -> host.run(iwad, dataDir, extraArgs), "LatteDoom-Engine");
        thread.setDaemon(true);
        host.engineThread = thread;
        thread.start();
        return host;
    }

    private void run(Path iwad, Path dataDir, List<String> extraArgs) {
        try {
            mochadoom.Engine.PLAYERS_IMMORTAL = true; // Minecraft owns player death
            mochadoom.Engine.TIC_TAP = this::onTic;   // per-tic world publish
            WorldSnapshot.newBoot(); // fresh level epoch, so stale mirrors are rejected
            // Sound events are surfaced to the host; there is one engine per client.
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
            data.dstrings.SAVEGAMENAME = dataDir.resolve("doomsav").toString();

            final List<String> argv = new ArrayList<>();
            argv.add("-iwad");
            argv.add(iwad.toAbsolutePath().toString());
            argv.addAll(extraArgs);

            final Engine engine = Engine.initEmbedded(this::onFrame, argv.toArray(new String[0]));
            final DoomMain<?, ?> d = engine.getDOOM();
            this.width = d.graphicSystem.getScreenWidth();
            this.height = d.graphicSystem.getScreenHeight();
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

    /** Minecraft-side damage on a transformed player. Raises the damage flash and pain
     * sound (damagecount + plpain) that P_DamageMobj would have, since it never saw the
     * hit. */
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

    // ---- minecraft -> DOOM damage (fists, swords, arrows hitting demons/barrels) ----
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
                // A hit arrived for an object that cannot take it; log it, since silently
                // dropping damage is hard to diagnose from the symptom alone.
                LOGGER.warn("applyThingDamage DROP id={} dmg={} reason={}",
                    mobjId, dmg, target == null ? "target-not-found" : "not-shootable");
                continue;
            }
            // Source = the attacker's body in this engine (players[0] = the owner,
            // players[1..3] = remote bodies), so the target retaliates against them
            p.mobj_t source = d.players[0].mo;
            if (attacker != null) {
                final RemoteBody rb = remoteBodies.get(attacker);
                if (rb != null && rb.playerIdx > 0 && d.players[rb.playerIdx].mo != null) {
                    source = d.players[rb.playerIdx].mo;
                }
            }
            final int hpBefore = target.health;
            d.actions.DamageMobj(target, source, source, dmg);
            // HP dropping without a death means the death state is not advancing (a frozen
            // P_Ticker); HP not dropping means DamageMobj itself did nothing
            LOGGER.warn("applyThingDamage HIT id={} dmg={} hp {}->{}",
                mobjId, dmg, hpBefore, target.health);
        }
    }

    /** Slave the engine's audio to Minecraft's sliders (0-15 DOOM scale). */
    public void setVolumes(int sfx, int music) {
        wantSfxVol = sfx;
        wantMusicVol = music;
    }

    /** Stop the engine's audio threads so its music does not play over Minecraft. */
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
        } catch (Throwable ignored) {
        }
        try {
            if (d.soundDriver != null) {
                d.soundDriver.ShutdownSound();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Runs on the engine thread at every page flip (Engine.updateFrame). Publishes
     * the finished frame, then parks here while the engine is frozen, which holds the
     * engine at a frame boundary without consuming CPU.
     */

    /**
     * Publishes world state after every completed tic. Publishing per display frame instead
     * starves the client's interpolation, because the engine loop runs in bursts and slower
     * than the tic rate, which makes all motion look far choppier than it is. Engine thread.
     */
    private void onTic() {
        final DoomMain<?, ?> d = doom;
        if (d == null) {
            return;
        }
        // A capture failure must not break the tic tap, which runs inside the engine loop.
        try {
            markSeenLines(d); // automap reveal, before capture so this tic's snapshot has it
        } catch (Throwable ignored) {
        }
        try {
            final WorldSnapshot ws = WorldSnapshot.capture(d);
            if (ws != null && !remoteBodies.isEmpty()) {
                // Which snapshot mobj belongs to which remote player, so each of them can
                // follow their own body, including through teleporters
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
            snapshot = ws;
            if (ws != null) {
                hadLevel = true; // this engine has been in a level, not merely booting
            }
        } catch (Throwable t) {
            snapshot = null;
        }
        try {
            // The native intermission screen's data feed (only meaningful in GS_INTERMISSION,
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
        } catch (Throwable ignored) {
        }
    }

    /** Reveals automap lines. The automap shows only lines the engine's software renderer
     * has drawn, and this mod does not run that renderer, so the reveal is reproduced with
     * the engine's own blockmap traversal: a fan of sight rays across the player's field of
     * view each tic, stopping at solid walls and closed openings. */
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
                // See-through only while the two-sided line has an open window
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
        // The engine advances its tic counter every loop, but the level ticker that runs
        // monster AI, movers and death states only runs inside a level. If the tic counter
        // climbs while level time stands still, monsters freeze and nothing can be killed,
        // with no sign of a stall anywhere else. Log it, throttled.
        if (state == State.RUNNING) {
            final int gt = d.gametic;
            if (gt - diagLastGametic >= 35) {
                if (diagLastLeveltime >= 0 && d.leveltime == diagLastLeveltime) {
                    LOGGER.warn("P_TICKER FROZEN (monsters=statues, hits don't kill): {}"
                        + ": leveltime stuck at {} while gametic advanced +{}",
                        debugState(), d.leveltime, gt - diagLastGametic);
                }
                diagLastGametic = gt;
                diagLastLeveltime = d.leveltime;
            }
        }
        // The mod has its own audio levels, independent of Minecraft's sliders.
        // Volume changes are applied on the engine thread, and only when one changed.
        applyVolumes(d);
        if (!mochadoom.Engine.RENDER_VIEW) {
            // No software view: nothing to copy, and without the render cost the engine loop
            // would busy-spin, so yield here. Tics are timer-driven and unaffected.
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
        // Mirroring: overwrite the engine player's position and angle with the Minecraft
        // player's before the snapshot is taken, so that what is rendered is consistent.
        try {
            if (d.players != null && d.players[0] != null && d.players[0].mo != null) {
                final var mo = d.players[0].mo;
                // Teleport follow: the engine has just teleported the player, so mirror
                // position writes are held until the client's mirror lands near the
                // destination. Otherwise the next frame's write returns the body through the
                // teleporter before the client ever observes the move.
                final int tc = mochadoom.Engine.PLAYER_TELEPORT_COUNT;
                if (tc != teleSyncCount) {
                    teleSyncCount = tc;
                    if (mirrorOn) {
                        telePending = true;
                        telePendingFrames = 0;
                        teleDstX = mo.x / 65536.0;
                        teleDstY = mo.y / 65536.0;
                    }
                }
                if (telePending
                    && (Math.hypot(mirrorX - teleDstX, mirrorY - teleDstY) < 64.0
                        || ++telePendingFrames > 90)) {
                    telePending = false;
                }
                // Epoch gate: only apply mirror coordinates computed against this level
                // instance. A fresh spawn (death restart, next level, /warp reboot) is left
                // untouched until the client has seen it; without the gate the write replaces
                // every new spawn with the previous position converted through the new origin.
                final WorldSnapshot ss = snapshot;
                final boolean epochOk = ss != null && mirrorEpoch != 0
                    && mirrorEpoch == ss.levelEpoch;
                if (mirrorOn && epochOk) {
                    if (!mirrorEpochWasOk) {
                        // Re-engaging on a fresh instance: re-baseline the health difference
                        // so the reborn 100 is not counted as healing
                        lastWrittenHealth = mo.health;
                        mirrorEpochWasOk = true;
                    }
                    mo.flags |= mobj_t.MF_SHOOTABLE; // possessed again: shootable
                    if (!telePending) {
                        // Relink through the blockmap (P_Unset/SetThingPosition) so monster
                        // melee-range and thing collision see the true cell, not stale coords
                        d.actions.UnsetThingPosition(mo);
                        mo.x = (int) (mirrorX * 65536.0);
                        mo.y = (int) (mirrorY * 65536.0);
                        mo.z = (int) (mirrorZ * 65536.0);
                        d.actions.SetThingPosition(mo);
                        // Damage floors: the engine's sector-damage check compares the
                        // player's height against the floor height for exact equality in
                        // fixed point, and a height derived from Minecraft's floating-point
                        // coordinates never lands exactly on it. Snap to the floor when
                        // standing close enough, or damaging floors never trigger.
                        final int fz = mo.subsector.sector.floorheight;
                        if (Math.abs(mo.z - fz) < (4 << 16)) {
                            mo.z = fz;
                        }
                        mo.momx = mo.momy = mo.momz = 0;
                        mo.angle = ((long) (mirrorAngleDeg / 360.0 * 4294967296.0)) & 0xFFFFFFFFL;
                        d.players[0].viewz = mo.z + data.Defines.VIEWHEIGHT;
                    }
                    d.players[0].cheats &= ~player_t.CF_GODMODE; // mirrored: clear god mode

                    // Health is translated in both directions at five engine points per
                    // heart. Any change since the last write is the engine's own doing,
                    // damage or healing, and is billed or credited to Minecraft health. The
                    // engine never reaches its own death state, because Minecraft owns
                    // dying.
                    final int cur = mo.health;
                    if (cur < lastWrittenHealth) {
                        pendingDamage.addAndGet(lastWrittenHealth - cur);
                    } else if (cur > lastWrittenHealth) {
                        pendingHeal.addAndGet(cur - lastWrittenHealth);
                    }
                    // Add the remainder of a killing blow that the immortality floor could
                    // not take off engine health this tic. Without it, a player at low
                    // health is only ever charged to one point short of death and never dies.
                    // It is recorded at the floor itself, for the local player only.
                    final int lethalOverflow = mochadoom.Engine.LETHAL_OVERFLOW.getAndSet(0);
                    if (lethalOverflow > 0) {
                        pendingDamage.addAndGet(lethalOverflow);
                    }
                    final int mcHp = Math.max(1, Math.min(200, mirrorHealth));
                    mo.health = mcHp;
                    d.players[0].health[0] = mcHp;
                    lastWrittenHealth = mcHp;

                    // Minecraft owns dying, so the engine's local player never runs its own
                    // death and rebirth cycle and nothing restores the starting weapons
                    // after a respawn. Guarantee at least the fist and pistol; the check is
                    // idempotent and leaves every other weapon and key untouched.
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

                    // Item pickup, for a transformed player only. Mirrored movement bypasses
                    // the engine's own movement code, so contact is tested here and handed to
                    // its pickup routine. Gated on the tic advancing, because removal is
                    // processed by the tic and touching per frame during a stall would grant
                    // the same item repeatedly.
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
                            // An untransformed player converts configured items into
                            // Minecraft resources instead. The engine's pickup routine is
                            // deliberately not used, since they must never receive DOOM
                            // ammunition or armour. The item is consumed engine-side so
                            // spectators see it vanish, and its sprite queued for conversion.
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

                    // The player's interactions, run through the engine's own handlers:
                    // Walkover crossings (doors, teleports, exits) + the use key. During a
                    // teleport hold the queued crossings came from pre-teleport movement:
                    // Firing them at the destination could return the player immediately.
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
                    } else {
                        pendingCross.clear();
                        pendingUse.set(false);
                    }
                } else if (mirrorOn) {
                    // Mirroring is active but the incoming position belongs to a previous
                    // level instance, so the client has not seen this spawn yet. Leave the
                    // player where the engine placed them and discard interactions computed
                    // against the old map, which would trigger the wrong lines here.
                    pendingCross.clear();
                    pendingUse.set(false);
                    mirrorEpochWasOk = false;
                    mirrorWasOn = true;
                } else {
                    pendingCross.clear();
                    pendingUse.set(false);
                    pendingDamage.set(0);
                    mirrorEpochWasOk = false;
                    mochadoom.Engine.LETHAL_OVERFLOW.set(0); // not possessing: clear any stale overflow
                    if (mirrorWasOn) {
                        // Mirroring has stopped, so the abandoned player object must become
                        // invisible to the engine rather than merely invulnerable: marking it
                        // non-shootable makes the chase code drop it and the target search
                        // skip it. Otherwise monsters lock onto the stationary object instead
                        // of the real player. Restored when mirroring resumes.
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
                // Voice selection: a transformed player uses the engine's pain sounds,
                // while an untransformed player keeps Minecraft's. Decided per player,
                // since the other slots are other Minecraft players.
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
                // Damage thrust: translate the engine's last hit on the local player into a
                // pending Minecraft knockback (the mirror wiped the engine-side push)
                if (mochadoom.Engine.HURT_PLAYER == d.players[0]) {
                    mochadoom.Engine.HURT_PLAYER = null;
                    knockDirX = mochadoom.Engine.HURT_DIR_X;
                    knockDirY = mochadoom.Engine.HURT_DIR_Y;
                    knockThrust = mochadoom.Engine.HURT_THRUST;
                    knockPending = true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (mapRestartPending) {
            // The player respawned after dying inside the level, so the map restarts as it
            // does in single-player. Engine thread, at a frame boundary, like every call in.
            mapRestartPending = false;
            try {
                if (d.gameskill != null && d.gamemap > 0) {
                    d.DeferedInitNew(d.gameskill, d.gameepisode, d.gamemap);
                }
            } catch (Throwable ignored) {
            }
        }
        if (terminate) {
            // Programmatic shutdown, for example /doomwarp rebooting the engine into another
            // map. Thrown on the engine thread, it unwinds setupLoop as a menu quit does.
            throw new mochadoom.DoomQuitSignal();
        }
        while (frozen && state == State.RUNNING) {
            applyVolumes(d); // the sliders keep working while frozen
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
                // The music module divides its argument by 127, so it expects a 0-127 value;
                // every other engine caller passes 0-15 multiplied by 8. A raw 0-15 value caps
                // music at about 12 percent, so the slider is mapped into the 0-127 domain.
                d.doomSound.SetMusicVolume(Math.round(music / 15f * 127f));
            } catch (Throwable ignored) {
            }
        }
    }

    /** The debug framebuffer screen requires the software view, which is expensive. The
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
     * Terminate and block for up to {@code timeoutMs} until the engine thread has unwound,
     * which is when {@link #shutdownAudio()} has run and this engine's sound and music
     * channels are silent. Callers that immediately boot a replacement engine, such as the
     * {@code /doomwarp} reboot, must use this rather than {@link #terminate()}: otherwise the
     * old engine is still audible when the next one starts and the two race on the shared
     * audio system. Best effort; if the thread does not finish in time this returns
     * anyway.
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

    // Remote bodies: other Minecraft players are mirrored into the engine's remaining player
    // slots, of which there are four in total. They are valid monster targets, their attacks
    // run the engine's ballistics, their use presses open doors, and the damage the engine
    // deals them is charged to their Minecraft health.

    /** One remote player's live state, written by the network, read by the engine thread. */
    public static final class RemoteBody {
        public volatile double x, y, z, angleDeg;
        public volatile int buttons;      // bit0 fire, bit1 use
        public volatile int slot = -1;    // 0..6 requested weapon, -1 none
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

    /** The network delivers a remote player's latest state; crossed lines fire their triggers. */
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

    /** Engine thread: spawn, mirror or despawn each remote player's body. */
    private void tickRemoteBodies(DoomMain<?, ?> d) {
        final long now = System.currentTimeMillis();
        for (var e : remoteBodies.entrySet()) {
            final RemoteBody rb = e.getValue();
            if (now - rb.lastSeenMs > 1500) {
                // The remote player left the level or the game: remove the body
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
            mo.z = (int) (rb.z * 65536.0);
            d.actions.SetThingPosition(mo);
            mo.momx = mo.momy = mo.momz = 0;
            mo.angle = ((long) (rb.angleDeg / 360.0 * 4294967296.0)) & 0xFFFFFFFFL;
            d.players[idx].viewz = mo.z + data.Defines.VIEWHEIGHT;
            d.players[idx].cheats &= ~player_t.CF_GODMODE;
            // Health follows their hearts and damage is charged back, as for player 0
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
            // Their inputs, through the engine's own ticcmd path for this player
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
        mirrorX = x;
        mirrorY = y;
        mirrorZ = z;
        mirrorAngleDeg = angleDeg;
        mirrorEpoch = epoch;
        mirrorOn = on;
    }

    /** The possessed player crossed a special line: fire the engine's walkover handler. */
    public void requestCross(int lineIdx, int side) {
        pendingCross.add(new int[]{lineIdx, side});
    }

    /** The possessed player pressed use: run the engine's P_UseLines at their position. */
    public void requestUse() {
        pendingUse.set(true);
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

    /** Sets whether the local player is transformed, which gates DOOM item pickup. */
    public void setMarineMode(boolean marine) {
        marineMode = marine;
    }

    /** Sets which DOOM item sprites an untransformed player may consume; empty disables it. */
    public void setScavengeSprites(java.util.Set<String> sprites) {
        scavengeSprites = sprites != null ? sprites : java.util.Set.of();
    }

    /** Sprites consumed since the last drain, for the client to convert. */
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

    /** One line of engine vitals: tic counter, level time and the pause/menu flags. */
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

    /** Where the engine thread is right now: the freeze watchdog logs this to name the
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
        return snapshot;
    }

    /**
     * True when the engine is running but not currently in a playable level: the
     * intermission tally after an exit, the text finale, or the brief load between maps.
     * {@link #worldSnapshot()} returns null throughout this window, because capture only
     * runs inside a level, even though the engine is healthy and about to provide the next
     * map.
     *
     * <p>Callers must not tear the world down in this state. Doing so removes the level's
     * geometry while the player is still standing in the otherwise empty dimension it was
     * rendered in.
     */
    public boolean isBetweenLevels() {
        final DoomMain<?, ?> d = doom;
        return state == State.RUNNING && d != null && d.gamestate != defines.gamestate_t.GS_LEVEL;
    }

    /** The engine's exact stage, for the DOOM-shell flow (intermission view, episode end):
     * 0=in a level, 1=intermission tally, 2=text finale, 3=title/demo screen, -1=none/booting. */
    public int gamestateKind() {
        final DoomMain<?, ?> d = doom;
        if (state != State.RUNNING || d == null || d.gamestate == null) {
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

    /** True once this engine has published at least one in-level snapshot: distinguishes a
     * real post-level intermission from the boot-time non-level states. */
    public boolean hadLevel() {
        return hadLevel;
    }

    /** Whether the engine's own menu overlay is up. Drives the in-level menu screen, which
     * closes as soon as the player backs out of the menu. */
    public boolean isMenuActive() {
        final DoomMain<?, ?> d = doom;
        return d != null && d.menuactive;
    }

    /** The intermission tally, straight from the engine's wminfo: what the native
     * Minecraft-rendered intermission screen draws. Volatile copy, engine thread writes. */
    public static final class InterSnap {
        public int epsd;         // 0-based episode of the finished map
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

    /** The engine's current episode (1-based): the native finale picks its text/flat by it. */
    public int episodeNow() {
        final DoomMain<?, ?> d = doom;
        return d != null ? Math.max(1, d.gameepisode) : 1;
    }

    /**
     * Restarts the current map, which is single-player DOOM's behaviour on death: the
     * engine's level initialisation returns every player to the reborn state, with fist,
     * pistol and 50 bullets. Minecraft owns dying, so the engine never runs its own
     * death and rebirth cycle; this is called when the player respawns after dying inside
     * a level.
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
