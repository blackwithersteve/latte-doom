package com.blackwithersteve.lattedoom.engine;

import p.ActiveStates;
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
 * One consistent copy of the engine's world state, taken ON THE ENGINE THREAD at a page
 * flip (the frame boundary — between tics, so nothing is mid-mutation). This is the ONLY
 * thing the Minecraft renderer reads: sector heights drive the level mesh, mobjs drive
 * billboards, the player drives the camera. Plain data, no MC and no engine references
 * escape (everything is copied to primitives), so the render thread can hold it freely.
 *
 * <p>Units: DOOM map units as doubles (fixed_t / 65536). Angles in degrees CCW-positive
 * (DOOM convention, angle_t * 360 / 2^32).
 */
public final class WorldSnapshot {

    /** The engine tic this snapshot was taken at (gametic). */
    public int tic;
    public int episode;
    public int map;
    /** True while the engine plays back a recorded demo — auto-possession must not stomp it. */
    public boolean demo;

    // ---- the player (players[0]) ----
    public double px, py, pz;
    /** Eye height above the map origin (viewz), NOT above the floor. */
    public double viewZ;
    public double angleDeg;

    // ---- sectors: parallel arrays, index = sector number ----
    public double[] floorH;
    public double[] ceilH;
    public short[] light;
    public short[] floorPic;
    public short[] ceilPic;

    // ---- automap truth: which lines the ENGINE's own renderer has seen (ML_MAPPED),
    // and whether the Computer Area Map power is running (reveals the rest in gray) ----
    public boolean[] lineMapped;
    public boolean allmap;
    /** Berserk is active — the fullscreen HUD shows the strength sprite instead of a medikit. */
    public boolean berserk;
    /** The radiation suit is active — vanilla washes the screen green while it runs. */
    public boolean radSuit;

    /** Lines whose FRONT side textures differ from the level's baseline — pressed
     * switches (SW1->SW2) and anything else that edits sidedefs. The client swaps the
     * texture names and re-bakes those walls. Null on the spectator wire (local only). */
    public int[] switchedLines;

    // ---- Boom friction + pushers, the DoomMovement mirror lane (all LOCAL-only: the
    // network codec does not carry them, so spectators keep the vanilla constants).
    // Null arrays = the level has none = the client's untouched constant path. ----
    /** EFFECTIVE per-tic friction FACTOR per sector (vanilla floors hold exactly
     * 0.90625): the 223-line value only while the sector special carries the
     * FRICTION_MASK bit, else the vanilla constant. */
    public double[] secFriction;
    /** EFFECTIVE Boom movefactor per sector (2048 = vanilla thrust scale). */
    public int[] secMoveFactor;
    /** TOTAL pusher force (wind 224 + current 225 + point 226) the engine's T_Pusher
     * would add to a player mobj at the LOCAL player's position this tic, map units/tic
     * of momentum. Computed with the engine's own integer math (falloff, sight check,
     * PUSH_MASK gate, grounded/airborne split at the mirrored position). Exactly 0.0
     * when the level has no pushers — the client's untouched path. */
    public double playerPushX, playerPushY;

    /** The console player's extralight (A_Light0/1/2 — gun flashes, and DEH
     * flashlight weapons that loop those pointers). Vanilla adds it to every
     * lightnum: walls, planes, sprites AND psprites all brighten. */
    public int extraLight;

    // ---- mobjs (monsters, items, projectiles, decorations, AND the player) ----
    public int mobjCount;
    public double[] mx, my, mz;
    public double[] mAngleDeg;
    /** spritenum_t ordinal. */
    public int[] mSprite;
    /** Frame index; bit 0x8000 (FF_FULLBRIGHT) preserved. */
    public int[] mFrame;
    /** Stable per-mobj identity across snapshots (for render interpolation). */
    public int[] mId;
    /** MF_SOLID things block the walking player (barrels, pillars, live monsters). */
    public boolean[] mSolid;
    /** MF_SHOOTABLE — what Minecraft fists/swords/arrows may damage (monsters, barrels;
     * corpses and decorations drop the flag). */
    public boolean[] mShootable;
    /** Collision radius, map units (the blockmap box rule uses radius sums). */
    public double[] mRadius;
    /** ENGINE-TRUE containing sector (mobj.subsector.sector.id), or -1. Renderers
     * use this instead of re-deriving point-in-sector geometry per thing per frame. */
    public int[] mSector;

    // ---- the player's view weapon (psprites[0]) + muzzle flash (psprites[1]) ----
    /** spritenum ordinal (-1 = none), frame (FF_FULLBRIGHT kept), sx/sy in 320x200 pixels. */
    public int wSprite = -1, wFrame, wX, wY;
    public int fSprite = -1, fFrame, fX, fY;

    // ---- marine stats for the 1:1 status bar ----
    public int armor, readyWeapon = -1, readyAmmoType = -1, damageCount, bonusCount;
    /** Kills/items/secrets, the player's count and the level's total, plus the level's
     * elapsed tics — the stats widget's feed. */
    public int killCount, totalKills, itemCount, totalItems, secretCount, totalSecrets,
        levelTime;
    /** The engine's heads-up line (pickups, keys, secrets), consumed on capture the way
     * HU_Ticker consumes it; null when nothing new was said this tic. */
    public String message;
    /** Whether the view weapon is in its ready state — the only state vanilla bobs in;
     * firing freezes the sway where it was. */
    public boolean weaponReady;
    /** 1 = green armor, 2 = blue, matches player_t.armortype. */
    public int armorType;
    public int[] ammo, maxAmmo;      // the 4 pools (clip, shell, cell, missile order)
    public boolean[] weaponOwned;    // 9 weapons
    public boolean[] cards;          // 6 keys (3 cards + 3 skulls)
    /** Index of the player's own mobj in the arrays (-1 if absent) — spectating renders it;
     * M3 (you ARE the marine) will hide it. */
    public int playerMobj = -1;

    /** True when this snapshot arrived over the NETWORK (someone else's engine): the
     * owner's player mobj is skipped in the sprite pass (their MC body already shows). */
    public boolean remote;

    // ---- possession sync (LOCAL-only, never on the wire) ----
    /** Identity of the LEVEL INSTANCE this snapshot came from — changes on every level
     * load, including a death-restart of the same map. The client echoes it with its
     * mirror input and the host refuses mirror positions stamped with a stale epoch:
     * without this, the per-frame mirror write instantly clobbered every fresh engine
     * spawn with the player's old standing spot converted through the NEW map's anchors
     * (the "spawned in the wrong spot of the level" reports — death restarts AND the
     * SIGIL E5M1→E5M2 advance). */
    public long levelEpoch;
    /** Running count of real engine-side teleports of the local player (see
     * Engine.PLAYER_TELEPORT_COUNT) — the client snaps the MC player when it changes. */
    public int teleportCount;

    /** The map's sky TEXTURE name ("SKY1", or whatever the PWAD swapped in) — engine
     * truth from G_DoLoadLevel's episode/map selection. The client draws it behind the
     * F_SKY1 holes in the mesh. LOCAL-only. */
    public String skyTexture;

    /** Remote player bodies living in this engine: player UUID halves ↔ the mId of
     * their possessed mobj (parallel arrays, null when none). A spectating client finds
     * its own body here (teleporter follows) and skips rendering it (their MC body
     * already stands there). */
    public long[] rbUuidMost, rbUuidLeast;
    public int[] rbMobjId;

    /** Empty shell for the network decoder to fill (world fields only; suit stats stay 0
     * — a spectator's HUD reads their OWN engine, never the wire). */
    public static WorldSnapshot forRemote() {
        final WorldSnapshot s = new WorldSnapshot();
        s.remote = true;
        return s;
    }

    private static final double FRAC = 65536.0;
    private static final double ANG_TO_DEG = 360.0 / 4294967296.0;

    /**
     * Capture the live engine state. Call ONLY on the engine thread at a frame boundary.
     * Returns null while no level is running (title screen, intermission, finale).
     */
    private static int baselineKey = Integer.MIN_VALUE;
    private static short[] sideBase;
    private static int[] sideToLine;
    private static final int[] EMPTY_INTS = new int[0];

    // level-instance epoch: a fresh nanoTime whenever the (episode, map, levelstarttic)
    // key changes. Static state survives an engine reboot in the same JVM, and a rebooted
    // engine can reproduce the same key (gametic restarts near 0) — newBoot() forces a
    // fresh epoch so a stale mirror can never be accepted across a /warp reboot either.
    private static int epochKey = Integer.MIN_VALUE;
    private static long epochValue;

    /** Call when a NEW engine boots (DoomHost): the first capture mints a fresh epoch. */
    public static void newBoot() {
        epochKey = Integer.MIN_VALUE;
    }

    /** A snapshot carrying nothing but a level identity, for the headless session gate. */
    public static WorldSnapshot forEpoch(long epoch) {
        final WorldSnapshot s = new WorldSnapshot();
        s.levelEpoch = epoch;
        return s;
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
        s.armorType = p.armortype;
        // read-and-clear, exactly HU_Ticker's contract, so a line shows once
        if (p.message != null) {
            s.message = p.message;
            p.message = null;
        }
        s.killCount = p.killcount;
        s.itemCount = p.itemcount;
        s.secretCount = p.secretcount;
        s.totalKills = d.totalkills;
        s.totalItems = d.totalitems;
        s.totalSecrets = d.totalsecret;
        s.levelTime = d.leveltime;
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
                s.weaponReady = p.psprites[0].state.action == ActiveStates.A_WeaponReady;
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

        // ---- Boom friction (223): per-sector EFFECTIVE values for the DoomMovement
        // marine integrator. Arrays exist only when some 223 line touched this level
        // (boomFriction is spawn-static), so vanilla maps take the null fast path; the
        // FRICTION_MASK on/off bit is re-read per tic like the engine's own gate.
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

        // ---- Boom pushers (224-226): T_Pusher's exact per-tic force for a player mobj
        // at the mirrored player's position, engine integer math end to end (the double
        // conversion happens once, on the final sum). The engine's own T_BoomPusher does
        // hit players[0].mo too, but the possession mirror zeroes momx/momy every frame,
        // so the client applying this is the ONLY delivery — no double push.
        // v1 (matches the engine thinker's own simplifications): center-in-sector gates
        // wind/current, and the grounded test is z <= this sector's floor — the mirrored
        // mobj's floorz is never refreshed by P_TryMove, so it cannot be trusted here.
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
                        // PIT_PushThing: linear falloff to zero at 2 x magnitude + sight
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

        // Switch textures: diff the sides' texture numbers against the level baseline, so
        // presses and reusable-button reverts both fall out of the diff without being
        // tracked separately. Engine thread only; the baseline re-seeds per level instance
        // (levelstarttic covers death-restarts of the same map).
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

        // automap: the engine's own renderer marks lines it has drawn (ML_MAPPED) — the
        // vanilla reveal rule; the allmap power shows the rest in gray
        final rr.line_t[] lines = d.levelLoader.lines;
        if (lines != null) {
            s.lineMapped = new boolean[lines.length];
            for (int i = 0; i < lines.length; i++) {
                s.lineMapped[i] = (lines[i].flags & rr.line_t.ML_MAPPED) != 0;
            }
        }
        s.allmap = p.powers != null && p.powers.length > data.Defines.pw_allmap
            && p.powers[data.Defines.pw_allmap] > 0;
        // berserk: the fullscreen HUD swaps the medikit for the strength sprite, as
        // DrawFullScreenStuff does
        s.berserk = p.powers != null && p.powers.length > data.Defines.pw_strength
            && p.powers[data.Defines.pw_strength] > 0;
        // the radiation suit's green wash is one of vanilla's palette shifts, alongside
        // the damage reds and pickup golds (ST_doPaletteStuff)
        s.radSuit = p.powers != null && p.powers.length > data.Defines.pw_ironfeet
            && p.powers[data.Defines.pw_ironfeet] > 0;

        // Walk the thinker ring once to count, once to fill — allocation-exact, no lists.
        // MF_NOSECTOR things are invisible MARKERS, not world objects: vanilla's renderer
        // walks sector thing lists, which they are never linked into, so it literally
        // cannot draw them. MT_TELEPORTMAN wears S_NULL whose sprite slot is TROO — we
        // walked the raw thinker ring and drew every teleport destination as a frozen
        // walk-through imp standing on its pad (the SIGIL/Eviternity "ghost imps").
        final thinker_t cap = d.actions.getThinkerCap();
        int count = 0;
        for (thinker_t t = cap.next; t != cap; t = t.next) {
            if (t instanceof mobj_t m && m.mobj_sprite != null
                && (m.flags & mobj_t.MF_NOSECTOR) == 0) {
                count++;
            }
        }
        s.extraLight = p.extralight;
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
        s.mSector = new int[count];
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
                // DEH support: prefer the true sprite index (MBF sprites 138+ have no enum)
                s.mSprite[i] = m.mobj_spritenum >= 0 ? m.mobj_spritenum : m.mobj_sprite.ordinal();
                s.mFrame[i] = m.mobj_frame;
                s.mId[i] = System.identityHashCode(m); // stable for the mobj's lifetime
                s.mSolid[i] = (m.flags & mobj_t.MF_SOLID) != 0;
                s.mShootable[i] = (m.flags & mobj_t.MF_SHOOTABLE) != 0;
                s.mRadius[i] = m.radius / FRAC;
                s.mSector[i] = m.subsector != null && m.subsector.sector != null
                    ? m.subsector.sector.id : -1;
                i++;
            }
        }
        return s;
    }

    private WorldSnapshot() {}
}
