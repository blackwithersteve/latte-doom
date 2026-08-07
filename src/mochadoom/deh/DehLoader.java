package deh;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import data.Defines;
import data.info;
import data.mobjinfo_t;
import data.sounds;
import data.state_t;
import defines.ammotype_t;
import defines.statenum_t;
import doom.DoomMain;
import doom.DoomStatus;
import doom.items;
import doom.player_t;
import p.ActiveStates;
import w.lumpinfo_t;

/**
 * Latte Doom additive patch — DEHACKED/BEX applier.
 *
 * Entry point is {@link #applyWadDehLumps}: called once at engine boot, after
 * W_Init loaded the wad set and before any table consumer runs (R_Init, P_Init,
 * level setup). Every lump named DEHACKED is parsed and applied in load order.
 * Defensive by contract: a broken patch logs and degrades, it never crashes boot.
 */
public final class DehLoader {

    private DehLoader() {
    }

    // application counters for the summary line
    private int things, frames, pointers, codeptrs, soundsN, ammoN, weaponsN, strings, pars, texts;
    private int skipped;
    private final List<String> skippedMisc = new ArrayList<>();

    /** Applies every DEHACKED lump in the loaded wads, in lump order. */
    public static void applyWadDehLumps(DoomMain<?, ?> DOOM) {
        int found = 0;
        for (int i = 0; i < DOOM.wadLoader.NumLumps(); i++) {
            final lumpinfo_t li = DOOM.wadLoader.GetLumpInfo(i);
            if (li == null || li.name == null || !"DEHACKED".equalsIgnoreCase(li.name.trim())) {
                continue;
            }
            found++;
            try {
                final byte[] raw = DOOM.wadLoader.CacheLumpNumAsRawBytes(i, Defines.PU_STATIC);
                final String text = new String(raw, StandardCharsets.ISO_8859_1);
                System.out.println("[lattedoom-deh] DEHACKED lump #" + found + " (" + raw.length + " bytes) -- applying");
                final DehPatch patch = DehParser.parse(text);
                new DehLoader().apply(patch, DOOM);
            } catch (Exception e) {
                System.err.println("[lattedoom-deh] DEHACKED lump failed to apply (continuing vanilla): " + e);
            }
        }
        if (found == 0) {
            return;
        }
    }

    /**
     * Parses and applies a single patch text. DOOM may be null (standalone harness):
     * the static tables are still patched for real; only the game-instance homes
     * (HU level names, par arrays) are skipped.
     */
    public static void applyText(String text, DoomMain<?, ?> DOOM) {
        new DehLoader().apply(DehParser.parse(text), DOOM);
    }

    // ---------------------------------------------------------------- apply
    void apply(DehPatch p, DoomMain<?, ?> DOOM) {
        for (final String problem : p.problems) {
            DehState.logOnce("parse: " + problem);
        }

        growTables(p);

        for (final Map.Entry<Integer, Map<String, String>> e : p.things.entrySet()) {
            applyThing(e.getKey(), e.getValue());
        }
        for (final Map.Entry<Integer, Map<String, String>> e : p.frames.entrySet()) {
            applyFrame(e.getKey(), e.getValue());
        }
        for (final Map.Entry<Integer, Integer> e : p.pointers.entrySet()) {
            applyPointer(e.getKey(), e.getValue());
        }
        for (final Map.Entry<Integer, String> e : p.codeptr.entrySet()) {
            applyCodeptr(e.getKey(), e.getValue());
        }
        for (final Map.Entry<Integer, Map<String, String>> e : p.sounds.entrySet()) {
            applySound(e.getKey(), e.getValue());
        }
        for (final Map.Entry<Integer, Map<String, String>> e : p.ammo.entrySet()) {
            applyAmmo(e.getKey(), e.getValue());
        }
        for (final Map.Entry<Integer, Map<String, String>> e : p.weapons.entrySet()) {
            applyWeapon(e.getKey(), e.getValue());
        }
        applyMisc(p.misc);
        for (final String[] t : p.texts) {
            applyText(t[0], t[1]);
        }
        for (final Map.Entry<String, String> e : p.strings.entrySet()) {
            applyBexString(e.getKey(), e.getValue(), DOOM);
        }
        for (final int[] par : p.pars) {
            applyPar(par, DOOM);
        }
        if (!p.cheats.isEmpty()) {
            DehState.logOnce("Cheat section parsed but not applied (" + p.cheats.size() + " fields)");
        }
        if (!skippedMisc.isEmpty()) {
            DehState.logOnce("Misc fields not runtime-patchable, skipped: " + String.join(", ", skippedMisc));
        }

        if (things + frames + pointers + codeptrs + soundsN + ammoN + weaponsN + strings + pars + texts > 0) {
            DehState.active = true;
        }
        System.out.println("[lattedoom-deh] applied: " + things + " things, " + frames + " frames, "
            + pointers + " pointers, " + codeptrs + " codeptrs, " + soundsN + " sounds, "
            + ammoN + " ammo, " + weaponsN + " weapons, " + texts + " texts, "
            + strings + " strings, " + pars + " pars"
            + (skipped > 0 ? " (" + skipped + " fields skipped)" : ""));
    }

    /** Pre-scan for the largest referenced indices so the tables can grow first. */
    private void growTables(DehPatch p) {
        int maxState = -1, maxThing = -1, maxSprite = -1;
        for (final int f : p.frames.keySet()) {
            maxState = Math.max(maxState, f);
        }
        for (final int f : p.codeptr.keySet()) {
            maxState = Math.max(maxState, f);
        }
        for (final Map.Entry<Integer, Integer> e : p.pointers.entrySet()) {
            maxState = Math.max(maxState, Math.max(e.getKey(), e.getValue()));
        }
        for (final Map<String, String> fields : p.frames.values()) {
            maxState = Math.max(maxState, intField(fields, "Next frame"));
            maxSprite = Math.max(maxSprite, intField(fields, "Sprite number"));
        }
        for (final Map.Entry<Integer, Map<String, String>> e : p.things.entrySet()) {
            maxThing = Math.max(maxThing, e.getKey() - 1);
            for (final Map.Entry<String, String> f : e.getValue().entrySet()) {
                final String id = DehTables.THING_FIELDS.get(DehTables.normalizeKey(f.getKey()));
                if (id != null && id.endsWith("state")) {
                    maxState = Math.max(maxState, (int) parseLong(f.getValue(), -1));
                }
            }
        }
        if (maxState >= DehTables.VANILLA_NUMSTATES) {
            DehState.ensureStates(maxState);
        }
        if (maxThing >= DehTables.VANILLA_NUMMOBJTYPES
            || maxState >= DehTables.VANILLA_NUMSTATES) {
            // MBF wads assume the MBF things exist (their doomednums live on maps)
            DehState.ensureMobjinfo(Math.max(maxThing, DehTables.MBF_NUMMOBJTYPES - 1));
        }
        if (maxSprite >= DehTables.VANILLA_NUMSPRITES) {
            DehState.ensureSprnames(maxSprite);
        }
    }

    private static int intField(Map<String, String> fields, String key) {
        for (final Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return (int) parseLong(e.getValue(), -1);
            }
        }
        return -1;
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------------------------------------------------------------- things
    private void applyThing(int dehNum, Map<String, String> fields) {
        final int idx = dehNum - 1; // DEH things are 1-based
        if (idx < 0 || idx >= info.mobjinfo.length) {
            DehState.logOnce("Thing " + dehNum + " out of range -- section skipped");
            skipped += fields.size();
            return;
        }
        final mobjinfo_t m = info.mobjinfo[idx];
        boolean any = false;
        for (final Map.Entry<String, String> e : fields.entrySet()) {
            final String key = DehTables.normalizeKey(e.getKey());
            final String val = e.getValue();
            final String id = DehTables.THING_FIELDS.get(key);
            if (id == null) {
                if (!DehTables.THING_FIELDS_IGNORED.contains(key)) {
                    DehState.logOnce("unknown Thing field '" + e.getKey() + "' -- ignored");
                }
                skipped++;
                continue;
            }
            if (id.equals("flags")) {
                m.flags = parseBits(val, m.flags);
                any = true;
                continue;
            }
            final long v = parseLong(val, Long.MIN_VALUE);
            if (v == Long.MIN_VALUE) {
                DehState.logOnce("bad numeric Thing value '" + val + "' for " + e.getKey());
                skipped++;
                continue;
            }
            any = true;
            switch (id) {
                case "doomednum": m.doomednum = (int) v; break;
                case "spawnhealth": m.spawnhealth = (int) v; break;
                case "reactiontime": m.reactiontime = (int) v; break;
                case "painchance": m.painchance = (int) v; break;
                case "speed": m.speed = (int) v; break;
                case "radius": m.radius = (int) v; break;
                case "height": m.height = (int) v; break;
                case "mass": m.mass = (int) v; break;
                case "damage": m.damage = (int) v; break;
                case "seesound": m.seesound = DehState.mapSfx((int) v); break;
                case "attacksound": m.attacksound = DehState.mapSfx((int) v); break;
                case "painsound": m.painsound = DehState.mapSfx((int) v); break;
                case "deathsound": m.deathsound = DehState.mapSfx((int) v); break;
                case "activesound": m.activesound = DehState.mapSfx((int) v); break;
                case "spawnstate": case "seestate": case "painstate": case "meleestate":
                case "missilestate": case "deathstate": case "xdeathstate": case "raisestate":
                    if (v >= 0 && v < info.states.length) {
                        DehState.setInfoState(m, id, (int) v);
                    } else {
                        DehState.logOnce("Thing " + dehNum + " " + e.getKey() + " = " + v + " out of state range -- skipped");
                        skipped++;
                    }
                    break;
                default:
                    skipped++;
                    break;
            }
        }
        if (any) {
            things++;
        }
    }

    /** DEH Bits: plain number replaces flags; mnemonic list (A+B+C) rebuilds them. */
    private long parseBits(String val, long current) {
        final String t = val.trim();
        final long numeric = parseLong(t, Long.MIN_VALUE);
        if (numeric != Long.MIN_VALUE) {
            return numeric;
        }
        long flags = 0;
        for (final String tok : t.split("[+|,\\s]+")) {
            if (tok.isEmpty()) {
                continue;
            }
            final String name = tok.toUpperCase(Locale.ROOT).replaceFirst("^MF_", "");
            final Long bits = DehTables.MNEMONIC_FLAGS.get(name);
            if (bits != null) {
                flags |= bits;
            } else if (DehTables.MNEMONIC_FLAGS_UNSUPPORTED.contains(name)) {
                DehState.logOnce("flag mnemonic " + name + " not supported -- dropped");
            } else {
                DehState.logOnce("unknown flag mnemonic " + name + " -- dropped");
            }
        }
        return flags;
    }

    // ---------------------------------------------------------------- frames
    private void applyFrame(int idx, Map<String, String> fields) {
        if (idx < 0 || idx >= info.states.length) {
            DehState.logOnce("Frame " + idx + " out of range -- section skipped");
            skipped += fields.size();
            return;
        }
        final state_t st = info.states[idx];
        boolean any = false;
        for (final Map.Entry<String, String> e : fields.entrySet()) {
            final String key = DehTables.normalizeKey(e.getKey());
            final long v = parseLong(e.getValue(), Long.MIN_VALUE);
            if (v == Long.MIN_VALUE) {
                DehState.logOnce("bad numeric Frame value '" + e.getValue() + "' for " + e.getKey());
                skipped++;
                continue;
            }
            switch (key) {
                case "duration":
                    st.tics = (int) v * Defines.TIC_MUL;
                    any = true;
                    break;
                case "sprite number":
                    DehState.ensureSprnames((int) v);
                    DehState.setStateSprite(st, (int) v);
                    any = true;
                    break;
                case "sprite subnumber":
                    st.frame = (int) v;
                    any = true;
                    break;
                case "next frame":
                    if (v >= 0 && v < info.states.length) {
                        DehState.setStateNext(st, (int) v);
                        any = true;
                    } else {
                        DehState.logOnce("Frame " + idx + " next frame " + v + " out of range -- skipped");
                        skipped++;
                    }
                    break;
                case "unknown 1":
                    st.misc1 = (int) v;
                    any = true;
                    break;
                case "unknown 2":
                    st.misc2 = (int) v;
                    any = true;
                    break;
                case "action pointer":
                    // ancient numeric codepointer form; BEX [CODEPTR] is the supported route
                    DehState.logOnce("numeric 'Action pointer' Frame field not supported -- use [CODEPTR]");
                    skipped++;
                    break;
                default:
                    DehState.logOnce("unknown Frame field '" + e.getKey() + "' -- ignored");
                    skipped++;
                    break;
            }
        }
        if (any) {
            frames++;
        }
    }

    private void applyPointer(int target, int source) {
        if (target < 0 || target >= info.states.length || source < 0 || source >= info.states.length) {
            DehState.logOnce("Pointer " + target + " <- " + source + " out of range -- skipped");
            skipped++;
            return;
        }
        info.states[target].action = DehState.originalActionOf(source);
        pointers++;
    }

    // -------------------------------------------------------------- codeptr
    private void applyCodeptr(int frame, String bexName) {
        if (frame < 0 || frame >= info.states.length) {
            DehState.logOnce("[CODEPTR] frame " + frame + " out of range -- skipped");
            skipped++;
            return;
        }
        info.states[frame].action = resolveCodeptr(bexName);
        codeptrs++;
    }

    static ActiveStates resolveCodeptr(String bexName) {
        final String name = bexName.trim();
        if (name.equalsIgnoreCase("NULL")) {
            return ActiveStates.NOP;
        }
        final String full = name.regionMatches(true, 0, "A_", 0, 2) ? name : "A_" + name;
        for (final ActiveStates a : ActiveStates.values()) {
            if (a.name().equalsIgnoreCase(full)) {
                return a;
            }
        }
        final String upper = name.toUpperCase(Locale.ROOT).replaceFirst("^A_", "");
        if (DehTables.MBF_CODEPTRS_UNAVAILABLE.contains(upper)) {
            DehState.logOnce("MBF codepointer " + full + " not available -- no-op");
        } else {
            DehState.logOnce("unknown codepointer " + full + " -- no-op");
        }
        return ActiveStates.NOP;
    }

    // ---------------------------------------------------------------- sounds
    private void applySound(int idx, Map<String, String> fields) {
        if (idx < 0 || idx >= sounds.S_sfx.length) {
            DehState.logOnce("Sound " + idx + " out of range -- section skipped");
            skipped += fields.size();
            return;
        }
        boolean any = false;
        for (final Map.Entry<String, String> e : fields.entrySet()) {
            final String key = DehTables.normalizeKey(e.getKey());
            final long v = parseLong(e.getValue(), Long.MIN_VALUE);
            if (key.equals("value") && v != Long.MIN_VALUE) {
                sounds.S_sfx[idx].priority = (int) v;
                any = true;
            } else if (key.equals("zero/one") && v != Long.MIN_VALUE) {
                sounds.S_sfx[idx].singularity = v != 0;
                any = true;
            } else {
                skipped++; // offsets and the zero fields have no runtime meaning here
            }
        }
        if (any) {
            soundsN++;
        }
    }

    // ------------------------------------------------------------------ ammo
    private void applyAmmo(int idx, Map<String, String> fields) {
        if (idx < 0 || idx >= DoomStatus.maxammo.length) {
            DehState.logOnce("Ammo " + idx + " out of range -- section skipped");
            skipped += fields.size();
            return;
        }
        boolean any = false;
        for (final Map.Entry<String, String> e : fields.entrySet()) {
            final String key = DehTables.normalizeKey(e.getKey());
            final long v = parseLong(e.getValue(), Long.MIN_VALUE);
            if (v == Long.MIN_VALUE) {
                skipped++;
                continue;
            }
            if (key.equals("max ammo")) {
                DoomStatus.maxammo[idx] = (int) v;
                any = true;
            } else if (key.equals("per ammo")) {
                player_t.clipammo[idx] = (int) v;
                any = true;
            } else {
                DehState.logOnce("unknown Ammo field '" + e.getKey() + "' -- ignored");
                skipped++;
            }
        }
        if (any) {
            ammoN++;
        }
    }

    // ---------------------------------------------------------------- weapons
    private void applyWeapon(int idx, Map<String, String> fields) {
        if (idx < 0 || idx >= items.weaponinfo.length) {
            DehState.logOnce("Weapon " + idx + " out of range -- section skipped");
            skipped += fields.size();
            return;
        }
        boolean any = false;
        for (final Map.Entry<String, String> e : fields.entrySet()) {
            final String key = DehTables.normalizeKey(e.getKey());
            final long v = parseLong(e.getValue(), Long.MIN_VALUE);
            if (v == Long.MIN_VALUE) {
                skipped++;
                continue;
            }
            if (key.equals("ammo type")) {
                items.weaponinfo[idx].ammo = v >= 0 && v < 4 ? ammotype_t.values()[(int) v] : ammotype_t.am_noammo;
                any = true;
                continue;
            }
            final String which;
            switch (key) {
                // DEH's names are inverted relative to what they read as: "Deselect frame"
                // sets the UP state and "Select frame" the DOWN state. Reference appliers
                // agree, and patches are internally consistent only this way round — the
                // frame a patch names in Deselect carries A_Raise, its Select carries
                // A_Lower. Mapping them intuitively raised weapons into A_Lower, which
                // re-entered BringUpWeapon with pendingweapon already wp_nochange and
                // indexed weaponinfo[] out of bounds at every commercial-game spawn.
                case "deselect frame": which = "up"; break;
                case "select frame": which = "down"; break;
                case "bobbing frame": which = "ready"; break;
                case "shooting frame": which = "atk"; break;
                case "firing frame": which = "flash"; break;
                default:
                    DehState.logOnce("unknown Weapon field '" + e.getKey() + "' -- ignored");
                    skipped++;
                    continue;
            }
            if (v < 0 || v >= DehTables.VANILLA_NUMSTATES) {
                // psprites still walk enum states; extended weapon frames are out of scope
                DehState.logOnce("Weapon frame " + v + " outside vanilla states -- skipped (psprites not extended)");
                skipped++;
                continue;
            }
            final statenum_t sn = statenum_t.values()[(int) v];
            switch (which) {
                case "down": items.weaponinfo[idx].downstate = sn; break;
                case "up": items.weaponinfo[idx].upstate = sn; break;
                case "ready": items.weaponinfo[idx].readystate = sn; break;
                case "atk": items.weaponinfo[idx].atkstate = sn; break;
                case "flash": items.weaponinfo[idx].flashstate = sn; break;
            }
            any = true;
        }
        if (any) {
            weaponsN++;
        }
    }

    // ------------------------------------------------------------------ misc
    private void applyMisc(Map<String, String> misc) {
        for (final Map.Entry<String, String> e : misc.entrySet()) {
            final String key = DehTables.normalizeKey(e.getKey());
            final long v = parseLong(e.getValue(), Long.MIN_VALUE);
            if (key.equals("initial health") && v != Long.MIN_VALUE) {
                DehMisc.initialHealth = (int) v;
            } else if (key.equals("initial bullets") && v != Long.MIN_VALUE) {
                DehMisc.initialBullets = (int) v;
            } else {
                skippedMisc.add(e.getKey());
                skipped++;
            }
        }
    }

    // ------------------------------------------------------------- old texts
    private void applyText(String from, String to) {
        if (from.length() == 4 && to.length() == 4 && DehState.renameSprite(from, to)) {
            texts++;
            return;
        }
        if (from.length() <= 6) {
            for (int i = 1; i < sounds.S_sfx.length; i++) {
                if (from.equalsIgnoreCase(sounds.S_sfx[i].name)) {
                    sounds.S_sfx[i].name = to.toLowerCase(Locale.ROOT);
                    texts++;
                    return;
                }
            }
            for (int i = 1; i < sounds.S_music.length; i++) {
                if (from.equalsIgnoreCase(sounds.S_music[i].name)) {
                    sounds.S_music[i].name = to.toLowerCase(Locale.ROOT);
                    texts++;
                    return;
                }
            }
        }
        DehState.logOnce("Text replacement not runtime-reachable (englsh constants are inlined): '"
            + (from.length() > 24 ? from.substring(0, 24) + "..." : from) + "'");
        skipped++;
    }

    // ------------------------------------------------------------- [STRINGS]
    private static final Pattern HUSTR_MAP = Pattern.compile("(?i)^(P|T)?HUSTR_(\\d+)$");
    private static final Pattern HUSTR_EXMY = Pattern.compile("(?i)^HUSTR_E(\\d)M(\\d)$");
    private static final Pattern FINALE_TEXT = Pattern.compile("(?i)^([CEPT])(\\d)TEXT$");

    private final Set<String> unreachableStrings = new LinkedHashSet<>();

    private void applyBexString(String key, String value, DoomMain<?, ?> DOOM) {
        DehStrings.put(key, value); // always available to marked hook sites
        Matcher m = HUSTR_EXMY.matcher(key);
        if (m.matches()) {
            final int e = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
            if (DOOM != null && DOOM.headsUp != null
                && DOOM.headsUp.dehSetMapName(0, (e - 1) * 9 + (mi - 1), value)) {
                strings++;
                return;
            }
        }
        m = HUSTR_MAP.matcher(key);
        if (m.matches()) {
            final String prefix = m.group(1);
            final int n = Integer.parseInt(m.group(2));
            final int which = prefix == null ? 1 : prefix.equalsIgnoreCase("P") ? 2 : 3;
            if (DOOM != null && DOOM.headsUp != null && DOOM.headsUp.dehSetMapName(which, n - 1, value)) {
                strings++;
                return;
            }
            if (DOOM == null) {
                strings++; // standalone harness: no HU instance to patch
                return;
            }
        }
        m = FINALE_TEXT.matcher(key);
        if (m.matches()) {
            final int n = Integer.parseInt(m.group(2)) - 1;
            final String[] arr;
            switch (Character.toUpperCase(m.group(1).charAt(0))) {
                case 'C': arr = f.Finale.doom2_text; break;
                case 'E': arr = f.Finale.doom_text; break;
                case 'P': arr = f.Finale.plut_text; break;
                default: arr = f.Finale.tnt_text; break;
            }
            if (n >= 0 && n < arr.length) {
                arr[n] = value;
                strings++;
                return;
            }
        }
        if (key.toUpperCase(Locale.ROOT).startsWith("CC_")) {
            strings++; // consumed lazily by the Finale cast hook via DehStrings
            return;
        }
        unreachableStrings.add(key);
        if (unreachableStrings.size() <= 8) {
            DehState.logOnce("[STRINGS] " + key + " has no runtime home (inlined constant) -- stored only");
        }
        strings++;
    }

    // ----------------------------------------------------------------- [PARS]
    private void applyPar(int[] par, DoomMain<?, ?> DOOM) {
        if (DOOM == null) {
            pars++; // standalone harness: no game instance to patch
            return;
        }
        final int episode = par[0], map = par[1], seconds = par[2];
        if (episode < 0) {
            if (map >= 1 && map <= DOOM.cpars.length) {
                DOOM.cpars[map - 1] = seconds;
                pars++;
            } else {
                DehState.logOnce("[PARS] map " + map + " out of range -- skipped");
                skipped++;
            }
        } else {
            if (episode >= 1 && episode < DOOM.pars.length && map >= 1 && map < DOOM.pars[episode].length) {
                DOOM.pars[episode][map] = seconds;
                pars++;
            } else {
                DehState.logOnce("[PARS] E" + episode + "M" + map + " out of range -- skipped");
                skipped++;
            }
        }
    }
}
