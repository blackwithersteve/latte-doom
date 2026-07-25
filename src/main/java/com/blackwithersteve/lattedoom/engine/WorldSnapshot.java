package com.blackwithersteve.lattedoom.engine;

import defines.gamestate_t;
import doom.DoomMain;
import doom.player_t;
import doom.thinker_t;
import p.Actions.ActionsBoom;
import p.MapUtils;
import p.mobj_t;
import p.pusher_t;
import rr.sector_t;

/**
 * Copy of the engine's world state, taken on the engine thread between tics so nothing is
 * captured mid-update. The only view of the engine the Minecraft side reads. Everything is
 * copied into primitives, so no engine object escapes the engine thread and a snapshot can
 * be held for as long as needed.
 *
 * <p>Units are map units as doubles (fixed_t / 65536); angles are degrees, counter-clockwise
 * positive.
 */
public final class WorldSnapshot {

    /** The engine tic this snapshot was taken at (gametic). */
    public int tic;
    public int episode;
    public int map;
    /** True while the engine plays back a recorded demo, during which the player must not
     * be mirrored into it. */
    public boolean demo;

    // ---- The local player. ----
    public double px, py, pz;
    /** Eye height above the map origin ({@code viewz}), not above the floor. */
    public double viewZ;
    public double angleDeg;

    // ---- Sectors, as parallel arrays indexed by sector number. ----
    public double[] floorH;
    public double[] ceilH;
    public short[] light;
    public short[] floorPic;
    public short[] ceilPic;

    // ---- Automap state: which lines the player has seen, and whether the computer area
    // map power-up is active, which reveals the remainder. ----
    public boolean[] lineMapped;
    public boolean allmap;

    /** Lines whose front side textures differ from the level's baseline: pressed
     * switches and anything else that edits sidedefs. The rendering side swaps the texture
     * names and rebuilds those walls. Null for snapshots received over the network. */
    public int[] switchedLines;

    // ---- Boom friction and pushers, consumed by the movement integrator. These are local
    // only: the network codec does not carry them, so spectators use the standard
    // constants. Null arrays mean the level defines none, and the constant path applies. ----
    /** Per-tic friction factor per sector. Standard floors hold exactly 0.90625; a friction
     * line's value applies only while the sector special carries the friction bit. */
    public double[] secFriction;
    /** Effective Boom movefactor per sector (2048 = vanilla thrust scale). */
    public int[] secMoveFactor;
    /** Total pusher force (wind 224 + current 225 + point 226) the engine's T_Pusher
     * would add to a player object at the local player's position this tic, in map units
     * per tic of momentum. Computed with the engine's own integer arithmetic, including
     * distance falloff, the sight check, the push-enable bit, and the distinction between
     * a grounded and an airborne player. Exactly zero when the level defines no pushers. */
    public double playerPushX, playerPushY;

    // ---- Map objects: monsters, items, projectiles, decorations and the player. ----
    public int mobjCount;
    public double[] mx, my, mz;
    public double[] mAngleDeg;
    /** Spritenum_t ordinal. */
    public int[] mSprite;
    /** Frame index; bit 0x8000 (FF_FULLBRIGHT) preserved. */
    public int[] mFrame;
    /** Stable per-mobj identity across snapshots (for render interpolation). */
    public int[] mId;
    /** MF_SOLID things block the walking player (barrels, pillars, live monsters). */
    public boolean[] mSolid;
    /** MF_SHOOTABLE: what Minecraft fists/swords/arrows may damage (monsters, barrels;
     * corpses and decorations do not carry the flag). */
    public boolean[] mShootable;
    /** Collision radius, map units (the blockmap box rule uses radius sums). */
    public double[] mRadius;

    // ---- The view weapon and its muzzle flash. ----
    /** spritenum ordinal (-1 = none), frame (FF_FULLBRIGHT kept), sx/sy in 320x200 pixels. */
    public int wSprite = -1, wFrame, wX, wY;
    public int fSprite = -1, fFrame, fX, fY;

    // ---- Player statistics, as drawn on the status bar. ----
    public int armor, readyWeapon = -1, readyAmmoType = -1, damageCount, bonusCount;
    public int[] ammo, maxAmmo;      // the 4 pools (clip, shell, cell, missile order)
    public boolean[] weaponOwned;    // 9 weapons
    public boolean[] cards;          // 6 keys (3 cards + 3 skulls)
    /** Index of the player's own mobj in the arrays (-1 if absent): spectating renders it;
     * hidden when it represents the local player. */
    public int playerMobj = -1;

    /** True when this snapshot arrived over the network (someone else's engine): the
     * sending player's own object is skipped when drawing sprites, because their avatar
     * already occupies that position. */
    public boolean remote;

    // ---- Player-mirroring state; local only, never sent over the network. ----
    /** Identity of the level instance this snapshot came from; changes on every load,
     * including a restart of the same map. The client echoes it back with its mirrored
     * position and stale-stamped positions are rejected, so a fresh spawn is never
     * overwritten by the player's previous position read through the new map's origin. */
    public long levelEpoch;
    /** Running count of real engine-side teleports of the local player (see
     * {@code Engine.PLAYER_TELEPORT_COUNT}); the client moves the Minecraft player to
     * match whenever it changes. */
    public int teleportCount;

    /** The map's sky texture name, as chosen by the engine for this episode and map,
     * including any replacement supplied by a patch WAD. The renderer draws it behind the
     * sky openings in the mesh. Local only. */
    public String skyTexture;

    /** Remote player bodies living in this engine: player UUID halves ↔ the mId of
     * their mirrored object, as parallel arrays, or null when there are none. A spectating
     * client finds its own body here, which is how it follows teleporters, and skips
     * drawing it because its avatar already stands there. */
    public long[] rbUuidMost, rbUuidLeast;
    public int[] rbMobjId;

    /** An empty instance for the network decoder to fill. Only world fields are carried;
     * player statistics stay zero, because a spectator's HUD always reads its own engine
     * rather than the network. */
    public static WorldSnapshot forRemote() {
        final WorldSnapshot s = new WorldSnapshot();
        s.remote = true;
        return s;
    }

    private static final double FRAC = 65536.0;
    private static final double ANG_TO_DEG = 360.0 / 4294967296.0;

    /**
     * Captures the current engine state. Must be called on the engine thread at a frame
     * boundary. Returns null while no level is running, such as on the title screen or
     * during the intermission or finale.
     */
    private static int baselineKey = Integer.MIN_VALUE;
    private static short[] sideBase;
    private static int[] sideToLine;
    private static final int[] EMPTY_INTS = new int[0];

    // Level-instance epoch: a fresh timestamp whenever the episode, map or level start tic
    // changes. This state outlives an engine restart within the same JVM, and a restarted
    // engine can reproduce the same key because its tic counter starts near zero, so
    // newBoot() forces a fresh epoch and a stale mirror cannot be accepted across a restart.
    private static int epochKey = Integer.MIN_VALUE;
    private static long epochValue;

    /** Call when a new engine boots (DoomHost): the first capture mints a fresh epoch. */
    public static void newBoot() {
        epochKey = Integer.MIN_VALUE;
    }

    public static WorldSnapshot capture(DoomMain<?, ?> d) {
        if (d.gamestate != gamestate_t.GS_LEVEL || d.players == null || d.levelLoader == null) {
            return null;
        }
        final player_t p = d.players[0];
        if (p == null || p.mo == null) {
            return null;
        }
        final sector_t[] sectors = d.levelLoader.sectors;
        if (sectors == null) {
            return null;
        }

        final WorldSnapshot s = new WorldSnapshot();
        s.tic = d.gametic;
        s.episode = d.gameepisode;
        s.map = d.gamemap;
        s.demo = d.demoplayback;

        s.px = p.mo.x / FRAC;
        s.py = p.mo.y / FRAC;
        s.pz = p.mo.z / FRAC;
        s.viewZ = p.viewz / FRAC;
        s.angleDeg = (p.mo.angle & 0xFFFFFFFFL) * ANG_TO_DEG;
        s.armor = p.armorpoints[0];
        s.damageCount = p.damagecount;
        s.bonusCount = p.bonuscount;
        if (p.readyweapon != null) {
            s.readyWeapon = p.readyweapon.ordinal();
            final var wi = doom.items.weaponinfo[s.readyWeapon];
            if (wi != null && wi.ammo != null) {
                s.readyAmmoType = wi.ammo.ordinal();
            }
        }
        if (p.ammo != null) {
            s.ammo = p.ammo.clone();
        }
        if (p.maxammo != null) {
            s.maxAmmo = p.maxammo.clone();
        }
        if (p.weaponowned != null) {
            s.weaponOwned = p.weaponowned.clone();
        }
        if (p.cards != null) {
            s.cards = p.cards.clone();
        }
        if (p.psprites != null && p.psprites.length > 1) {
            if (p.psprites[0] != null && p.psprites[0].state != null
                && p.psprites[0].state.sprite != null) {
                s.wSprite = p.psprites[0].state.sprite.ordinal();
                s.wFrame = p.psprites[0].state.frame;
                s.wX = p.psprites[0].sx >> 16;
                s.wY = p.psprites[0].sy >> 16;
            }
            if (p.psprites[1] != null && p.psprites[1].state != null
                && p.psprites[1].state.sprite != null) {
                s.fSprite = p.psprites[1].state.sprite.ordinal();
                s.fFrame = p.psprites[1].state.frame;
                s.fX = p.psprites[1].sx >> 16;
                s.fY = p.psprites[1].sy >> 16;
            }
        }

        final int n = d.levelLoader.numsectors;
        s.floorH = new double[n];
        s.ceilH = new double[n];
        s.light = new short[n];
        s.floorPic = new short[n];
        s.ceilPic = new short[n];
        for (int i = 0; i < n; i++) {
            final sector_t sec = sectors[i];
            s.floorH[i] = sec.floorheight / FRAC;
            s.ceilH[i] = sec.ceilingheight / FRAC;
            s.light[i] = sec.lightlevel;
            s.floorPic[i] = sec.floorpic;
            s.ceilPic[i] = sec.ceilingpic;
        }

        // ---- Boom friction: the effective per-sector values for the movement integrator.
        // The arrays exist only when the level defines friction, since that assignment is
        // fixed after spawn, so maps without it take the null path. The friction-enable bit
        // is re-read every tic, exactly as the engine re-reads it. ----
        boolean anyFriction = false;
        for (int i = 0; i < n && !anyFriction; i++) {
            anyFriction = sectors[i].boomFriction != sector_t.ORIG_FRICTION;
        }
        if (anyFriction) {
            s.secFriction = new double[n];
            s.secMoveFactor = new int[n];
            for (int i = 0; i < n; i++) {
                final sector_t sec = sectors[i];
                if (sec.boomFriction != sector_t.ORIG_FRICTION
                    && (sec.special & sector_t.FRICTION_MASK) != 0) {
                    s.secFriction[i] = sec.boomFriction / FRAC;
                    s.secMoveFactor[i] = sec.boomMoveFactor;
                } else {
                    s.secFriction[i] = sector_t.ORIG_FRICTION / FRAC;
                    s.secMoveFactor[i] = sector_t.ORIG_FRICTION_FACTOR;
                }
            }
        }

        // ---- Boom pushers: the exact per-tic force the engine's pusher thinker would
        // apply to a player object at the mirrored player's position, computed in the
        // engine's integer arithmetic throughout and converted once at the end. The engine
        // applies this to the mirrored object as well, but mirroring zeroes its momentum
        // every frame, so applying it on the Minecraft side is the only delivery and there
        // is no double push. Following the engine thinker's own simplifications, wind and
        // current are gated on the sector containing the player's centre, and the grounded
        // test compares against this sector's floor, because the mirrored object's own floor
        // height is never refreshed by the engine's movement code. ----
        {
            int pushX = 0, pushY = 0; // fixed_t momentum add
            final mobj_t pmo = p.mo;
            if ((pmo.flags & (mobj_t.MF_NOGRAVITY | mobj_t.MF_NOCLIP)) == 0) {
                final rr.subsector_t pss = pmo.subsector;
                final sector_t psec = pss != null ? pss.sector : null;
                final thinker_t pcap = d.actions.getThinkerCap();
                for (thinker_t t = pcap.next; t != pcap; t = t.next) {
                    if (!(t instanceof pusher_t pu)) {
                        continue;
                    }
                    final sector_t sec = sectors[pu.affectee];
                    if ((sec.special & sector_t.PUSH_MASK) == 0) {
                        continue;
                    }
                    if (pu.type == pusher_t.PT_POINT) {
                        // PIT_PushThing: linear falloff to zero at twice the magnitude,
                        // plus a line-of-sight check.
                        final int dist = MapUtils.AproxDistance(pmo.x - pu.x, pmo.y - pu.y);
                        final int speed = (pu.magnitude - ((dist >> 16) >> 1))
                            << (16 - ActionsBoom.PUSH_FACTOR - 1);
                        if (speed > 0 && pu.source != null
                            && d.actions.CheckSight(pmo, pu.source)) {
                            long ang = d.sceneRenderer.PointToAngle2(pmo.x, pmo.y, pu.x, pu.y);
                            if (!pu.pull) {
                                ang += data.Tables.ANG180; // away from an MT_PUSH source
                            }
                            ang &= data.Tables.BITS32;
                            pushX += m.fixed_t.FixedMul(speed, data.Tables.finecosine(ang));
                            pushY += m.fixed_t.FixedMul(speed, data.Tables.finesine(ang));
                        }
                    } else if (psec == sec) { // wind/current: standing in the sector
                        final boolean above = pmo.z > sec.floorheight;
                        final int xs, ys;
                        if (pu.type == pusher_t.PT_WIND) {
                            xs = above ? pu.xMag : pu.xMag >> 1; // airborne full, ground half
                            ys = above ? pu.yMag : pu.yMag >> 1;
                        } else { // PT_CURRENT: grounded full, airborne nothing
                            xs = above ? 0 : pu.xMag;
                            ys = above ? 0 : pu.yMag;
                        }
                        pushX += xs << (16 - ActionsBoom.PUSH_FACTOR);
                        pushY += ys << (16 - ActionsBoom.PUSH_FACTOR);
                    }
                }
            }
            s.playerPushX = pushX / FRAC;
            s.playerPushY = pushY / FRAC;
        }

        // Switch textures: compare the sidedefs' texture numbers against the baseline
        // captured when the level loaded, so that both a press and the revert of a
        // reusable switch fall out of the same comparison. Engine thread only; the
        // baseline is re-seeded per level instance, which covers restarting the same map.
        final int levelKey = (d.gameepisode << 24) ^ (d.gamemap << 16) ^ d.levelstarttic;
        if (levelKey != epochKey) {
            epochKey = levelKey;
            epochValue = System.nanoTime();
        }
        s.levelEpoch = epochValue;
        s.teleportCount = mochadoom.Engine.PLAYER_TELEPORT_COUNT;
        if (d.textureManager instanceof rr.SimpleTextureManager stm) {
            s.skyTexture = stm.CheckTextureNameForNum(stm.getSkyTexture());
        }
        final rr.side_t[] engSides = d.levelLoader.sides;
        if (engSides != null && d.levelLoader.lines != null) {
            if (baselineKey != levelKey || sideBase == null
                || sideBase.length != engSides.length * 3) {
                baselineKey = levelKey;
                sideBase = new short[engSides.length * 3];
                for (int i = 0; i < engSides.length; i++) {
                    sideBase[i * 3] = engSides[i].toptexture;
                    sideBase[i * 3 + 1] = engSides[i].midtexture;
                    sideBase[i * 3 + 2] = engSides[i].bottomtexture;
                }
                sideToLine = new int[engSides.length];
                java.util.Arrays.fill(sideToLine, -1);
                for (int li = 0; li < d.levelLoader.lines.length; li++) {
                    final int sn = d.levelLoader.lines[li].sidenum[0];
                    if (sn != 0xFFFF && sn < sideToLine.length) {
                        sideToLine[sn] = li;
                    }
                }
            }
            java.util.List<Integer> flipped = null;
            for (int i = 0; i < engSides.length; i++) {
                if (engSides[i].toptexture != sideBase[i * 3]
                    || engSides[i].midtexture != sideBase[i * 3 + 1]
                    || engSides[i].bottomtexture != sideBase[i * 3 + 2]) {
                    final int li = sideToLine[i];
                    if (li >= 0) {
                        if (flipped == null) {
                            flipped = new java.util.ArrayList<>();
                        }
                        flipped.add(li);
                    }
                }
            }
            if (flipped != null) {
                s.switchedLines = new int[flipped.size()];
                for (int i = 0; i < flipped.size(); i++) {
                    s.switchedLines[i] = flipped.get(i);
                }
            } else {
                s.switchedLines = EMPTY_INTS;
            }
        }

        // Automap: the engine marks the lines the player has seen, which is the
        // original's reveal rule; the area map power-up shows the remainder in grey.
        final rr.line_t[] lines = d.levelLoader.lines;
        if (lines != null) {
            s.lineMapped = new boolean[lines.length];
            for (int i = 0; i < lines.length; i++) {
                s.lineMapped[i] = (lines[i].flags & rr.line_t.ML_MAPPED) != 0;
            }
        }
        s.allmap = p.powers != null && p.powers.length > data.Defines.pw_allmap
            && p.powers[data.Defines.pw_allmap] > 0;

        // Walk the thinker list twice, once to count and once to fill, which sizes the
        // arrays exactly and avoids intermediate collections.
        //
        // Objects flagged MF_NOSECTOR are invisible markers rather than world objects. The
        // engine's renderer walks per-sector object lists, which they are never linked
        // into, so it cannot draw them at all. Teleport destinations are the common case:
        // they carry a null state whose sprite slot is a real monster sprite, so walking the
        // thinker list without this filter draws a motionless, non-solid monster on every
        // teleporter pad.
        final thinker_t cap = d.actions.getThinkerCap();
        int count = 0;
        for (thinker_t t = cap.next; t != cap; t = t.next) {
            if (t instanceof mobj_t m && m.mobj_sprite != null
                && (m.flags & mobj_t.MF_NOSECTOR) == 0) {
                count++;
            }
        }
        s.mobjCount = count;
        s.mx = new double[count];
        s.my = new double[count];
        s.mz = new double[count];
        s.mAngleDeg = new double[count];
        s.mSprite = new int[count];
        s.mFrame = new int[count];
        s.mId = new int[count];
        s.mSolid = new boolean[count];
        s.mShootable = new boolean[count];
        s.mRadius = new double[count];
        int i = 0;
        for (thinker_t t = cap.next; t != cap && i < count; t = t.next) {
            if (t instanceof mobj_t m && m.mobj_sprite != null
                && (m.flags & mobj_t.MF_NOSECTOR) == 0) {
                if (m == p.mo) {
                    s.playerMobj = i;
                }
                s.mx[i] = m.x / FRAC;
                s.my[i] = m.y / FRAC;
                s.mz[i] = m.z / FRAC;
                s.mAngleDeg[i] = (m.angle & 0xFFFFFFFFL) * ANG_TO_DEG;
                // Prefer the true sprite index: sprites added by MBF-class patches have
                // no entry in the built-in enumeration.
                s.mSprite[i] = m.mobj_spritenum >= 0 ? m.mobj_spritenum : m.mobj_sprite.ordinal();
                s.mFrame[i] = m.mobj_frame;
                s.mId[i] = System.identityHashCode(m); // stable for the mobj's lifetime
                s.mSolid[i] = (m.flags & mobj_t.MF_SOLID) != 0;
                s.mShootable[i] = (m.flags & mobj_t.MF_SHOOTABLE) != 0;
                s.mRadius[i] = m.radius / FRAC;
                i++;
            }
        }
        return s;
    }

    private WorldSnapshot() {}
}
