package deh;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import p.mobj_t;

/**
 * Latte Doom patch: DEHACKED/BEX static data.
 *
 * MBF's table extensions (states 967..1075, mobjinfo 137..143, sprites 138..143)
 * mechanically extracted from MBF-family source (Nugget Doom's info.c, which keeps
 * killough's canonical numbering), plus the DEH field-name and BEX mnemonic maps.
 * MBF-class wads (Eviternity et al.) assume these defaults underneath their patches:
 * e.g. Eviternity's "Nightmare Demon" is the MBF dog slot with stat overrides only.
 */
public final class DehTables {

    private DehTables() {
    }

    /** First MBF extension state (== vanilla NUMSTATES). */
    public static final int VANILLA_NUMSTATES = 967;
    /** One past the last MBF state (S_MUSHROOM = 1075). */
    public static final int MBF_NUMSTATES = 1076;
    public static final int VANILLA_NUMSPRITES = 138;
    public static final int VANILLA_NUMMOBJTYPES = 137;
    public static final int MBF_NUMMOBJTYPES = 144;
    public static final int VANILLA_NUMSFX = 109;

    /** MBF sprite names 138..143. TNT1 is the canonical invisible sprite. */
    public static final String[] MBF_SPRITES = { "TNT1", "DOGS", "PLS1", "PLS2", "BON3", "BON4" };

    /** Action names for MBF_STATES actionId (0 = none). */
    static final String[] MBF_ACTIONS = { null, "A_BFGsound", "A_BetaSkullAttack", "A_Chase", "A_Detonate", "A_Die",
        "A_FaceTarget", "A_Fall", "A_FireOldBFG", "A_Light0", "A_Look", "A_Mushroom", "A_Pain", "A_ReFire",
        "A_SargAttack", "A_Scream", "A_Stop" };

    /** MBF extension states 967..1075: {sprite, frame, tics, nextstate, misc1, misc2, actionId}. */
    static final int[][] MBF_STATES = {
        {138,0,-1,967,0,0,0}, {22,32768,1000,968,0,0,5}, {22,32769,4,970,0,0,15}, {22,32770,6,971,0,0,4}, {22,32771,10,0,0,0,0}, {139,0,10,973,0,0,10},
        {139,1,10,972,0,0,10}, {139,0,2,975,0,0,3}, {139,0,2,976,0,0,3}, {139,1,2,977,0,0,3}, {139,1,2,978,0,0,3}, {139,2,2,979,0,0,3},
        {139,2,2,980,0,0,3}, {139,3,2,981,0,0,3}, {139,3,2,974,0,0,3}, {139,4,8,983,0,0,6}, {139,5,8,984,0,0,6}, {139,6,8,974,0,0,14},
        {139,7,2,986,0,0,0}, {139,7,2,974,0,0,12}, {139,8,8,988,0,0,0}, {139,9,8,989,0,0,15}, {139,10,4,990,0,0,0}, {139,11,4,991,0,0,7},
        {139,12,4,992,0,0,0}, {139,13,-1,0,0,0,0}, {139,13,5,994,0,0,0}, {139,12,5,995,0,0,0}, {139,11,5,996,0,0,0}, {139,10,5,997,0,0,0},
        {139,9,5,998,0,0,0}, {139,8,5,974,0,0,0}, {14,0,10,1000,0,0,1}, {14,1,1,1001,0,0,8}, {14,1,1,1002,0,0,8}, {14,1,1,1003,0,0,8},
        {14,1,1,1004,0,0,8}, {14,1,1,1005,0,0,8}, {14,1,1,1006,0,0,8}, {14,1,1,1007,0,0,8}, {14,1,1,1008,0,0,8}, {14,1,1,1009,0,0,8},
        {14,1,1,1010,0,0,8}, {14,1,1,1011,0,0,8}, {14,1,1,1012,0,0,8}, {14,1,1,1013,0,0,8}, {14,1,1,1014,0,0,8}, {14,1,1,1015,0,0,8},
        {14,1,1,1016,0,0,8}, {14,1,1,1017,0,0,8}, {14,1,1,1018,0,0,8}, {14,1,1,1019,0,0,8}, {14,1,1,1020,0,0,8}, {14,1,1,1021,0,0,8},
        {14,1,1,1022,0,0,8}, {14,1,1,1023,0,0,8}, {14,1,1,1024,0,0,8}, {14,1,1,1025,0,0,8}, {14,1,1,1026,0,0,8}, {14,1,1,1027,0,0,8},
        {14,1,1,1028,0,0,8}, {14,1,1,1029,0,0,8}, {14,1,1,1030,0,0,8}, {14,1,1,1031,0,0,8}, {14,1,1,1032,0,0,8}, {14,1,1,1033,0,0,8},
        {14,1,1,1034,0,0,8}, {14,1,1,1035,0,0,8}, {14,1,1,1036,0,0,8}, {14,1,1,1037,0,0,8}, {14,1,1,1038,0,0,8}, {14,1,1,1039,0,0,8},
        {14,1,1,1040,0,0,8}, {14,1,0,1041,0,0,9}, {14,1,20,81,0,0,13}, {140,32768,6,1043,0,0,0}, {140,32769,6,1042,0,0,0}, {140,32770,4,1045,0,0,0},
        {140,32771,4,1046,0,0,0}, {140,32772,4,1047,0,0,0}, {140,32773,4,1048,0,0,0}, {140,32774,4,0,0,0,0}, {141,32768,4,1050,0,0,0}, {141,32769,4,1049,0,0,0},
        {141,32770,6,1052,0,0,0}, {141,32771,6,1053,0,0,0}, {141,32772,6,0,0,0,0}, {142,0,6,1054,0,0,0}, {143,0,6,1055,0,0,0}, {44,0,10,1056,0,0,10},
        {44,1,5,1058,0,0,3}, {44,2,5,1059,0,0,3}, {44,3,5,1060,0,0,3}, {44,0,5,1057,0,0,3}, {44,4,4,1062,0,0,6}, {44,5,5,1063,0,0,2},
        {44,5,4,1057,0,0,0}, {44,6,4,1065,0,0,0}, {44,7,2,1057,0,0,12}, {44,8,4,1057,0,0,0}, {44,9,5,1068,0,0,0}, {44,10,5,1069,0,0,0},
        {44,11,5,1070,0,0,0}, {44,12,5,1071,0,0,0}, {44,13,5,1072,0,0,15}, {44,14,5,1073,0,0,0}, {44,15,5,1074,0,0,7}, {44,16,5,1074,0,0,16},
        {22,32769,8,128,0,0,11}
    };

    /**
     * MBF extra mobjinfo 137..143 (MT_PUSH, MT_PULL, MT_DOGS, MT_PLASMA1, MT_PLASMA2,
     * MT_SCEPTRE, MT_BIBLE), fields in mobjinfo order minus flags:
     * doomednum, spawnstate, spawnhealth, seestate, seesound, reactiontime, attacksound,
     * painstate, painchance, painsound, meleestate, missilestate, deathstate, xdeathstate,
     * deathsound, speed, radius, height, mass, damage, activesound, raisestate.
     */
    static final int[][] MBF_MOBJINFO = {
        {5001, 967, 1000, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 10, 0, 0, 0},
        {5002, 967, 1000, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 10, 0, 0, 0},
        {888, 972, 500, 974, 109, 8, 110, 985, 180, 113, 982, 0, 987, 0, 112, 10, 786432, 1835008, 100, 0, 111, 993},
        {-1, 1042, 1000, 0, 8, 8, 0, 0, 0, 0, 0, 0, 1044, 0, 17, 1638400, 851968, 524288, 100, 4, 0, 0},
        {-1, 1049, 1000, 0, 8, 8, 0, 0, 0, 0, 0, 0, 1051, 0, 17, 1638400, 393216, 524288, 100, 4, 0, 0},
        {2016, 1054, 1000, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 655360, 1048576, 100, 0, 0, 0},
        {2017, 1055, 1000, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1310720, 655360, 100, 0, 0, 0},
    };
    static final String[] MBF_MOBJINFO_FLAGS = {
        "MF_NOBLOCKMAP",
        "MF_NOBLOCKMAP",
        "MF_SOLID|MF_SHOOTABLE|MF_COUNTKILL",
        "MF_NOBLOCKMAP|MF_MISSILE|MF_DROPOFF|MF_NOGRAVITY|MF_BOUNCES|MF_TRANSLUCENT",
        "MF_NOBLOCKMAP|MF_MISSILE|MF_DROPOFF|MF_NOGRAVITY|MF_BOUNCES|MF_TRANSLUCENT",
        "MF_SPECIAL|MF_COUNTITEM",
        "MF_SPECIAL|MF_COUNTITEM",
    };

    /**
     * MBF dog sounds 109..113 (sfx_dgsit/dgatk/dgact/dgdth/dgpain) have no slots in
     * mocha's sfxenum_t; mapped to their closest vanilla demon equivalents. A disclosed
     * fidelity compromise: the Nightmare Demon barks like a pinky until sounds grow slots.
     */
    static final String[] MBF_DOG_SFX_SUBST = { "sfx_sgtsit", "sfx_sgtatk", "sfx_dmact", "sfx_sgtdth", "sfx_dmpain" };

    /** BEX "Bits" mnemonics -&gt; mocha MF_* (vanilla set). */
    static final Map<String, Long> MNEMONIC_FLAGS = new HashMap<>();
    /** Port-only mnemonics we recognize and skip (MBF and friends). */
    static final java.util.Set<String> MNEMONIC_FLAGS_UNSUPPORTED = new java.util.HashSet<>(java.util.Arrays.asList(
        "TRANSLUCENT", "TOUCHY", "BOUNCES", "FRIEND", "FRIENDLY", "STEALTH", "COLORMASK",
        "UNUSED1", "UNUSED2", "UNUSED3", "UNUSED4", "TRANSLATION1", "TRANSLATION2"));

    private static void flag(String name, long bits) {
        MNEMONIC_FLAGS.put(name, bits);
    }

    static {
        flag("SPECIAL", mobj_t.MF_SPECIAL);
        flag("SOLID", mobj_t.MF_SOLID);
        flag("SHOOTABLE", mobj_t.MF_SHOOTABLE);
        flag("NOSECTOR", mobj_t.MF_NOSECTOR);
        flag("NOBLOCKMAP", mobj_t.MF_NOBLOCKMAP);
        flag("AMBUSH", mobj_t.MF_AMBUSH);
        flag("JUSTHIT", mobj_t.MF_JUSTHIT);
        flag("JUSTATTACKED", mobj_t.MF_JUSTATTACKED);
        flag("SPAWNCEILING", mobj_t.MF_SPAWNCEILING);
        flag("NOGRAVITY", mobj_t.MF_NOGRAVITY);
        flag("DROPOFF", mobj_t.MF_DROPOFF);
        flag("PICKUP", mobj_t.MF_PICKUP);
        flag("NOCLIP", mobj_t.MF_NOCLIP);
        flag("SLIDE", mobj_t.MF_SLIDE);
        flag("FLOAT", mobj_t.MF_FLOAT);
        flag("TELEPORT", mobj_t.MF_TELEPORT);
        flag("MISSILE", mobj_t.MF_MISSILE);
        flag("DROPPED", mobj_t.MF_DROPPED);
        flag("SHADOW", mobj_t.MF_SHADOW);
        flag("NOBLOOD", mobj_t.MF_NOBLOOD);
        flag("CORPSE", mobj_t.MF_CORPSE);
        flag("INFLOAT", mobj_t.MF_INFLOAT);
        flag("COUNTKILL", mobj_t.MF_COUNTKILL);
        flag("COUNTITEM", mobj_t.MF_COUNTITEM);
        flag("SKULLFLY", mobj_t.MF_SKULLFLY);
        flag("NOTDMATCH", mobj_t.MF_NOTDMATCH);
        flag("TRANSLATION", mobj_t.MF_TRANSLATION);
    }

    /**
     * MBF-only codepointers mocha has no implementation for: mapped to a safe no-op
     * with a one-time log. (BEX names, without the A_ prefix.)
     */
    static final java.util.Set<String> MBF_CODEPTRS_UNAVAILABLE = new java.util.HashSet<>(java.util.Arrays.asList(
        "MUSHROOM", "DIE", "SPAWN", "TURN", "FACE", "SCRATCH", "PLAYSOUND", "RANDOMJUMP",
        "LINEEFFECT", "BETASKULLATTACK", "DETONATE", "STOP", "FIREOLDBFG"));

    /** DEH Thing field name (lower case) -&gt; internal field id used by the applier. */
    static final Map<String, String> THING_FIELDS = new HashMap<>();

    static {
        THING_FIELDS.put("id #", "doomednum");
        THING_FIELDS.put("initial frame", "spawnstate");
        THING_FIELDS.put("hit points", "spawnhealth");
        THING_FIELDS.put("first moving frame", "seestate");
        THING_FIELDS.put("alert sound", "seesound");
        THING_FIELDS.put("reaction time", "reactiontime");
        THING_FIELDS.put("attack sound", "attacksound");
        THING_FIELDS.put("injury frame", "painstate");
        THING_FIELDS.put("pain chance", "painchance");
        THING_FIELDS.put("pain sound", "painsound");
        THING_FIELDS.put("close attack frame", "meleestate");
        THING_FIELDS.put("far attack frame", "missilestate");
        THING_FIELDS.put("death frame", "deathstate");
        THING_FIELDS.put("exploding frame", "xdeathstate");
        THING_FIELDS.put("death sound", "deathsound");
        THING_FIELDS.put("speed", "speed");
        THING_FIELDS.put("width", "radius");
        THING_FIELDS.put("height", "height");
        THING_FIELDS.put("mass", "mass");
        THING_FIELDS.put("missile damage", "damage");
        THING_FIELDS.put("action sound", "activesound");
        THING_FIELDS.put("bits", "flags");
        THING_FIELDS.put("respawn frame", "raisestate");
    }

    /** Thing fields from editors/other ports that carry no meaning here; skipped silently. */
    static final java.util.Set<String> THING_FIELDS_IGNORED = new java.util.HashSet<>(java.util.Arrays.asList(
        "name1", "plural1", "retro bits", "translucency", "infighting group", "projectile group",
        "splash group", "mbf21 bits", "rip sound", "fast speed", "melee range", "dropped item",
        "blood color", "gib health", "self damage factor", "id24 bits"));

    static String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
