package p.Actions;

import data.Tables;
import data.mobjtype_t;
import data.sounds;
import defines.card_t;
import doom.englsh;
import doom.player_t;
import doom.thinker_t;
import java.util.HashSet;
import java.util.Set;
import p.ActiveStates;
import p.DoorDefines;
import p.ceiling_e;
import p.ceiling_t;
import p.elevator_t;
import p.floor_e;
import p.floormove_t;
import p.mobj_t;
import p.plat_e;
import p.plat_t;
import p.plattype_e;
import p.result_e;
import p.stair_e;
import p.vldoor_e;
import p.vldoor_t;
import p.pusher_t;
import rr.line_t;
import rr.sector_t;
import rr.side_t;
import utils.TraitFactory.ContextKey;

import static data.Limits.CEILSPEED;
import static data.Limits.PLATSPEED;
import static m.fixed_t.FRACUNIT;
import static m.fixed_t.FixedDiv;
import static m.fixed_t.FixedMul;
import static m.fixed_t.MAPFRACUNIT;

/**
 * Latte Doom additive patch — BOOM GENERALIZED LINEDEFS (the heart of Boom compat).
 *
 * Port of Boom v2.02 p_genlin.c / p_spec.h into Mocha Doom's trait architecture.
 * Generalized specials live in 0x2F80..0x7FFF: parameterized floors, ceilings, doors,
 * locked doors, lifts, stairs and crushers, with trigger type (W/S/G/D x once/many),
 * speed, target, direction, change and model encoded in bit fields.
 *
 * All seven kinds are ported (floors, ceilings, doors, locked doors, lifts, stairs,
 * crushers). Known v1 simplifications, marked at their sites: gen crushers ride the
 * vanilla crusher types (speed resets to CEILSPEED after a full stroke), CdO doors
 * reuse the fixed 30s reopen, S1-stairs do not flip direction on reuse. Vanilla
 * specials (< 0x2F80) are untouched by every code path here.
 *
 * ALSO here: BOOM FIXED EXTENDED TYPES (142-269) — the jff 1/29/98 "fill out all
 * varieties" table (W1/WR/S1/SR/G1 flavors of the vanilla actions, donuts, exits),
 * the killough silent teleporters (thing and line-to-line), the jff elevators
 * (227-238) and instant toggle plats (211/212). See the FIXED TYPES section below.
 */
public interface ActionsBoom extends ActionsFloors, ActionsMoveEvents, ActionsUseEvents, ActionsShootEvents, ActionsThinkers, ActionsSight, ActionsTeleportation {

    // ---- generalized ranges (p_spec.h) ----
    int GenEnd = 0x8000;
    int GenFloorBase = 0x6000;
    int GenCeilingBase = 0x4000;
    int GenDoorBase = 0x3c00;
    int GenLockedBase = 0x3800;
    int GenLiftBase = 0x3400;
    int GenStairsBase = 0x3000;
    int GenCrusherBase = 0x2f80;

    // ---- trigger field ----
    int TriggerType = 0x0007;
    int TrigWalkOnce = 0, TrigWalkMany = 1, TrigSwitchOnce = 2, TrigSwitchMany = 3,
        TrigGunOnce = 4, TrigGunMany = 5, TrigPushOnce = 6, TrigPushMany = 7;

    // ---- floor bit fields ----
    int FloorCrush = 0x1000, FloorCrushShift = 12;
    int FloorChange = 0x0c00, FloorChangeShift = 10;
    int FloorTarget = 0x0380, FloorTargetShift = 7;
    int FloorDirection = 0x0040, FloorDirectionShift = 6;
    int FloorModel = 0x0020, FloorModelShift = 5;
    int FloorSpeed = 0x0018, FloorSpeedShift = 3;

    // floor targets
    int FtoHnF = 0, FtoLnF = 1, FtoNnF = 2, FtoLnC = 3, FtoC = 4, FbyST = 5, Fby24 = 6, Fby32 = 7;
    // floor change kinds
    int FNoChg = 0, FChgZero = 1, FChgTxt = 2, FChgTyp = 3;

    /** Is this special in Boom's generalized range? (No vanilla special reaches 0x2F80.) */
    static boolean isGeneralized(int special) {
        final int sp = special & 0xFFFF;
        return sp >= GenCrusherBase && sp < GenEnd;
    }

    /**
     * CROSS (walkover) lane for generalized specials. Returns true when the special was
     * generalized (handled or safely consumed) — the vanilla switch must then be skipped.
     */
    default boolean crossBoomGeneralized(line_t line, mobj_t thing) {
        final int sp = line.special & 0xFFFF;
        if (!isGeneralized(sp)) {
            return false;
        }
        final int trig = sp & TriggerType;
        if (trig != TrigWalkOnce && trig != TrigWalkMany) {
            return true; // not a walkover trigger: consumed, nothing fires
        }
        if (thing.player == null && !boomMonsterAllowed(sp)) {
            return true;
        }
        if (dispatchBoomGeneralized(line, sp, thing) && trig == TrigWalkOnce) {
            line.special = 0;
        }
        return true;
    }

    /** USE lane: switches (S1/SR) and manual push (D1/DR — backside sector, no tag). */
    default boolean useBoomGeneralized(mobj_t thing, line_t line) {
        final int sp = line.special & 0xFFFF;
        if (!isGeneralized(sp)) {
            return false;
        }
        final int trig = sp & TriggerType;
        if (trig != TrigSwitchOnce && trig != TrigSwitchMany
            && trig != TrigPushOnce && trig != TrigPushMany) {
            return true;
        }
        if (thing.player == null && !boomMonsterAllowed(sp)) {
            return true;
        }
        if (dispatchBoomGeneralized(line, sp, thing)) {
            final boolean reusable = trig == TrigSwitchMany || trig == TrigPushMany;
            getSwitches().ChangeSwitchTexture(line, reusable);
            if (!reusable) {
                line.special = 0;
            }
        }
        return true;
    }

    /** GUN (impact) lane: G1/GR. */
    default boolean shootBoomGeneralized(mobj_t thing, line_t line) {
        final int sp = line.special & 0xFFFF;
        if (!isGeneralized(sp)) {
            return false;
        }
        final int trig = sp & TriggerType;
        if (trig != TrigGunOnce && trig != TrigGunMany) {
            return true;
        }
        if (thing.player == null && !boomMonsterAllowed(sp)) {
            return true;
        }
        if (dispatchBoomGeneralized(line, sp, thing)) {
            getSwitches().ChangeSwitchTexture(line, trig == TrigGunMany);
            if (trig == TrigGunOnce) {
                line.special = 0;
            }
        }
        return true;
    }

    /** Boom monster gating, per kind: doors carry a dedicated bit (0x80); lifts, stairs
     * and crushers use their 0x20 bit; floors, ceilings and locked doors never allow. */
    private boolean boomMonsterAllowed(int sp) {
        if (sp >= GenCeilingBase) {
            return false; // floors + ceilings: player-only
        }
        if (sp >= GenDoorBase) {
            return (sp & 0x0080) != 0;
        }
        if (sp >= GenLockedBase) {
            return false;
        }
        return (sp & 0x0020) != 0; // lifts, stairs, crushers
    }

    /** Route to the kind's EV — all seven ported. */
    private boolean dispatchBoomGeneralized(line_t line, int sp, mobj_t thing) {
        if (sp >= GenFloorBase) {
            return EV_DoGenFloor(line);
        }
        if (sp >= GenCeilingBase) {
            return EV_DoGenCeiling(line);
        }
        if (sp >= GenDoorBase) {
            return EV_DoGenDoor(line);
        }
        if (sp >= GenLockedBase) {
            return EV_DoGenLockedDoor(line, thing);
        }
        if (sp >= GenLiftBase) {
            return EV_DoGenLift(line);
        }
        if (sp >= GenStairsBase) {
            return EV_DoGenStairs(line);
        }
        return EV_DoGenCrusher(line);
    }

    // ------------------------------------------------------------------ EV_DoGenFloor

    /**
     * Boom p_genlin.c EV_DoGenFloor, faithful: parameterized floor movers, including
     * texture/type change under trigger or numeric model. The mover itself is the
     * vanilla T_MoveFloor thinker — the renderer already animates any moving sector.
     */
    default boolean EV_DoGenFloor(line_t line) {
        final int value = (line.special & 0xFFFF) - GenFloorBase;

        final boolean Crsh = ((value & FloorCrush) >> FloorCrushShift) != 0;
        final int ChgT = (value & FloorChange) >> FloorChangeShift;
        final int Targ = (value & FloorTarget) >> FloorTargetShift;
        final int Dirn = (value & FloorDirection) >> FloorDirectionShift;
        final int ChgM = (value & FloorModel) >> FloorModelShift;
        final int Sped = (value & FloorSpeed) >> FloorSpeedShift;
        final int trig = value & TriggerType;
        final boolean manual = trig == TrigPushOnce || trig == TrigPushMany;

        boolean rtn = false;
        final sector_t[] sectors = levelLoader().sectors;
        int secnum = -1;

        while (true) {
            sector_t sec;
            if (manual) {
                // push trigger: just the sector on the line's backside, no tag search
                if (line.backsector == null) {
                    return rtn;
                }
                sec = line.backsector;
                secnum = sec.id;
            } else {
                secnum = FindSectorFromLineTag(line, secnum);
                if (secnum < 0) {
                    return rtn;
                }
                sec = sectors[secnum];
            }

            if (sec.specialdata == null) {
                rtn = true;
                final floormove_t floor = new floormove_t();
                sec.specialdata = floor;
                floor.thinkerFunction = ActiveStates.T_MoveFloor;
                AddThinker(floor);
                floor.crush = Crsh;
                floor.direction = Dirn != 0 ? 1 : -1;
                floor.sector = sec;
                floor.texture = sec.floorpic;
                floor.newspecial = sec.special;
                floor.type = floor_e.genFloor;

                floor.speed = switch (Sped) {
                    case 1 -> FLOORSPEED * 2;
                    case 2 -> FLOORSPEED * 4;
                    case 3 -> FLOORSPEED * 8;
                    default -> FLOORSPEED;
                };

                switch (Targ) {
                    case FtoHnF -> floor.floordestheight = sec.FindHighestFloorSurrounding();
                    case FtoLnF -> floor.floordestheight = sec.FindLowestFloorSurrounding();
                    case FtoNnF -> floor.floordestheight = Dirn != 0
                        ? sec.FindNextHighestFloor(sec.floorheight)
                        : boomFindNextLowestFloor(sec, sec.floorheight);
                    case FtoLnC -> floor.floordestheight = sec.FindLowestCeilingSurrounding();
                    case FtoC -> floor.floordestheight = sec.ceilingheight;
                    case FbyST -> {
                        int dest = floor.sector.floorheight
                            + floor.direction * boomFindShortestTextureAround(secnum);
                        // Boom clamps to +-32000 units against overflow
                        final int limit = 32000 << 16;
                        if (dest > limit) {
                            dest = limit;
                        }
                        if (dest < -limit) {
                            dest = -limit;
                        }
                        floor.floordestheight = dest;
                    }
                    case Fby24 -> floor.floordestheight =
                        floor.sector.floorheight + floor.direction * (24 << 16);
                    case Fby32 -> floor.floordestheight =
                        floor.sector.floorheight + floor.direction * (32 << 16);
                    default -> { }
                }

                if (ChgT != FNoChg) { // texture/type change requested
                    sector_t model;
                    if (ChgM != 0) {
                        // numeric model: the sector at the destination height
                        model = (Targ == FtoLnC || Targ == FtoC)
                            ? boomFindModelCeilingSector(floor.floordestheight, secnum)
                            : boomFindModelFloorSector(floor.floordestheight, secnum);
                    } else {
                        // trigger model: the line's front sector
                        model = line.frontsector;
                    }
                    if (model != null) {
                        floor.texture = model.floorpic;
                        switch (ChgT) {
                            case FChgZero -> {
                                floor.newspecial = 0;
                                floor.type = floor_e.genFloorChg0;
                            }
                            case FChgTyp -> {
                                floor.newspecial = model.special;
                                floor.type = floor_e.genFloorChgT;
                            }
                            case FChgTxt -> floor.type = floor_e.genFloorChg;
                            default -> { }
                        }
                    }
                }
            } else if (manual) {
                return rtn;
            }

            if (manual) {
                return rtn;
            }
        }
    }

    // ------------------------------------------------ Boom-only sector searches (p_spec.c)

    /** P_FindNextLowestFloor: highest surrounding floor below currentheight. */
    default int boomFindNextLowestFloor(sector_t sec, int currentheight) {
        int height = -500 << 16;
        boolean found = false;
        for (int i = 0; i < sec.linecount; i++) {
            final sector_t other = boomOtherSector(sec, sec.lines[i]);
            if (other != null && other.floorheight < currentheight
                && other.floorheight > height) {
                height = other.floorheight;
                found = true;
            }
        }
        return found ? height : currentheight;
    }

    /** P_FindShortestTextureAround: smallest bottom-texture height on the sector's
     * two-sided lines (fixed-point units). */
    default int boomFindShortestTextureAround(int secnum) {
        int minsize = 32000 << 16;
        final sector_t sec = levelLoader().sectors[secnum];
        for (int i = 0; i < sec.linecount; i++) {
            final line_t l = sec.lines[i];
            if (l.backsector != null && l.frontsector != null) {
                for (int s = 0; s < 2; s++) {
                    final int sn = l.sidenum[s]; // char: one-sided marker is 0xFFFF
                    final side_t side = sn != 0xFFFF ? levelLoader().sides[sn] : null;
                    if (side != null && side.bottomtexture > 0) {
                        final int h = DOOM().textureManager
                            .getTextureheight(side.bottomtexture);
                        if (h < minsize) {
                            minsize = h;
                        }
                    }
                }
            }
        }
        return minsize;
    }

    /** P_FindModelFloorSector: an adjacent sector whose floor sits at the destination. */
    default sector_t boomFindModelFloorSector(int floordestheight, int secnum) {
        final sector_t sec = levelLoader().sectors[secnum];
        for (int i = 0; i < sec.linecount; i++) {
            final sector_t other = boomOtherSector(sec, sec.lines[i]);
            if (other != null && other.floorheight == floordestheight) {
                return other;
            }
        }
        return null;
    }

    /** P_FindModelCeilingSector: an adjacent sector whose ceiling sits at the destination. */
    default sector_t boomFindModelCeilingSector(int ceildestheight, int secnum) {
        final sector_t sec = levelLoader().sectors[secnum];
        for (int i = 0; i < sec.linecount; i++) {
            final sector_t other = boomOtherSector(sec, sec.lines[i]);
            if (other != null && other.ceilingheight == ceildestheight) {
                return other;
            }
        }
        return null;
    }

    private sector_t boomOtherSector(sector_t sec, line_t line) {
        if (line.backsector == null) {
            return null;
        }
        return line.frontsector == sec ? line.backsector : line.frontsector;
    }

    // ------------------------------------------------------------------ EV_DoGenCeiling

    // ceiling bit fields (same layout as floors)
    int CeilCrush = 0x1000, CeilChange = 0x0c00, CeilTarget = 0x0380, CeilDirection = 0x0040,
        CeilModel = 0x0020, CeilSpeed = 0x0018;
    int CtoHnC = 0, CtoLnC = 1, CtoNnC = 2, CtoHnF = 3, CtoF = 4, CbyST = 5, Cby24 = 6, Cby32 = 7;

    /** Boom EV_DoGenCeiling: parameterized ceiling movers with change models. */
    default boolean EV_DoGenCeiling(line_t line) {
        final int value = (line.special & 0xFFFF) - GenCeilingBase;
        final boolean Crsh = (value & CeilCrush) != 0;
        final int ChgT = (value & CeilChange) >> 10;
        final int Targ = (value & CeilTarget) >> 7;
        final int Dirn = (value & CeilDirection) >> 6;
        final int ChgM = (value & CeilModel) >> 5;
        final int Sped = (value & CeilSpeed) >> 3;
        final int trig = value & TriggerType;
        final boolean manual = trig == TrigPushOnce || trig == TrigPushMany;

        boolean rtn = false;
        final sector_t[] sectors = levelLoader().sectors;
        int secnum = -1;
        while (true) {
            sector_t sec;
            if (manual) {
                if (line.backsector == null) {
                    return rtn;
                }
                sec = line.backsector;
                secnum = sec.id;
            } else {
                secnum = FindSectorFromLineTag(line, secnum);
                if (secnum < 0) {
                    return rtn;
                }
                sec = sectors[secnum];
            }

            if (sec.specialdata == null) {
                rtn = true;
                final ceiling_t ceiling = new ceiling_t();
                sec.specialdata = ceiling;
                ceiling.thinkerFunction = ActiveStates.T_MoveCeiling;
                AddThinker(ceiling);
                ceiling.sector = sec;
                ceiling.crush = Crsh;
                ceiling.direction = Dirn != 0 ? 1 : -1;
                ceiling.tag = sec.tag;
                ceiling.type = ceiling_e.genCeiling;
                ceiling.speed = switch (Sped) {
                    case 1 -> CEILSPEED * 2;
                    case 2 -> CEILSPEED * 4;
                    case 3 -> CEILSPEED * 8;
                    default -> CEILSPEED;
                };
                int target = sec.ceilingheight;
                switch (Targ) {
                    case CtoHnC -> target = sec.FindHighestCeilingSurrounding();
                    case CtoLnC -> target = sec.FindLowestCeilingSurrounding();
                    case CtoNnC -> target = Dirn != 0
                        ? boomFindNextHighestCeiling(sec, sec.ceilingheight)
                        : boomFindNextLowestCeiling(sec, sec.ceilingheight);
                    case CtoHnF -> target = sec.FindHighestFloorSurrounding();
                    case CtoF -> target = sec.floorheight;
                    case CbyST -> {
                        int dest = sec.ceilingheight
                            + ceiling.direction * boomFindShortestUpperAround(secnum);
                        final int limit = 32000 << 16;
                        if (dest > limit) {
                            dest = limit;
                        }
                        if (dest < -limit) {
                            dest = -limit;
                        }
                        target = dest;
                    }
                    case Cby24 -> target = sec.ceilingheight + ceiling.direction * (24 << 16);
                    case Cby32 -> target = sec.ceilingheight + ceiling.direction * (32 << 16);
                    default -> { }
                }
                if (ceiling.direction == 1) {
                    ceiling.topheight = target;
                    ceiling.bottomheight = sec.ceilingheight;
                } else {
                    ceiling.bottomheight = target;
                    ceiling.topheight = sec.ceilingheight;
                }

                // change models park their result on the ceiling thinker; the gen
                // completion in MoveCeiling applies them on arrival
                if (ChgT != FNoChg) {
                    sector_t model;
                    if (ChgM != 0) {
                        model = (Targ == CtoHnF || Targ == CtoF)
                            ? boomFindModelFloorSector(target, secnum)
                            : boomFindModelCeilingSector(target, secnum);
                    } else {
                        model = line.frontsector;
                    }
                    if (model != null) {
                        ceiling.genTexture = model.ceilingpic;
                        ceiling.genNewSpecial = switch (ChgT) {
                            case FChgZero -> 0;
                            case FChgTyp -> model.special;
                            default -> -1; // texture-only
                        };
                        ceiling.type = switch (ChgT) {
                            case FChgZero -> ceiling_e.genCeilingChg0;
                            case FChgTyp -> ceiling_e.genCeilingChgT;
                            default -> ceiling_e.genCeilingChg;
                        };
                    }
                }
                AddActiveCeiling(ceiling);
            } else if (manual) {
                return rtn;
            }
            if (manual) {
                return rtn;
            }
        }
    }

    // ------------------------------------------------------------------ EV_DoGenDoor

    int DoorDelay = 0x0300, DoorKind = 0x0060, DoorSpeed = 0x0018;
    int OdCDoor = 0, ODoor = 1, CdODoor = 2, CDoor = 3;

    /** Boom EV_DoGenDoor: parameterized doors mapped onto the vanilla door types (the
     * ticker sound + wait logic rides along). Boom delays: 1s/4.3s/8.6s/30s. */
    default boolean EV_DoGenDoor(line_t line) {
        final int value = (line.special & 0xFFFF) - GenDoorBase;
        final int Dely = (value & DoorDelay) >> 8;
        final int Kind = (value & DoorKind) >> 5;
        final int Sped = (value & DoorSpeed) >> 3;
        final int trig = value & TriggerType;
        final boolean manual = trig == TrigPushOnce || trig == TrigPushMany;

        boolean rtn = false;
        final sector_t[] sectors = levelLoader().sectors;
        int secnum = -1;
        while (true) {
            sector_t sec;
            if (manual) {
                if (line.backsector == null) {
                    return rtn;
                }
                sec = line.backsector;
                secnum = sec.id;
            } else {
                secnum = FindSectorFromLineTag(line, secnum);
                if (secnum < 0) {
                    return rtn;
                }
                sec = sectors[secnum];
            }

            if (sec.specialdata == null) {
                rtn = true;
                final vldoor_t door = new vldoor_t();
                sec.specialdata = door;
                door.thinkerFunction = ActiveStates.T_VerticalDoor;
                AddThinker(door);
                door.sector = sec;
                final boolean blaze = Sped >= 2;
                door.speed = switch (Sped) {
                    case 1 -> DoorDefines.VDOORSPEED * 2;
                    case 2 -> DoorDefines.VDOORSPEED * 4;
                    case 3 -> DoorDefines.VDOORSPEED * 8;
                    default -> DoorDefines.VDOORSPEED;
                };
                door.topwait = switch (Dely) {
                    case 1 -> 150;
                    case 2 -> 300;
                    case 3 -> 1050;
                    default -> 35;
                };
                switch (Kind) {
                    case ODoor -> {
                        door.type = blaze ? vldoor_e.blazeOpen : vldoor_e.open;
                        door.topheight = sec.FindLowestCeilingSurrounding() - (4 << 16);
                        door.direction = 1;
                        if (door.topheight != sec.ceilingheight) {
                            StartSound(sec.soundorg, blaze
                                ? sounds.sfxenum_t.sfx_bdopn : sounds.sfxenum_t.sfx_doropn);
                        }
                    }
                    case CDoor -> {
                        door.type = blaze ? vldoor_e.blazeClose : vldoor_e.close;
                        door.topheight = sec.FindLowestCeilingSurrounding() - (4 << 16);
                        door.direction = -1;
                        StartSound(sec.soundorg, blaze
                            ? sounds.sfxenum_t.sfx_bdcls : sounds.sfxenum_t.sfx_dorcls);
                    }
                    case CdODoor -> {
                        // v1: rides vanilla close30ThenOpen (fixed 30s), Boom delay ignored
                        door.type = vldoor_e.close30ThenOpen;
                        door.topheight = sec.ceilingheight;
                        door.direction = -1;
                        StartSound(sec.soundorg, blaze
                            ? sounds.sfxenum_t.sfx_bdcls : sounds.sfxenum_t.sfx_dorcls);
                    }
                    default -> { // OdCDoor: raise, wait, come back down
                        door.type = blaze ? vldoor_e.blazeRaise : vldoor_e.normal;
                        door.topheight = sec.FindLowestCeilingSurrounding() - (4 << 16);
                        door.direction = 1;
                        if (door.topheight != sec.ceilingheight) {
                            StartSound(sec.soundorg, blaze
                                ? sounds.sfxenum_t.sfx_bdopn : sounds.sfxenum_t.sfx_doropn);
                        }
                    }
                }
            } else if (manual) {
                return rtn;
            }
            if (manual) {
                return rtn;
            }
        }
    }

    // ------------------------------------------------------------- EV_DoGenLockedDoor

    int LockedNKeys = 0x0200, LockedKey = 0x01c0, LockedKind = 0x0020;

    /** Boom EV_DoGenLockedDoor: OdC or O doors behind the Boom key rules. */
    default boolean EV_DoGenLockedDoor(line_t line, mobj_t thing) {
        if (thing == null || thing.player == null) {
            return false;
        }
        if (!boomCanUnlockGenDoor(line, (player_t) thing.player)) {
            return false;
        }
        // run as a plain gen door of the same speed/trigger (kind bit 5: open-only)
        final int value = (line.special & 0xFFFF) - GenLockedBase;
        final int Kind = (value & LockedKind) >> 5;
        final int Sped = (value & DoorSpeed) >> 3;
        final int syntheticSpecial = GenDoorBase
            | ((Kind != 0 ? ODoor : OdCDoor) << 5)
            | (Sped << 3) | (value & TriggerType);
        final short saved = line.special;
        line.special = (short) syntheticSpecial;
        final boolean ok = EV_DoGenDoor(line);
        line.special = saved;
        return ok;
    }

    /** Boom P_CanUnlockGenDoor: key checks with the classic messages + grunt. */
    default boolean boomCanUnlockGenDoor(line_t line, player_t player) {
        final int value = (line.special & 0xFFFF) - GenLockedBase;
        final boolean skulliscard = (value & LockedNKeys) != 0;
        final int key = (value & LockedKey) >> 6;
        final boolean[] cards = player.cards;
        final boolean rc = cards[card_t.it_redcard.ordinal()];
        final boolean bc = cards[card_t.it_bluecard.ordinal()];
        final boolean yc = cards[card_t.it_yellowcard.ordinal()];
        final boolean rs = cards[card_t.it_redskull.ordinal()];
        final boolean bs = cards[card_t.it_blueskull.ordinal()];
        final boolean ys = cards[card_t.it_yellowskull.ordinal()];
        boolean ok;
        String msg;
        switch (key) {
            case 1 -> {
                ok = rc || (skulliscard && rs);
                msg = englsh.PD_REDO;
            }
            case 2 -> {
                ok = bc || (skulliscard && bs);
                msg = englsh.PD_BLUEO;
            }
            case 3 -> {
                ok = yc || (skulliscard && ys);
                msg = englsh.PD_YELLOWO;
            }
            case 4 -> {
                ok = rs || (skulliscard && rc);
                msg = englsh.PD_REDO;
            }
            case 5 -> {
                ok = bs || (skulliscard && bc);
                msg = englsh.PD_BLUEO;
            }
            case 6 -> {
                ok = ys || (skulliscard && yc);
                msg = englsh.PD_YELLOWO;
            }
            case 7 -> {
                ok = skulliscard ? ((rc || rs) && (bc || bs) && (yc || ys))
                    : (rc && bc && yc && rs && bs && ys);
                msg = "You need all the keys";
            }
            default -> {
                ok = rc || bc || yc || rs || bs || ys;
                msg = "You need a key";
            }
        }
        if (!ok) {
            player.message = msg;
            StartSound(player.mo, sounds.sfxenum_t.sfx_oof);
        }
        return ok;
    }

    // ------------------------------------------------------------------ EV_DoGenLift

    int LiftTarget = 0x0300, LiftDelay = 0x00c0, GenSpeedField = 0x0018;
    int F2LnF = 0, F2NnF = 1, F2LnC = 2, LnF2HnF = 3;

    /** Boom EV_DoGenLift: parameterized platforms on the vanilla plat thinker. */
    default boolean EV_DoGenLift(line_t line) {
        final int value = (line.special & 0xFFFF) - GenLiftBase;
        final int Targ = (value & LiftTarget) >> 8;
        final int Dely = (value & LiftDelay) >> 6;
        final int Sped = (value & GenSpeedField) >> 3;
        final int trig = value & TriggerType;
        final boolean manual = trig == TrigPushOnce || trig == TrigPushMany;

        boolean rtn = false;
        final sector_t[] sectors = levelLoader().sectors;
        int secnum = -1;
        while (true) {
            sector_t sec;
            if (manual) {
                if (line.backsector == null) {
                    return rtn;
                }
                sec = line.backsector;
                secnum = sec.id;
            } else {
                secnum = FindSectorFromLineTag(line, secnum);
                if (secnum < 0) {
                    return rtn;
                }
                sec = sectors[secnum];
            }

            if (sec.specialdata == null) {
                rtn = true;
                final plat_t plat = new plat_t();
                sec.specialdata = plat;
                plat.thinkerFunction = ActiveStates.T_PlatRaise;
                AddThinker(plat);
                plat.sector = sec;
                plat.crush = false;
                plat.tag = line.tag;
                plat.type = Targ == LnF2HnF ? plattype_e.perpetualRaise
                    : plattype_e.downWaitUpStay;
                plat.high = sec.floorheight;
                plat.low = switch (Targ) {
                    case F2NnF -> boomFindNextLowestFloor(sec, sec.floorheight);
                    case F2LnC -> Math.min(sec.FindLowestCeilingSurrounding(),
                        sec.floorheight);
                    default -> Math.min(sec.FindLowestFloorSurrounding(), sec.floorheight);
                };
                if (Targ == LnF2HnF) {
                    plat.high = Math.max(sec.FindHighestFloorSurrounding(), sec.floorheight);
                }
                plat.speed = switch (Sped) {
                    case 1 -> PLATSPEED * 4;
                    case 2 -> PLATSPEED * 8;
                    case 3 -> PLATSPEED * 16;
                    default -> PLATSPEED * 2;
                };
                plat.wait = switch (Dely) {
                    case 1 -> 105;
                    case 2 -> 165;
                    case 3 -> 350;
                    default -> 35;
                };
                plat.status = plat_e.down;
                AddActivePlat(plat);
                StartSound(sec.soundorg, sounds.sfxenum_t.sfx_pstart);
            } else if (manual) {
                return rtn;
            }
            if (manual) {
                return rtn;
            }
        }
    }

    // ------------------------------------------------------------------ EV_DoGenStairs

    int StairIgnore = 0x0200, StairDirection = 0x0100, StairStep = 0x00c0;

    /** Boom EV_DoGenStairs: parameterized staircases (step 4/8/16/24, both directions,
     * texture-ignore bit). v1: reuse does not flip build direction. */
    default boolean EV_DoGenStairs(line_t line) {
        final int value = (line.special & 0xFFFF) - GenStairsBase;
        final boolean Igno = (value & StairIgnore) != 0;
        final int Dirn = (value & StairDirection) >> 8;
        final int Step = (value & StairStep) >> 6;
        final int Sped = (value & GenSpeedField) >> 3;

        final int stairsize = switch (Step) {
            case 1 -> 8 << 16;
            case 2 -> 16 << 16;
            case 3 -> 24 << 16;
            default -> 4 << 16;
        };
        final int speed = switch (Sped) {
            case 1 -> FLOORSPEED / 2;
            case 2 -> FLOORSPEED * 2;
            case 3 -> FLOORSPEED * 4;
            default -> FLOORSPEED / 4;
        };
        final int direction = Dirn != 0 ? 1 : -1;

        boolean rtn = false;
        int secnum = -1;
        while ((secnum = FindSectorFromLineTag(line, secnum)) >= 0) {
            sector_t sec = levelLoader().sectors[secnum];
            if (sec.specialdata != null) {
                continue;
            }
            rtn = true;
            floormove_t floor = new floormove_t();
            sec.specialdata = floor;
            floor.thinkerFunction = ActiveStates.T_MoveFloor;
            AddThinker(floor);
            floor.direction = direction;
            floor.sector = sec;
            floor.speed = speed;
            int height = sec.floorheight + direction * stairsize;
            floor.floordestheight = height;
            floor.type = floor_e.genFloor;
            floor.crush = false;

            final int texture = sec.floorpic;
            boolean ok;
            do {
                ok = false;
                for (int i = 0; i < sec.linecount; i++) {
                    final line_t l = sec.lines[i];
                    if (l.backsector == null || l.frontsector == null) {
                        continue;
                    }
                    if (l.frontsector != sec) {
                        continue;
                    }
                    final sector_t tsec = l.backsector;
                    if (!Igno && tsec.floorpic != texture) {
                        continue;
                    }
                    if (tsec.specialdata != null) {
                        continue;
                    }
                    height += direction * stairsize;
                    sec = tsec;
                    rtn = true;
                    floor = new floormove_t();
                    sec.specialdata = floor;
                    floor.thinkerFunction = ActiveStates.T_MoveFloor;
                    AddThinker(floor);
                    floor.direction = direction;
                    floor.sector = sec;
                    floor.speed = speed;
                    floor.floordestheight = height;
                    floor.type = floor_e.genFloor;
                    floor.crush = false;
                    ok = true;
                    break;
                }
            } while (ok);
        }
        return rtn;
    }

    // ---------------------------------------------------------------- EV_DoGenCrusher

    int CrusherSilent = 0x0040;

    /** Boom EV_DoGenCrusher, mapped onto the vanilla crusher types. v1 note: after a
     * full stroke the vanilla ticker resets speed to CEILSPEED (Boom keeps the chosen
     * speed) — visible only on fast/turbo crushers. */
    default boolean EV_DoGenCrusher(line_t line) {
        final int value = (line.special & 0xFFFF) - GenCrusherBase;
        final boolean silent = (value & CrusherSilent) != 0;
        final int Sped = (value & GenSpeedField) >> 3;
        final int trig = value & TriggerType;
        final boolean manual = trig == TrigPushOnce || trig == TrigPushMany;

        boolean rtn = false;
        final sector_t[] sectors = levelLoader().sectors;
        int secnum = -1;
        while (true) {
            sector_t sec;
            if (manual) {
                if (line.backsector == null) {
                    return rtn;
                }
                sec = line.backsector;
                secnum = sec.id;
            } else {
                secnum = FindSectorFromLineTag(line, secnum);
                if (secnum < 0) {
                    return rtn;
                }
                sec = sectors[secnum];
            }

            if (sec.specialdata == null) {
                rtn = true;
                final ceiling_t ceiling = new ceiling_t();
                sec.specialdata = ceiling;
                ceiling.thinkerFunction = ActiveStates.T_MoveCeiling;
                AddThinker(ceiling);
                ceiling.sector = sec;
                ceiling.crush = true;
                ceiling.type = silent ? ceiling_e.silentCrushAndRaise : ceiling_e.crushAndRaise;
                ceiling.tag = sec.tag;
                ceiling.topheight = sec.ceilingheight;
                ceiling.bottomheight = sec.floorheight + (8 << 16);
                ceiling.direction = -1;
                ceiling.speed = switch (Sped) {
                    case 1 -> CEILSPEED * 2;
                    case 2 -> CEILSPEED * 4;
                    case 3 -> CEILSPEED * 8;
                    default -> CEILSPEED;
                };
                AddActiveCeiling(ceiling);
            } else if (manual) {
                return rtn;
            }
            if (manual) {
                return rtn;
            }
        }
    }

    // ------------------------------------------------- Boom-only ceiling searches

    /** P_FindNextHighestCeiling: lowest surrounding ceiling above currentheight. */
    default int boomFindNextHighestCeiling(sector_t sec, int currentheight) {
        int height = 32000 << 16;
        boolean found = false;
        for (int i = 0; i < sec.linecount; i++) {
            final sector_t other = boomOtherSector(sec, sec.lines[i]);
            if (other != null && other.ceilingheight > currentheight
                && other.ceilingheight < height) {
                height = other.ceilingheight;
                found = true;
            }
        }
        return found ? height : currentheight;
    }

    /** P_FindNextLowestCeiling: highest surrounding ceiling below currentheight. */
    default int boomFindNextLowestCeiling(sector_t sec, int currentheight) {
        int height = -32000 << 16;
        boolean found = false;
        for (int i = 0; i < sec.linecount; i++) {
            final sector_t other = boomOtherSector(sec, sec.lines[i]);
            if (other != null && other.ceilingheight < currentheight
                && other.ceilingheight > height) {
                height = other.ceilingheight;
                found = true;
            }
        }
        return found ? height : currentheight;
    }

    /** P_FindShortestUpperAround: smallest top-texture height on two-sided lines. */
    default int boomFindShortestUpperAround(int secnum) {
        int minsize = 32000 << 16;
        final sector_t sec = levelLoader().sectors[secnum];
        for (int i = 0; i < sec.linecount; i++) {
            final line_t l = sec.lines[i];
            if (l.backsector != null && l.frontsector != null) {
                for (int s = 0; s < 2; s++) {
                    final int sn = l.sidenum[s];
                    final side_t side = sn != 0xFFFF ? levelLoader().sides[sn] : null;
                    if (side != null && side.toptexture > 0) {
                        final int h = DOOM().textureManager
                            .getTextureheight(side.toptexture);
                        if (h < minsize) {
                            minsize = h;
                        }
                    }
                }
            }
        }
        return minsize;
    }

    // ============================================================ BOOM SCROLLERS
    // p_spec.c T_Scroll / P_SpawnScrollers: walls, flats and the CARRY conveyors that
    // drive voodoo-doll script closets. Without the carry conveyors those scripts never
    // run, because nothing conveys the dolls across their trigger lines.

    int SCROLL_SHIFT = 5;
    int CARRYFACTOR = 6144; // FRACUNIT * 0.09375 — with DOOM friction, steady drift = line speed

    /** The per-tic scroller (ActiveStates.T_BoomScroll). */
    default void T_BoomScroll(doom.thinker_t th) {
        final p.scroll_t s = (p.scroll_t) th;
        int dx = s.dx, dy = s.dy;
        if (s.control != -1) {
            final sector_t c = levelLoader().sectors[s.control];
            final int height = c.floorheight + c.ceilingheight;
            final int delta = height - s.lastHeight;
            s.lastHeight = height;
            dx = m.fixed_t.FixedMul(dx, delta);
            dy = m.fixed_t.FixedMul(dy, delta);
        }
        if (s.accel) {
            s.vdx = dx += s.vdx;
            s.vdy = dy += s.vdy;
        }
        if (dx == 0 && dy == 0) {
            return;
        }
        switch (s.type) {
            case p.scroll_t.SC_SIDE -> {
                final side_t side = levelLoader().sides[s.affectee];
                side.textureoffset += dx;
                side.rowoffset += dy;
            }
            case p.scroll_t.SC_CARRY -> {
                final sector_t sec = levelLoader().sectors[s.affectee];
                for (mobj_t t = sec.thinglist; t != null; t = (mobj_t) t.snext) {
                    if ((t.flags & mobj_t.MF_NOCLIP) == 0
                        && !((t.flags & mobj_t.MF_NOGRAVITY) != 0 || t.z > sec.floorheight)) {
                        t.momx += dx;
                        t.momy += dy;
                    }
                }
            }
            default -> {
                // SC_FLOOR / SC_CEILING move only the flat's texture — a visual the MC
                // renderer will pick up in a later slice; the thinker still runs so
                // control/accel state stays Boom-exact.
            }
        }
    }

    private void addBoomScroller(int type, int dx, int dy, int control, int affectee,
                                 boolean accel) {
        final p.scroll_t s = new p.scroll_t();
        s.type = type;
        s.dx = dx;
        s.dy = dy;
        s.control = control;
        s.affectee = affectee;
        s.accel = accel;
        if (control != -1) {
            final sector_t c = levelLoader().sectors[control];
            s.lastHeight = c.floorheight + c.ceilingheight;
        }
        s.thinkerFunction = ActiveStates.T_BoomScroll;
        AddThinker(s);
    }

    /** P_SpawnScrollers, called at the end of SpawnSpecials. */
    default void spawnBoomScrollers() {
        final rr.line_t[] lines = levelLoader().lines;
        final sector_t[] sectors = levelLoader().sectors;
        if (lines == null) {
            return;
        }
        for (int i = 0; i < lines.length; i++) {
            final line_t l = lines[i];
            int special = l.special & 0xFFFF;
            final int dx = l.dx >> SCROLL_SHIFT;
            final int dy = l.dy >> SCROLL_SHIFT;
            int control = -1;
            boolean accel = false;
            if (special >= 245 && special <= 249) {        // displacement family
                control = l.frontsector != null ? l.frontsector.id : -1;
                special = 250 + (special - 245);
            } else if (special >= 214 && special <= 218) { // accelerative family
                control = l.frontsector != null ? l.frontsector.id : -1;
                accel = true;
                special = 250 + (special - 214);
            }
            switch (special) {
                case 250 -> { // scroll ceiling
                    for (int sn = -1; (sn = FindSectorFromLineTag(l, sn)) >= 0; ) {
                        addBoomScroller(p.scroll_t.SC_CEILING, -dx, dy, control, sn, accel);
                    }
                }
                case 251 -> { // scroll floor
                    for (int sn = -1; (sn = FindSectorFromLineTag(l, sn)) >= 0; ) {
                        addBoomScroller(p.scroll_t.SC_FLOOR, -dx, dy, control, sn, accel);
                    }
                }
                case 252 -> { // carry things
                    for (int sn = -1; (sn = FindSectorFromLineTag(l, sn)) >= 0; ) {
                        addBoomScroller(p.scroll_t.SC_CARRY,
                            m.fixed_t.FixedMul(dx, CARRYFACTOR),
                            m.fixed_t.FixedMul(dy, CARRYFACTOR), control, sn, accel);
                    }
                }
                case 253 -> { // scroll floor AND carry
                    for (int sn = -1; (sn = FindSectorFromLineTag(l, sn)) >= 0; ) {
                        addBoomScroller(p.scroll_t.SC_FLOOR, -dx, dy, control, sn, accel);
                        addBoomScroller(p.scroll_t.SC_CARRY,
                            m.fixed_t.FixedMul(dx, CARRYFACTOR),
                            m.fixed_t.FixedMul(dy, CARRYFACTOR), control, sn, accel);
                    }
                }
                case 254 -> { // scroll tagged walls by the line vector
                    for (int li = 0; li < lines.length; li++) {
                        if (lines[li].tag == l.tag && li != i
                            && lines[li].sidenum[0] != 0xFFFF) {
                            addBoomScroller(p.scroll_t.SC_SIDE, dx, dy,
                                control, lines[li].sidenum[0], accel);
                        }
                    }
                }
                case 255 -> { // scroll this wall by its own sidedef offsets
                    if (l.sidenum[0] != 0xFFFF) {
                        final side_t sd = levelLoader().sides[l.sidenum[0]];
                        addBoomScroller(p.scroll_t.SC_SIDE,
                            sd.textureoffset, sd.rowoffset, -1, l.sidenum[0], false);
                    }
                }
                default -> { }
            }
        }
    }

    // ==================================================== BOOM FRICTION + PUSHERS
    // p_spec.c P_SpawnFriction / P_SpawnPushers / T_Pusher / PIT_PushThing. Types
    // 223 (friction), 224 (wind), 225 (current), 226 (point push/pull) are PASSIVE
    // spawn-time specials: nothing dispatches them on cross/use/shoot (the vanilla
    // switches silently skip unmatched specials), they only seed state here.
    //
    // The values only ACT while the tagged sector's special carries the matching
    // generalized bit (FRICTION_MASK 0x100 / PUSH_MASK 0x200) — Boom's on/off switch.
    //
    // v1 simplifications, documented per site: friction lives on sector_t (killough's
    // MBF form of the same behavior) and reads the mobj's CENTER sector (no
    // touching_thinglist straddle rule); wind/current iterate sec.thinglist (center-in-
    // sector, like the carry scrollers) instead of touching_thinglist; the deep-water
    // heightsec cases collapse to floor-standing vs airborne (mocha has no 242 transfer
    // heights yet); point pushers take their position from the recorded 5001/5002 map
    // things because mocha has no MT_PUSH/MT_PULL mobj types.

    /** Boom p_spec.h PUSH_FACTOR. */
    int PUSH_FACTOR = 7;

    /** Recorded 5001/5002 (MT_PUSH/MT_PULL) map things: {x, y, doomednum} in integer
     * map units — captured by SpawnMapThing at load, consumed by spawnBoomPushers. */
    ContextKey<BoomPushPoints> KEY_BOOM_PUSH_POINTS =
        ACTION_KEY_CHAIN.newKey(ActionsBoom.class, BoomPushPoints::new);

    final class BoomPushPoints {
        public final java.util.List<int[]> points = new java.util.ArrayList<>();
    }

    /** SpawnSpecials hook: Boom runs P_SpawnFriction then P_SpawnPushers, after the
     * scrollers. */
    default void spawnBoomFrictionPushers() {
        spawnBoomFriction();
        spawnBoomPushers();
    }

    /**
     * P_SpawnFriction: each 223 line turns its LENGTH into a friction + movefactor
     * pair for every tagged sector. Boom 2.02 kept per-sector thinkers re-stamping
     * mobj fields every tic; the values themselves never change after spawn, so they
     * live on sector_t (additive fields) and the friction application site reads them
     * directly. Boom's mud-over-ice precedence (T_Friction only lowered the value)
     * becomes a min() here — same per-tic outcome.
     */
    default void spawnBoomFriction() {
        final line_t[] lines = levelLoader().lines;
        if (lines == null) {
            return;
        }
        for (final line_t l : lines) {
            if ((l.special & 0xFFFF) != 223) {
                continue;
            }
            final int length = p.MapUtils.AproxDistance(l.dx, l.dy) >> m.fixed_t.FRACBITS;
            final int friction = (0x1EB8 * length) / 0x80 + 0xD000;
            // Higher friction value = LESS friction (the move keeps friction/0x10000).
            final int movefactor = friction > sector_t.ORIG_FRICTION
                ? ((0x10092 - friction) * 0x70) / 0x158  // ice: harder to get moving
                : ((friction - 0xDB34) * 0xA) / 0x80;    // mud: much harder
            for (int s = -1; (s = FindSectorFromLineTag(l, s)) >= 0; ) {
                final sector_t sec = levelLoader().sectors[s];
                if (sec.boomFriction == sector_t.ORIG_FRICTION
                    || friction < sec.boomFriction) { // mud precedence, Boom T_Friction
                    sec.boomFriction = friction;
                    sec.boomMoveFactor = movefactor;
                }
            }
        }
    }

    /**
     * P_SpawnPushers: 224 wind / 225 current push tagged sectors along the line
     * vector; 226 needs a point source — Boom looks for an MT_PUSH/MT_PULL thing in
     * the tagged sector, we look through the positions SpawnMapThing recorded for
     * doomednums 5001/5002 (no recorded point in the sector = no effect, like Boom).
     */
    default void spawnBoomPushers() {
        final line_t[] lines = levelLoader().lines;
        final java.util.List<int[]> points = contextRequire(KEY_BOOM_PUSH_POINTS).points;
        if (lines != null) {
            for (final line_t l : lines) {
                switch (l.special & 0xFFFF) {
                    case 224 -> { // wind
                        for (int s = -1; (s = FindSectorFromLineTag(l, s)) >= 0; ) {
                            addBoomPusher(pusher_t.PT_WIND, l.dx, l.dy, null, s);
                        }
                    }
                    case 225 -> { // current
                        for (int s = -1; (s = FindSectorFromLineTag(l, s)) >= 0; ) {
                            addBoomPusher(pusher_t.PT_CURRENT, l.dx, l.dy, null, s);
                        }
                    }
                    case 226 -> { // point pusher/puller
                        for (int s = -1; (s = FindSectorFromLineTag(l, s)) >= 0; ) {
                            final int[] pt = boomFindPushPoint(points, s);
                            if (pt != null) {
                                addBoomPusher(pusher_t.PT_POINT, l.dx, l.dy, pt, s);
                            }
                        }
                    }
                    default -> { }
                }
            }
        }
        points.clear(); // consumed; the next level's LoadThings starts clean
    }

    /** P_GetPushThing, over the recorded points: first one inside sector s wins. */
    private int[] boomFindPushPoint(java.util.List<int[]> points, int s) {
        for (final int[] pt : points) {
            final rr.subsector_t ss = levelLoader().PointInSubsector(
                pt[0] << m.fixed_t.FRACBITS, pt[1] << m.fixed_t.FRACBITS);
            if (ss != null && ss.sector != null && ss.sector.id == s) {
                return pt;
            }
        }
        return null;
    }

    /** Add_Pusher. Magnitudes come from the LINE vector even for point sources. */
    private void addBoomPusher(int type, int dx, int dy, int[] point, int affectee) {
        final pusher_t pu = new pusher_t();
        pu.type = type;
        pu.xMag = dx >> m.fixed_t.FRACBITS;
        pu.yMag = dy >> m.fixed_t.FRACBITS;
        pu.magnitude = p.MapUtils.AproxDistance(pu.xMag, pu.yMag);
        pu.affectee = affectee;
        if (point != null) { // point source: position from the recorded map thing
            pu.radius = pu.magnitude << (m.fixed_t.FRACBITS + 1); // force reaches zero
            pu.x = point[0] << m.fixed_t.FRACBITS;
            pu.y = point[1] << m.fixed_t.FRACBITS;
            pu.pull = point[2] == 5002; // MT_PULL drags toward the point
            // Detached sight-check stand-in where the MT_PUSH/MT_PULL thing would be
            // (never linked into the world): CheckSight only reads x/y/z/height and
            // the subsector for REJECT.
            final mobj_t src = mobj_t.createOn(DOOM());
            src.x = pu.x;
            src.y = pu.y;
            src.height = 8 << m.fixed_t.FRACBITS;
            src.subsector = levelLoader().PointInSubsector(pu.x, pu.y);
            src.z = src.subsector.sector.floorheight;
            pu.source = src;
        }
        pu.thinkerFunction = ActiveStates.T_BoomPusher;
        AddThinker(pu);
    }

    /**
     * T_Pusher (ActiveStates.T_BoomPusher), the per-tic force. Players only —
     * Boom 2.02 explicitly deferred "Things other than players" to a Phase II that
     * never shipped in 2.02.
     */
    default void T_BoomPusher(doom.thinker_t th) {
        final pusher_t pu = (pusher_t) th;
        final sector_t sec = levelLoader().sectors[pu.affectee];
        // The sector special is the on/off switch; it can change under us (transfers).
        if ((sec.special & sector_t.PUSH_MASK) == 0) {
            return;
        }

        if (pu.type == pusher_t.PT_POINT) {
            // PIT_PushThing over the players (Boom's blockmap scan only ever affects
            // players; iterating them directly is the same set, box slop aside).
            for (int i = 0; i < data.Limits.MAXPLAYERS; i++) {
                if (!PlayerInGame(i)) {
                    continue;
                }
                final player_t pl = getPlayer(i);
                final mobj_t thing = pl.mo;
                if (thing == null
                    || (thing.flags & (mobj_t.MF_NOGRAVITY | mobj_t.MF_NOCLIP)) != 0) {
                    continue;
                }
                final int dist = p.MapUtils.AproxDistance(thing.x - pu.x, thing.y - pu.y);
                final int speed = (pu.magnitude - ((dist >> m.fixed_t.FRACBITS) >> 1))
                    << (m.fixed_t.FRACBITS - PUSH_FACTOR - 1);
                // outside the effective radius, or no line of sight to the point
                if (speed <= 0 || pu.source == null || !CheckSight(thing, pu.source)) {
                    continue;
                }
                long pushangle = sceneRenderer().PointToAngle2(thing.x, thing.y, pu.x, pu.y);
                if (!pu.pull) {
                    pushangle += data.Tables.ANG180; // away from an MT_PUSH source
                }
                pl.Thrust(pushangle & data.Tables.BITS32, speed);
            }
            return;
        }

        // Constant pushers (wind/current). v1: center-in-sector things (thinglist, like
        // the carry scrollers) and no heightsec water cases — grounded vs airborne only.
        for (mobj_t thing = sec.thinglist; thing != null; thing = (mobj_t) thing.snext) {
            if (thing.player == null
                || (thing.flags & (mobj_t.MF_NOGRAVITY | mobj_t.MF_NOCLIP)) != 0) {
                continue;
            }
            final int xspeed, yspeed;
            if (pu.type == pusher_t.PT_WIND) {
                if (thing.z > thing.floorz) { // airborne: full force
                    xspeed = pu.xMag;
                    yspeed = pu.yMag;
                } else {                      // on ground: half force
                    xspeed = pu.xMag >> 1;
                    yspeed = pu.yMag >> 1;
                }
            } else { // PT_CURRENT
                if (thing.z > sec.floorheight) { // above ground: no force
                    xspeed = yspeed = 0;
                } else {                         // on ground: full force
                    xspeed = pu.xMag;
                    yspeed = pu.yMag;
                }
            }
            thing.momx += xspeed << (m.fixed_t.FRACBITS - PUSH_FACTOR);
            thing.momy += yspeed << (m.fixed_t.FRACBITS - PUSH_FACTOR);
        }
    }

    // ============================================================ BOOM FIXED TYPES
    // Boom v2.02 p_spec.c extended linedef types 142-269: the jff 1/29/98 table that
    // gives every vanilla action all of its W1/WR/S1/SR/G1 varieties, plus the new
    // Boom actions (silent teleports, elevators, toggle plats, no-motion changes,
    // next-lower floors, ceiling lowers). Dispatched from the three vanilla
    // dispatchers right after the generalized hooks. Vanilla specials (< 142) never
    // enter these lanes; the generalized range (>= 0x2F80) is handled above.
    //
    // 214-218/245-255 are the passive scroller families spawned at level load by
    // spawnBoomScrollers — they are consumed silently here, never dispatched.
    // Types owned by other slices (213/261 light transfers, 223-226 friction and
    // pushers, 242 deep water, 260 translucency, MBF 271/272 sky transfers) are
    // consumed safely with a one-time log so no Boom map can crash the vanilla path.

    /** Is this special one of Boom's fixed extended types (or an MBF sky transfer)? */
    static boolean isBoomFixed(int special) {
        return (special >= 142 && special <= 269) || special == 271 || special == 272;
    }

    /** One-time "not ported yet" log guard, shared by the three lanes. */
    Set<Integer> BOOM_FIXED_STUBBED = new HashSet<>();

    private void boomFixedNotPorted(int sp) {
        if (BOOM_FIXED_STUBBED.add(sp)) {
            System.out.println("[lattedoom-boom] type " + sp + " not ported yet (consumed safely)");
        }
    }

    /**
     * The fixed types that are level-load properties, not triggers. Scrollers
     * (214-218 accelerative, 245-249 displacement, 250-255 plain) are already
     * spawned by spawnBoomScrollers and consume silently; the rest belong to
     * other slices and log once. Returns true when the special was passive.
     */
    private boolean boomFixedPassive(int sp) {
        if ((sp >= 214 && sp <= 218) || (sp >= 245 && sp <= 255)) {
            return true; // scrollers: ported, spawned at level load, nothing to trigger
        }
        switch (sp) {
            case 213: case 261:                     // light transfers
            case 223: case 224: case 225: case 226: // friction + pushers (boom/friction-pushers slice)
            case 242:                               // deep water (renderer work)
            case 260:                               // translucency (renderer work)
            case 271: case 272:                     // MBF sky transfers
                boomFixedNotPorted(sp);
                return true;
            default:
                return false;
        }
    }

    /**
     * Boom P_CheckTag, filtered to the fixed extended range: a zero-tag line may
     * only fire the types that need no tag (lights, thing teleports, exits).
     * Note 268/269 are absent from Boom's exemption list — quirk preserved.
     */
    private boolean boomFixedTagOk(line_t line, int sp) {
        if (line.tag != 0) {
            return true;
        }
        switch (sp) {
            case 156: case 157: case 169: case 170: case 171: case 172: case 173:
            case 174: case 192: case 193: case 194: case 195: case 197: case 198:
            case 207: case 208: case 209: case 210:
                return true;
            default:
                return false;
        }
    }

    /**
     * CROSS (walkover) lane for the fixed extended types. Returns true when the
     * special was recognized (fired or safely consumed) — the vanilla switch is
     * then skipped. Trigger table per Boom p_spec.c P_CrossSpecialLine.
     */
    default boolean crossBoomFixed(line_t line, int side, mobj_t thing) {
        final int sp = line.special & 0xFFFF;
        if (!isBoomFixed(sp)) {
            return false;
        }
        if (boomFixedPassive(sp)) {
            return true;
        }
        if (thing.player == null) {
            // things that should never trigger walkovers (vanilla missile list)
            switch (thing.type) {
                case MT_ROCKET:
                case MT_PLASMA:
                case MT_BFG:
                case MT_TROOPSHOT:
                case MT_HEADSHOT:
                case MT_BRUISERSHOT:
                    return true;
                default:
                    break;
            }
            // jff 3/5/98 monsters may cross only the silent teleporter types
            switch (sp) {
                case 207: case 208: case 243: case 244:
                case 262: case 263: case 264: case 265:
                case 266: case 267: case 268: case 269:
                    break;
                default:
                    return true;
            }
        }
        if (!boomFixedTagOk(line, sp)) {
            return true;
        }

        switch (sp) {
            // ---------------- Extended walk-once triggers (W1) ----------------
            case 142: // W1 Raise Floor 512
                if (DoFloor(line, floor_e.raiseFloor512)) line.special = 0;
                break;
            case 143: // W1 Plat Raise 24 and Change
                if (DoPlat(line, plattype_e.raiseAndChange, 24)) line.special = 0;
                break;
            case 144: // W1 Plat Raise 32 and Change
                if (DoPlat(line, plattype_e.raiseAndChange, 32)) line.special = 0;
                break;
            case 145: // W1 Ceiling Lower to Floor
                if (DoCeiling(line, ceiling_e.lowerToFloor)) line.special = 0;
                break;
            case 146: // W1 Lower Pillar, Raise Donut
                if (DoDonut(line)) line.special = 0;
                break;
            case 153: // W1 Change Texture/Type Only (trigger model)
                if (EV_DoChange(line, BOOM_CHG_TRIG)) line.special = 0;
                break;
            case 199: // W1 Ceiling Lower to Lowest Ceiling
                if (DoCeiling(line, ceiling_e.lowerToLowest)) line.special = 0;
                break;
            case 200: // W1 Ceiling Lower to Highest Floor
                if (DoCeiling(line, ceiling_e.lowerToMaxFloor)) line.special = 0;
                break;
            case 207: // W1 Silent Teleport (killough 2/16/98)
                if (EV_SilentTeleport(line, side, thing)) line.special = 0;
                break;
            case 219: // W1 Floor Lower to Next Lower Neighbor
                if (DoFloor(line, floor_e.lowerFloorToNearest)) line.special = 0;
                break;
            case 227: // W1 Elevator Raise to Next Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_UP)) line.special = 0;
                break;
            case 231: // W1 Elevator Lower to Next Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_DOWN)) line.special = 0;
                break;
            case 235: // W1 Elevator to Current Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_CURRENT)) line.special = 0;
                break;
            case 239: // W1 Change Texture/Type Only (numeric model)
                if (EV_DoChange(line, BOOM_CHG_NUM)) line.special = 0;
                break;
            case 243: // W1 Silent Line Teleport (killough 2/16/98)
                if (EV_SilentLineTeleport(line, side, thing, false)) line.special = 0;
                break;
            case 262: // W1 Silent Line Teleport Reversed (jff 4/14/98)
                if (EV_SilentLineTeleport(line, side, thing, true)) line.special = 0;
                break;
            case 264: // W1 Silent Line Teleport Reversed, monsters only
                if (thing.player == null
                    && EV_SilentLineTeleport(line, side, thing, true)) line.special = 0;
                break;
            case 266: // W1 Silent Line Teleport, monsters only
                if (thing.player == null
                    && EV_SilentLineTeleport(line, side, thing, false)) line.special = 0;
                break;
            case 268: // W1 Silent Teleport, monsters only
                if (thing.player == null
                    && EV_SilentTeleport(line, side, thing)) line.special = 0;
                break;

            // ------------- Extended walk-many retriggerable (WR) --------------
            case 147: // WR Raise Floor 512
                DoFloor(line, floor_e.raiseFloor512);
                break;
            case 148: // WR Plat Raise 24 and Change
                DoPlat(line, plattype_e.raiseAndChange, 24);
                break;
            case 149: // WR Plat Raise 32 and Change
                DoPlat(line, plattype_e.raiseAndChange, 32);
                break;
            case 150: // WR Start Slow Silent Crusher
                DoCeiling(line, ceiling_e.silentCrushAndRaise);
                break;
            case 151: // WR Raise Ceiling, Lower Floor
                DoCeiling(line, ceiling_e.raiseToHighest);
                DoFloor(line, floor_e.lowerFloorToLowest);
                break;
            case 152: // WR Ceiling Lower to Floor
                DoCeiling(line, ceiling_e.lowerToFloor);
                break;
            case 154: // WR Change Texture/Type Only (trigger model)
                EV_DoChange(line, BOOM_CHG_TRIG);
                break;
            case 155: // WR Lower Pillar, Raise Donut
                DoDonut(line);
                break;
            case 156: // WR Start Lights Strobing
                StartLightStrobing(line);
                break;
            case 157: // WR Lights to Dimmest Near
                TurnTagLightsOff(line);
                break;
            case 201: // WR Ceiling Lower to Lowest Ceiling
                DoCeiling(line, ceiling_e.lowerToLowest);
                break;
            case 202: // WR Ceiling Lower to Highest Floor
                DoCeiling(line, ceiling_e.lowerToMaxFloor);
                break;
            case 208: // WR Silent Teleport
                EV_SilentTeleport(line, side, thing);
                break;
            case 212: // WR Instant Toggle Floor (jff 3/14/98)
                DoPlat(line, plattype_e.toggleUpDn, 0);
                break;
            case 220: // WR Floor Lower to Next Lower Neighbor
                DoFloor(line, floor_e.lowerFloorToNearest);
                break;
            case 228: // WR Elevator Raise to Next Floor
                EV_DoElevator(line, elevator_t.ELEVATE_UP);
                break;
            case 232: // WR Elevator Lower to Next Floor
                EV_DoElevator(line, elevator_t.ELEVATE_DOWN);
                break;
            case 236: // WR Elevator to Current Floor
                EV_DoElevator(line, elevator_t.ELEVATE_CURRENT);
                break;
            case 240: // WR Change Texture/Type Only (numeric model)
                EV_DoChange(line, BOOM_CHG_NUM);
                break;
            case 244: // WR Silent Line Teleport
                EV_SilentLineTeleport(line, side, thing, false);
                break;
            case 256: // WR Build Stairs, step 8 (jff 3/16/98 renumbered 153->256)
                BuildStairs(line, stair_e.build8);
                break;
            case 257: // WR Build Stairs Turbo, step 16 (jff 3/16/98 renumbered 154->257)
                BuildStairs(line, stair_e.turbo16);
                break;
            case 263: // WR Silent Line Teleport Reversed
                EV_SilentLineTeleport(line, side, thing, true);
                break;
            case 265: // WR Silent Line Teleport Reversed, monsters only
                if (thing.player == null) {
                    EV_SilentLineTeleport(line, side, thing, true);
                }
                break;
            case 267: // WR Silent Line Teleport, monsters only
                if (thing.player == null) {
                    EV_SilentLineTeleport(line, side, thing, false);
                }
                break;
            case 269: // WR Silent Teleport, monsters only
                if (thing.player == null) {
                    EV_SilentTeleport(line, side, thing);
                }
                break;

            default:
                break; // recognized range, no walkover action for this number
        }
        return true;
    }

    /**
     * USE lane for the fixed extended types (S1/SR switch table per Boom
     * p_switch.c P_UseSpecialLine). Returns true when the special was recognized.
     */
    default boolean useBoomFixed(mobj_t thing, line_t line, boolean side) {
        final int sp = line.special & 0xFFFF;
        if (!isBoomFixed(sp)) {
            return false;
        }
        if (boomFixedPassive(sp)) {
            return true;
        }
        if (side) {
            return true; // jff 6/1/98 back-side use never fires the extended types
        }
        if (thing.player == null) {
            // monsters never open secret lines...
            if ((line.flags & line_t.ML_SECRET) != 0) {
                return true;
            }
            // ...and may only use the teleporter switches (jff 3/5/98)
            switch (sp) {
                case 174: case 195: case 209: case 210:
                    break;
                default:
                    return true;
            }
        }
        if (!boomFixedTagOk(line, sp)) {
            return true;
        }

        switch (sp) {
            // ------------------- Extended switches, S1 ------------------------
            case 158: // S1 Floor Raise to Shortest Lower Texture
                if (DoFloor(line, floor_e.raiseToTexture)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 159: // S1 Floor Lower to Lowest and Change
                if (DoFloor(line, floor_e.lowerAndChange)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 160: // S1 Floor Raise 24 and Change
                if (DoFloor(line, floor_e.raiseFloor24AndChange)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 161: // S1 Floor Raise 24
                if (DoFloor(line, floor_e.raiseFloor24)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 162: // S1 Plat Perpetual Raise
                if (DoPlat(line, plattype_e.perpetualRaise, 0)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 163: // S1 Plat Stop
                StopPlat(line);
                getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 164: // S1 Ceiling Fast Crusher
                if (DoCeiling(line, ceiling_e.fastCrushAndRaise)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 165: // S1 Ceiling Silent Crusher
                if (DoCeiling(line, ceiling_e.silentCrushAndRaise)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 166: // S1 Raise Ceiling, Lower Floor (short-circuit per Boom)
                if (DoCeiling(line, ceiling_e.raiseToHighest)
                    || DoFloor(line, floor_e.lowerFloorToLowest)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 167: // S1 Ceiling Lower and Crush
                if (DoCeiling(line, ceiling_e.lowerAndCrush)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 168: // S1 Ceiling Crusher Stop
                if (CeilingCrushStop(line) != 0) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 169: // S1 Lights to Brightest Near
                LightTurnOn(line, 0);
                getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 170: // S1 Lights to 35
                LightTurnOn(line, 35);
                getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 171: // S1 Lights On Full
                LightTurnOn(line, 255);
                getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 172: // S1 Start Lights Strobing
                StartLightStrobing(line);
                getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 173: // S1 Lights to Dimmest Near
                TurnTagLightsOff(line);
                getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 174: // S1 Teleport
                if (Teleport(line, 0, thing) != 0) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 175: // S1 Close Door, Open in 30 secs
                if (DoDoor(line, vldoor_e.close30ThenOpen)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 189: // S1 Change Texture/Type Only (trigger model)
                if (EV_DoChange(line, BOOM_CHG_TRIG)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 203: // S1 Ceiling Lower to Lowest Ceiling
                if (DoCeiling(line, ceiling_e.lowerToLowest)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 204: // S1 Ceiling Lower to Highest Floor
                if (DoCeiling(line, ceiling_e.lowerToMaxFloor)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 209: // S1 Silent Teleport (killough 1/31/98)
                if (EV_SilentTeleport(line, 0, thing)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 221: // S1 Floor Lower to Next Lower Neighbor
                if (DoFloor(line, floor_e.lowerFloorToNearest)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 229: // S1 Elevator Raise to Next Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_UP)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 233: // S1 Elevator Lower to Next Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_DOWN)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 237: // S1 Elevator to Current Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_CURRENT)) getSwitches().ChangeSwitchTexture(line, false);
                break;
            case 241: // S1 Change Texture/Type Only (numeric model)
                if (EV_DoChange(line, BOOM_CHG_NUM)) getSwitches().ChangeSwitchTexture(line, false);
                break;

            // ------------------- Extended switches, SR ------------------------
            case 176: // SR Floor Raise to Shortest Lower Texture
                if (DoFloor(line, floor_e.raiseToTexture)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 177: // SR Floor Lower to Lowest and Change
                if (DoFloor(line, floor_e.lowerAndChange)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 178: // SR Floor Raise 512
                if (DoFloor(line, floor_e.raiseFloor512)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 179: // SR Floor Raise 24 and Change
                if (DoFloor(line, floor_e.raiseFloor24AndChange)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 180: // SR Floor Raise 24
                if (DoFloor(line, floor_e.raiseFloor24)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 181: // SR Plat Perpetual Raise (unconditional texture per Boom)
                DoPlat(line, plattype_e.perpetualRaise, 0);
                getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 182: // SR Plat Stop
                StopPlat(line);
                getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 183: // SR Ceiling Fast Crusher
                if (DoCeiling(line, ceiling_e.fastCrushAndRaise)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 184: // SR Ceiling Crusher
                if (DoCeiling(line, ceiling_e.crushAndRaise)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 185: // SR Ceiling Silent Crusher
                if (DoCeiling(line, ceiling_e.silentCrushAndRaise)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 186: // SR Raise Ceiling, Lower Floor (short-circuit per Boom)
                if (DoCeiling(line, ceiling_e.raiseToHighest)
                    || DoFloor(line, floor_e.lowerFloorToLowest)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 187: // SR Ceiling Lower and Crush
                if (DoCeiling(line, ceiling_e.lowerAndCrush)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 188: // SR Ceiling Crusher Stop
                if (CeilingCrushStop(line) != 0) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 190: // SR Change Texture/Type Only (trigger model)
                if (EV_DoChange(line, BOOM_CHG_TRIG)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 191: // SR Lower Pillar, Raise Donut
                if (DoDonut(line)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 192: // SR Lights to Brightest Near
                LightTurnOn(line, 0);
                getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 193: // SR Start Lights Strobing
                StartLightStrobing(line);
                getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 194: // SR Lights to Dimmest Near
                TurnTagLightsOff(line);
                getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 195: // SR Teleport
                if (Teleport(line, 0, thing) != 0) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 196: // SR Close Door, Open in 30 secs
                if (DoDoor(line, vldoor_e.close30ThenOpen)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 205: // SR Ceiling Lower to Lowest Ceiling
                if (DoCeiling(line, ceiling_e.lowerToLowest)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 206: // SR Ceiling Lower to Highest Floor
                if (DoCeiling(line, ceiling_e.lowerToMaxFloor)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 210: // SR Silent Teleport
                if (EV_SilentTeleport(line, 0, thing)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 211: // SR Instant Toggle Floor (jff 3/14/98)
                if (DoPlat(line, plattype_e.toggleUpDn, 0)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 222: // SR Floor Lower to Next Lower Neighbor
                if (DoFloor(line, floor_e.lowerFloorToNearest)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 230: // SR Elevator Raise to Next Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_UP)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 234: // SR Elevator Lower to Next Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_DOWN)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 238: // SR Elevator to Current Floor
                if (EV_DoElevator(line, elevator_t.ELEVATE_CURRENT)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 258: // SR Build Stairs, step 8
                if (BuildStairs(line, stair_e.build8)) getSwitches().ChangeSwitchTexture(line, true);
                break;
            case 259: // SR Build Stairs Turbo, step 16
                if (BuildStairs(line, stair_e.turbo16)) getSwitches().ChangeSwitchTexture(line, true);
                break;

            default:
                break; // recognized range, no use action for this number
        }
        return true;
    }

    /**
     * GUN (impact) lane for the fixed extended types: only the Boom G1 exits
     * (197/198, jff 1/30/98). Returns true when the special was recognized.
     */
    default boolean shootBoomFixed(mobj_t thing, line_t line) {
        final int sp = line.special & 0xFFFF;
        if (!isBoomFixed(sp)) {
            return false;
        }
        if (boomFixedPassive(sp)) {
            return true;
        }
        if (thing.player == null) {
            return true; // the extended gun types are player-only
        }
        if (!boomFixedTagOk(line, sp)) {
            return true;
        }
        switch (sp) {
            case 197: // G1 Exit Level
                getSwitches().ChangeSwitchTexture(line, false);
                DOOM().ExitLevel();
                break;
            case 198: // G1 Secret Exit Level
                getSwitches().ChangeSwitchTexture(line, false);
                DOOM().SecretExitLevel();
                break;
            default:
                break; // recognized range, no gun action for this number
        }
        return true;
    }

    // ------------------------------------------------------------------ EV_DoChange

    int BOOM_CHG_TRIG = 0, BOOM_CHG_NUM = 1;

    /**
     * Boom EV_DoChange (jff 3/15/98): change a tagged sector's floor texture and
     * special in place, no motion. Trigger model copies from the line's front
     * sector; numeric model from an adjacent sector at the same floor height.
     * (Boom also copies oldspecial — vanilla Mocha has no such field.)
     */
    default boolean EV_DoChange(line_t line, int changetype) {
        boolean rtn = false;
        int secnum = -1;
        while ((secnum = FindSectorFromLineTag(line, secnum)) >= 0) {
            final sector_t sec = levelLoader().sectors[secnum];
            rtn = true;
            switch (changetype) {
                case BOOM_CHG_TRIG -> {
                    sec.floorpic = line.frontsector.floorpic;
                    sec.special = line.frontsector.special;
                }
                case BOOM_CHG_NUM -> {
                    final sector_t model = boomFindModelFloorSector(sec.floorheight, secnum);
                    if (model != null) { // if no model, no change
                        sec.floorpic = model.floorpic;
                        sec.special = model.special;
                    }
                }
                default -> { }
            }
        }
        return rtn;
    }

    // ------------------------------------------------------------------ ELEVATORS
    // Boom p_floor.c EV_DoElevator / T_MoveElevator (jff 2/22/98): an elevator
    // moves a sector's floor AND ceiling together, keeping their gap constant.
    // Types 227-238 (W1/WR/S1/SR x next-highest/next-lowest/current).

    int BOOM_ELEVATORSPEED = MAPFRACUNIT * 4;

    /** Boom EV_DoElevator: spawn elevator movers on all tagged sectors. */
    default boolean EV_DoElevator(line_t line, int elevtype) {
        boolean rtn = false;
        int secnum = -1;
        while ((secnum = FindSectorFromLineTag(line, secnum)) >= 0) {
            final sector_t sec = levelLoader().sectors[secnum];
            // if floor or ceiling is already activated, skip it (jff 2/22/98)
            if (sec.specialdata != null) {
                continue;
            }
            rtn = true;
            final elevator_t elevator = new elevator_t();
            sec.specialdata = elevator;
            elevator.thinkerFunction = ActiveStates.T_MoveElevator;
            AddThinker(elevator);
            elevator.type = elevtype;
            elevator.sector = sec;
            elevator.speed = BOOM_ELEVATORSPEED;

            switch (elevtype) {
                case elevator_t.ELEVATE_DOWN -> {
                    elevator.direction = -1;
                    elevator.floordestheight = boomFindNextLowestFloor(sec, sec.floorheight);
                }
                case elevator_t.ELEVATE_UP -> {
                    elevator.direction = 1;
                    elevator.floordestheight = sec.FindNextHighestFloor(sec.floorheight);
                }
                case elevator_t.ELEVATE_CURRENT -> {
                    // to the floor height of the triggering line's front sector
                    elevator.floordestheight = line.frontsector.floorheight;
                    elevator.direction = elevator.floordestheight > sec.floorheight ? 1 : -1;
                }
                default -> { }
            }
            elevator.ceilingdestheight =
                elevator.floordestheight + sec.ceilingheight - sec.floorheight;
        }
        return rtn;
    }

    /**
     * The per-tic elevator mover (ActiveStates.T_MoveElevator). Going down the
     * ceiling leads, going up the floor leads (jff 4/7/98 — Boom's comments are
     * famously swapped here; this is what the code does), so the gap never
     * squeezes its riders. Completion checks the LEADING plane's result only,
     * exactly like Boom.
     */
    default void T_MoveElevator(thinker_t th) {
        final elevator_t elevator = (elevator_t) th;
        final result_e res;

        if (elevator.direction < 0) { // moving down: ceiling first, floor follows
            res = MovePlane(elevator.sector, elevator.speed,
                elevator.ceilingdestheight, false, 1, elevator.direction);
            if (res == result_e.ok || res == result_e.pastdest) {
                MovePlane(elevator.sector, elevator.speed,
                    elevator.floordestheight, false, 0, elevator.direction);
            }
        } else { // moving up: floor first, ceiling follows
            res = MovePlane(elevator.sector, elevator.speed,
                elevator.floordestheight, false, 0, elevator.direction);
            if (res == result_e.ok || res == result_e.pastdest) {
                MovePlane(elevator.sector, elevator.speed,
                    elevator.ceilingdestheight, false, 1, elevator.direction);
            }
        }

        // make floor move sound
        if ((LevelTime() & 7) == 0) {
            StartSound(elevator.sector.soundorg, sounds.sfxenum_t.sfx_stnmov);
        }

        if (res == result_e.pastdest) { // destination height reached
            elevator.sector.specialdata = null;
            RemoveThinker(elevator);
            StartSound(elevator.sector.soundorg, sounds.sfxenum_t.sfx_pstop);
        }
    }

    // ------------------------------------------------------------ SILENT TELEPORTS
    // Boom p_telept.c (killough 1/31/98 + jff 4/14/98): teleports with no fog, no
    // sound, no freeze, preserving orientation and momentum — the rooms-over-rooms
    // workhorse. Types 207-210 (thing exits) and 243/244/262-267 (line-to-line).

    /** Maximum fixed_t units a line teleport exit may fudge to stay on the right side. */
    int BOOM_TELEPORT_FUDGE = 10;

    /**
     * Boom EV_SilentTeleport: teleport to a MT_TELEPORTMAN in the tagged sector,
     * keeping height above floor and rotating momentum by the angle between the
     * crossed line and the exit thing.
     */
    default boolean EV_SilentTeleport(line_t line, int side, mobj_t thing) {
        // don't teleport missiles; don't teleport if hit back of line,
        // so you can get out of the teleporter
        if (side != 0 || (thing.flags & mobj_t.MF_MISSILE) != 0) {
            return false;
        }

        for (int i = -1; (i = FindSectorFromLineTag(line, i)) >= 0;) {
            for (thinker_t th = getThinkerCap().next; th != getThinkerCap(); th = th.next) {
                if (th.thinkerFunction != ActiveStates.P_MobjThinker) {
                    continue;
                }
                final mobj_t m = (mobj_t) th;
                if (m.type != mobjtype_t.MT_TELEPORTMAN || m.subsector.sector.id != i) {
                    continue;
                }

                // height of thing above ground, in case of mid-air teleports
                final int z = thing.z - thing.floorz;

                // angle between the exit thing and the source linedef, rotated 90
                // degrees: walking perpendicularly across the teleporter exits in
                // the direction the exit thing faces
                final long angle = (sceneRenderer().PointToAngle2(0, 0, line.dx, line.dy)
                    - m.angle + Tables.ANG90) & Tables.BITS32;
                final int fine = Tables.toBAMIndex(angle);
                final int s = Tables.finesine[fine];
                final int c = Tables.finecosine[fine];

                // momentum of thing crossing the teleporter linedef
                final int momx = thing.momx;
                final int momy = thing.momy;

                final player_t player = thing.player;

                // attempt to teleport, aborting if blocked
                if (!TeleportMove(thing, m.x, m.y)) {
                    return false;
                }

                // rotate thing according to difference in angles
                thing.angle = Tables.addAngles(thing.angle, angle);

                // adjust z position to be same height above ground as before
                thing.z = z + thing.floorz;

                // rotate thing's momentum to come out of exit just like it entered
                thing.momx = FixedMul(momx, c) - FixedMul(momy, s);
                thing.momy = FixedMul(momy, c) + FixedMul(momx, s);

                // adjust the player's view in case of a height change; voodoo
                // dolls are excluded by making sure player.mo == thing
                if (player != null && player.mo == thing) {
                    final int deltaviewheight = player.deltaviewheight;
                    player.deltaviewheight = 0;
                    player.CalcHeight();
                    player.deltaviewheight = deltaviewheight;
                    if (player == DOOM().players[0]) {
                        mochadoom.Engine.PLAYER_TELEPORT_COUNT++; // MC side must follow
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Boom EV_SilentLineTeleport: teleport across a pair of tagged linedefs,
     * interpolating the position along the exit line and rotating orientation
     * and momentum by the angle between the lines (reversed variants rotate 180
     * and flip the position across the exit line).
     */
    default boolean EV_SilentLineTeleport(line_t line, int side, mobj_t thing, boolean reverse) {
        if (side != 0 || (thing.flags & mobj_t.MF_MISSILE) != 0) {
            return false;
        }

        for (int i = -1; (i = boomFindLineFromLineTag(line, i)) >= 0;) {
            final line_t l = levelLoader().lines[i];
            if (l == line || l.backsector == null) {
                continue;
            }

            // the thing's position along the source linedef
            int pos = Math.abs(line.dx) > Math.abs(line.dy)
                ? FixedDiv(thing.x - line.v1.x, line.dx)
                : FixedDiv(thing.y - line.v1.y, line.dy);

            // angle between the two linedefs for rotating orientation and
            // momentum; rotate 180 degrees and flip the position across the
            // exit linedef if reversed
            long angle;
            if (reverse) {
                pos = FRACUNIT - pos;
                angle = 0;
            } else {
                angle = Tables.ANG180;
            }
            angle = (angle
                + sceneRenderer().PointToAngle2(0, 0, l.dx, l.dy)
                - sceneRenderer().PointToAngle2(0, 0, line.dx, line.dy)) & Tables.BITS32;

            // interpolate position across the exit linedef
            int x = l.v2.x - FixedMul(pos, l.dx);
            int y = l.v2.y - FixedMul(pos, l.dy);

            final int fine = Tables.toBAMIndex(angle);
            final int s = Tables.finesine[fine];
            final int c = Tables.finecosine[fine];

            // whether this is a player; voodoo dolls are excluded by making
            // sure thing.player.mo == thing
            final player_t player =
                thing.player != null && thing.player.mo == thing ? thing.player : null;

            // whether walking towards first side of exit linedef steps down
            final int stepdown =
                l.frontsector.floorheight < l.backsector.floorheight ? 1 : 0;

            // height of thing above ground
            final int z = thing.z - thing.floorz;

            // side to exit the linedef on positionally (see Boom p_telept.c for
            // the full oscillation/stuck-in-wall rationale)
            final boolean exitside = reverse || (player != null && stepdown != 0);

            // make sure we are on the correct side of the exit linedef
            int fudge = BOOM_TELEPORT_FUDGE;
            while (l.PointOnLineSide(x, y) != exitside && --fudge >= 0) {
                if (Math.abs(l.dx) > Math.abs(l.dy)) {
                    y -= (l.dx < 0) != exitside ? -1 : 1;
                } else {
                    x += (l.dy < 0) != exitside ? -1 : 1;
                }
            }

            // attempt to teleport, aborting if blocked
            if (!TeleportMove(thing, x, y)) {
                return false;
            }

            // adjust z position to be same height above ground as before;
            // ground level at the exit is measured as the higher of the two
            // floor heights at the exit linedef
            thing.z = z + levelLoader().sides[l.sidenum[stepdown]].sector.floorheight;

            // rotate thing's orientation according to difference in linedef angles
            thing.angle = Tables.addAngles(thing.angle, angle);

            // rotate thing's momentum to come out of exit just like it entered
            final int momx = thing.momx;
            final int momy = thing.momy;
            thing.momx = FixedMul(momx, c) - FixedMul(momy, s);
            thing.momy = FixedMul(momy, c) + FixedMul(momx, s);

            // adjust a player's view in case of a height change
            if (player != null) {
                final int deltaviewheight = player.deltaviewheight;
                player.deltaviewheight = 0;
                player.CalcHeight();
                player.deltaviewheight = deltaviewheight;
                if (player == DOOM().players[0]) {
                    mochadoom.Engine.PLAYER_TELEPORT_COUNT++; // MC side must follow
                }
            }
            return true;
        }
        return false;
    }

    /** Boom P_FindLineFromLineTag (linear-scan form, same visitation order as
     * killough's hash chains): next line index sharing the line's tag. */
    default int boomFindLineFromLineTag(line_t line, int start) {
        final line_t[] lines = levelLoader().lines;
        for (int i = start + 1; i < levelLoader().numlines; i++) {
            if (lines[i].tag == line.tag) {
                return i;
            }
        }
        return -1;
    }
}
