package deh;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import data.info;
import data.mobjinfo_t;
import data.sounds.sfxenum_t;
import data.spritenum_t;
import data.state_t;
import defines.statenum_t;
import p.ActiveStates;
import rr.ISpriteManager;

/**
 * Latte Doom additive patch — DEHACKED/BEX runtime table state.
 *
 * Mocha types its state machine with Java enums (statenum_t/spritenum_t/mobjtype_t),
 * which cannot grow at runtime. MBF-class DEH patches reference indices past the
 * vanilla enums (states 967.., sprites 138.., things 137..). The scheme:
 *
 *  - the ARRAYS are the truth: info.states / info.mobjinfo are reallocated larger and
 *    filled with MBF's canonical defaults (DehTables);
 *  - enum-typed fields keep a best-effort enum (real one when in vanilla range, a
 *    neutral placeholder otherwise) while parallel int fields added to state_t /
 *    mobjinfo_t / mobj_t carry the true index; the state walker and the handful of
 *    consumer sites prefer the ints (all marked "Latte Doom additive patch -- DEH").
 *
 * Everything here is inert until a DEHACKED lump actually needs it.
 */
public final class DehState {

    private DehState() {
    }

    /** true once any DEH patch was applied (cheap gate for hook sites). */
    public static volatile boolean active = false;

    private static final statenum_t[] STATENUMS = statenum_t.values();
    private static final spritenum_t[] SPRITENUMS = spritenum_t.values();
    private static final sfxenum_t[] SFXNUMS = sfxenum_t.values();

    /** Working sprite-name table handed to InitSprites; grows past 138 when extended. */
    private static String[] sprnames = null;

    /** Original state actions, for vanilla Pointer sections (captured before mutation). */
    private static ActiveStates[] originalActions = null;

    private static final Set<String> loggedOnce = new HashSet<>();

    // ------------------------------------------------------------- pristine restore
    // Latte Doom patch: every table below is a JVM-lifetime static that apply() mutates
    // in place. A new engine boot re-applies its own WAD set's patches, but nothing ever
    // un-applied the previous set's — load a smoothing patch, then load plain TNT, and
    // TNT ran on the smoothed tables without the patch's art. The first restore call
    // captures the untouched tables; every later call rebuilds them from that capture.

    private static state_t[] pristineStates;
    private static mobjinfo_t[] pristineMobjinfo;
    private static doom.weaponinfo_t[] pristineWeapons;
    private static int[] pristineMaxammo;
    private static int[] pristineClipammo;

    private static state_t copyState(state_t o) {
        final state_t c = new state_t(o.sprite, o.frame, o.tics, o.action, o.nextstate,
            o.misc1, o.misc2);
        c.id = o.id;
        c.dehnext = o.dehnext;
        c.dehsprite = o.dehsprite;
        return c;
    }

    private static mobjinfo_t copyInfo(mobjinfo_t o) {
        final mobjinfo_t c = new mobjinfo_t(o.doomednum, o.spawnstate, o.spawnhealth,
            o.seestate, o.seesound, o.reactiontime, o.attacksound, o.painstate,
            o.painchance, o.painsound, o.meleestate, o.missilestate, o.deathstate,
            o.xdeathstate, o.deathsound, o.speed, o.radius, o.height, o.mass, o.damage,
            o.activesound, o.flags, o.raisestate);
        c.dehspawnstate = o.dehspawnstate;
        c.dehseestate = o.dehseestate;
        c.dehpainstate = o.dehpainstate;
        c.dehmeleestate = o.dehmeleestate;
        c.dehmissilestate = o.dehmissilestate;
        c.dehdeathstate = o.dehdeathstate;
        c.dehxdeathstate = o.dehxdeathstate;
        c.dehraisestate = o.dehraisestate;
        return c;
    }

    private static doom.weaponinfo_t copyWeapon(doom.weaponinfo_t o) {
        return new doom.weaponinfo_t(o.ammo, o.upstate, o.downstate, o.readystate,
            o.atkstate, o.flashstate);
    }

    /** Called at every boot before the WAD set's patches apply: first call captures the
     * untouched tables, every later call puts them back. */
    public static synchronized void restoreVanilla() {
        if (pristineStates == null) {
            pristineStates = new state_t[info.states.length];
            for (int i = 0; i < info.states.length; i++) {
                pristineStates[i] = copyState(info.states[i]);
            }
            pristineMobjinfo = new mobjinfo_t[info.mobjinfo.length];
            for (int i = 0; i < info.mobjinfo.length; i++) {
                pristineMobjinfo[i] = copyInfo(info.mobjinfo[i]);
            }
            pristineWeapons = new doom.weaponinfo_t[doom.items.weaponinfo.length];
            for (int i = 0; i < doom.items.weaponinfo.length; i++) {
                pristineWeapons[i] = copyWeapon(doom.items.weaponinfo[i]);
            }
            pristineMaxammo = Arrays.copyOf(doom.DoomStatus.maxammo,
                doom.DoomStatus.maxammo.length);
            pristineClipammo = Arrays.copyOf(doom.player_t.clipammo,
                doom.player_t.clipammo.length);
            return;
        }
        final state_t[] ns = new state_t[pristineStates.length];
        for (int i = 0; i < ns.length; i++) {
            ns[i] = copyState(pristineStates[i]);
        }
        info.states = ns;
        final mobjinfo_t[] nm = new mobjinfo_t[pristineMobjinfo.length];
        for (int i = 0; i < nm.length; i++) {
            nm[i] = copyInfo(pristineMobjinfo[i]);
        }
        info.mobjinfo = nm;
        for (int i = 0; i < doom.items.weaponinfo.length && i < pristineWeapons.length; i++) {
            final doom.weaponinfo_t w = doom.items.weaponinfo[i];
            final doom.weaponinfo_t o = pristineWeapons[i];
            w.ammo = o.ammo;
            w.upstate = o.upstate;
            w.downstate = o.downstate;
            w.readystate = o.readystate;
            w.atkstate = o.atkstate;
            w.flashstate = o.flashstate;
        }
        System.arraycopy(pristineMaxammo, 0, doom.DoomStatus.maxammo, 0,
            pristineMaxammo.length);
        System.arraycopy(pristineClipammo, 0, doom.player_t.clipammo, 0,
            pristineClipammo.length);
        sprnames = null;          // back to the built-in name table
        originalActions = null;   // vanilla Pointer sections re-capture freshly
        active = false;
    }

    /** One-time log, keyed by message. */
    public static void logOnce(String msg) {
        if (loggedOnce.add(msg)) {
            System.out.println("[lattedoom-deh] " + msg);
        }
    }

    // ------------------------------------------------------------ states
    /** Grows info.states to cover index maxState, installing MBF defaults for 967..1075. */
    public static void ensureStates(int maxState) {
        if (maxState < info.states.length) {
            captureOriginalActions();
            return;
        }
        final int target = Math.max(maxState + 1, DehTables.MBF_NUMSTATES);
        final int oldLen = info.states.length;
        final state_t[] ns = Arrays.copyOf(info.states, target);
        for (int i = oldLen; i < target; i++) {
            ns[i] = makeExtensionState(i);
        }
        info.states = ns;
        ensureSprnames(DehTables.VANILLA_NUMSPRITES + DehTables.MBF_SPRITES.length - 1);
        active = true;
        captureOriginalActions();
        logOnce("state table extended to " + target + " entries (MBF base 967..1075 installed)");
    }

    private static state_t makeExtensionState(int i) {
        final state_t s = new state_t();
        s.id = i;
        final int mbf = i - DehTables.VANILLA_NUMSTATES;
        if (mbf >= 0 && mbf < DehTables.MBF_STATES.length) {
            final int[] row = DehTables.MBF_STATES[mbf];
            setStateSprite(s, row[0]);
            s.frame = row[1];
            s.tics = row[2];
            setStateNext(s, row[3]);
            s.misc1 = row[4];
            s.misc2 = row[5];
            s.action = resolveMbfAction(DehTables.MBF_ACTIONS[row[6]]);
        } else {
            // DEHEXTRA-style blank beyond MBF: invisible, inert, self-looping
            setStateSprite(s, DehTables.VANILLA_NUMSPRITES); // TNT1
            s.frame = 0;
            s.tics = -1;
            setStateNext(s, i);
            s.action = ActiveStates.NOP;
        }
        return s;
    }

    /** Sets both the enum (best effort) and the int mirror on a state's sprite. */
    public static void setStateSprite(state_t s, int sprite) {
        s.sprite = sprite >= 0 && sprite < DehTables.VANILLA_NUMSPRITES
            ? SPRITENUMS[sprite] : spritenum_t.NUMSPRITES;
        s.dehsprite = sprite;
    }

    /** Sets both the enum (best effort) and the int mirror on a state's nextstate. */
    public static void setStateNext(state_t s, int next) {
        s.nextstate = next >= 0 && next < DehTables.VANILLA_NUMSTATES
            ? STATENUMS[next] : statenum_t.S_NULL;
        s.dehnext = next;
    }

    private static ActiveStates resolveMbfAction(String name) {
        if (name == null) {
            return ActiveStates.NOP;
        }
        try {
            return ActiveStates.valueOf(name);
        } catch (IllegalArgumentException e) {
            logOnce("MBF codepointer " + name + " not available in this engine -- no-op");
            return ActiveStates.NOP;
        }
    }

    private static void captureOriginalActions() {
        if (originalActions == null || originalActions.length < info.states.length) {
            final int from = originalActions == null ? 0 : originalActions.length;
            originalActions = originalActions == null
                ? new ActiveStates[info.states.length]
                : Arrays.copyOf(originalActions, info.states.length);
            for (int i = from; i < info.states.length; i++) {
                originalActions[i] = info.states[i].action == null ? ActiveStates.NOP : info.states[i].action;
            }
        }
    }

    /** The action a state had before any DEH mutation (vanilla Pointer sections). */
    public static ActiveStates originalActionOf(int state) {
        captureOriginalActions();
        return state >= 0 && state < originalActions.length ? originalActions[state] : ActiveStates.NOP;
    }

    // ------------------------------------------------------------ mobjinfo
    /** Grows info.mobjinfo to cover index maxType, installing MBF defaults for 137..143. */
    public static void ensureMobjinfo(int maxType) {
        if (maxType < info.mobjinfo.length) {
            return;
        }
        final int target = Math.max(maxType + 1, DehTables.MBF_NUMMOBJTYPES);
        final int oldLen = info.mobjinfo.length;
        ensureStates(DehTables.MBF_NUMSTATES - 1); // MBF mobjinfo references MBF states
        final mobjinfo_t[] nm = Arrays.copyOf(info.mobjinfo, target);
        for (int i = oldLen; i < target; i++) {
            nm[i] = makeExtensionMobjinfo(i);
        }
        info.mobjinfo = nm;
        active = true;
        logOnce("mobjinfo table extended to " + target + " entries (MBF things 137..143 installed)");
    }

    private static mobjinfo_t makeExtensionMobjinfo(int i) {
        final int mbf = i - DehTables.VANILLA_NUMMOBJTYPES;
        final mobjinfo_t m = new mobjinfo_t(-1, statenum_t.S_NULL, 1000, statenum_t.S_NULL,
            sfxenum_t.sfx_None, 8, sfxenum_t.sfx_None, statenum_t.S_NULL, 0, sfxenum_t.sfx_None,
            statenum_t.S_NULL, statenum_t.S_NULL, statenum_t.S_NULL, statenum_t.S_NULL,
            sfxenum_t.sfx_None, 0, 20 * 0x10000, 16 * 0x10000, 100, 0, sfxenum_t.sfx_None,
            0, statenum_t.S_NULL);
        if (mbf >= 0 && mbf < DehTables.MBF_MOBJINFO.length) {
            final int[] r = DehTables.MBF_MOBJINFO[mbf];
            m.doomednum = r[0];
            setInfoState(m, "spawnstate", r[1]);
            m.spawnhealth = r[2];
            setInfoState(m, "seestate", r[3]);
            m.seesound = mapSfx(r[4]);
            m.reactiontime = r[5];
            m.attacksound = mapSfx(r[6]);
            setInfoState(m, "painstate", r[7]);
            m.painchance = r[8];
            m.painsound = mapSfx(r[9]);
            setInfoState(m, "meleestate", r[10]);
            setInfoState(m, "missilestate", r[11]);
            setInfoState(m, "deathstate", r[12]);
            setInfoState(m, "xdeathstate", r[13]);
            m.deathsound = mapSfx(r[14]);
            m.speed = r[15];
            m.radius = r[16];
            m.height = r[17];
            m.mass = r[18];
            m.damage = r[19];
            m.activesound = mapSfx(r[20]);
            m.flags = parseMbfBaseFlags(DehTables.MBF_MOBJINFO_FLAGS[mbf]);
            setInfoState(m, "raisestate", r[21]);
        }
        return m;
    }

    private static long parseMbfBaseFlags(String expr) {
        long v = 0;
        for (final String tok : expr.split("\\|")) {
            final String name = tok.trim().replaceFirst("^MF_", "");
            final Long bits = DehTables.MNEMONIC_FLAGS.get(name);
            if (bits != null) {
                v |= bits;
            } else if (DehTables.MNEMONIC_FLAGS_UNSUPPORTED.contains(name)) {
                logOnce("mobj flag MF_" + name + " not supported -- dropped (MBF base table)");
            }
        }
        return v;
    }

    /** Sets an enum statenum field plus its int mirror on mobjinfo by field id. */
    public static void setInfoState(mobjinfo_t m, String field, int state) {
        final statenum_t en = state >= 0 && state < DehTables.VANILLA_NUMSTATES
            ? STATENUMS[state] : statenum_t.S_NULL;
        switch (field) {
            case "spawnstate": m.spawnstate = en; m.dehspawnstate = state; break;
            case "seestate": m.seestate = en; m.dehseestate = state; break;
            case "painstate": m.painstate = en; m.dehpainstate = state; break;
            case "meleestate": m.meleestate = en; m.dehmeleestate = state; break;
            case "missilestate": m.missilestate = en; m.dehmissilestate = state; break;
            case "deathstate": m.deathstate = en; m.dehdeathstate = state; break;
            case "xdeathstate": m.xdeathstate = en; m.dehxdeathstate = state; break;
            case "raisestate": m.raisestate = en; m.dehraisestate = state; break;
            default: throw new IllegalArgumentException(field);
        }
    }

    /**
     * Maps a DEH sound number onto mocha's sfxenum. MBF dog sounds (109..113) fall back
     * to vanilla demon equivalents (disclosed compromise); anything else out of range
     * goes silent with a one-time log.
     */
    public static sfxenum_t mapSfx(int id) {
        if (id >= 0 && id < DehTables.VANILLA_NUMSFX) {
            return SFXNUMS[id];
        }
        if (id >= 109 && id <= 113) {
            final String subst = DehTables.MBF_DOG_SFX_SUBST[id - 109];
            logOnce("MBF dog sound " + id + " mapped to vanilla " + subst + " (no slot in sfxenum)");
            return sfxenum_t.valueOf(subst);
        }
        logOnce("sound number " + id + " out of range -- muted");
        return sfxenum_t.sfx_None;
    }

    // ------------------------------------------------------------ sprites
    /** Grows the working sprite-name table to cover index maxSprite. */
    public static void ensureSprnames(int maxSprite) {
        if (sprnames == null) {
            sprnames = Arrays.copyOf(ISpriteManager.doomsprnames, ISpriteManager.doomsprnames.length);
        }
        if (maxSprite < sprnames.length) {
            return;
        }
        final int oldLen = sprnames.length;
        sprnames = Arrays.copyOf(sprnames, maxSprite + 1);
        for (int i = oldLen; i <= maxSprite; i++) {
            final int mbf = i - DehTables.VANILLA_NUMSPRITES;
            if (mbf >= 0 && mbf < DehTables.MBF_SPRITES.length) {
                sprnames[i] = DehTables.MBF_SPRITES[mbf];
            } else if (i >= 245 && i <= 344) {
                sprnames[i] = String.format("SP%02d", i - 245); // DEHEXTRA slots
            } else {
                sprnames[i] = "TNT1"; // harmless invisible placeholder
            }
        }
        active = true;
    }

    /**
     * The sprite-name table for InitSprites: vanilla list when no DEH is active,
     * the extended working copy otherwise. Called from P_Init (marked patch site).
     */
    public static String[] sprnames() {
        return sprnames == null ? ISpriteManager.doomsprnames : sprnames;
    }

    /** Sprite name for an index (extended-aware); null when unknown. */
    public static String spriteNameOf(int num) {
        final String[] tab = sprnames();
        return num >= 0 && num < tab.length ? tab[num] : null;
    }

    /** Renames a sprite (old-style DEH Text 4/4); true if a slot matched. */
    public static boolean renameSprite(String from, String to) {
        if (sprnames == null) {
            sprnames = Arrays.copyOf(ISpriteManager.doomsprnames, ISpriteManager.doomsprnames.length);
        }
        for (int i = 0; i < sprnames.length; i++) {
            if (sprnames[i].equalsIgnoreCase(from)) {
                sprnames[i] = to.toUpperCase();
                active = true;
                return true;
            }
        }
        return false;
    }
}
