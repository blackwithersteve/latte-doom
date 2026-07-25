package com.blackwithersteve.lattedoom.render;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Animated textures and flats, resolved when a texture is bound for rendering and
 * following the engine's {@code P_UpdateSpecials}: sequences advance every 8 tics, and
 * each member of a sequence is phase-offset by its own index, so a floor mixing
 * {@code NUKAGE1} and {@code NUKAGE2} stays out of step exactly as it does in DOOM.
 *
 * <p>The animation definitions are the engine's own table. Sequences are resolved by WAD
 * directory order between the first and last member rather than by name arithmetic, which
 * is what makes {@code FIREWALA} to {@code FIREWALB} to {@code FIREWALL} work. The clock
 * is the engine tic carried by the snapshot, so freezing the engine also freezes the
 * animations.
 */
public final class LatteAnims {

    private record Def(boolean wall, String last, String first, int speed) {}

    /** The vanilla 1.9 animation table; a WAD's Boom {@code ANIMATED} lump replaces it. */
    private static final Def[] DEFS = {
        new Def(false, "NUKAGE3", "NUKAGE1", 8),
        new Def(false, "FWATER4", "FWATER1", 8),
        new Def(false, "SWATER4", "SWATER1", 8),
        new Def(false, "LAVA4", "LAVA1", 8),
        new Def(false, "BLOOD3", "BLOOD1", 8),
        new Def(false, "RROCK08", "RROCK05", 8),
        new Def(false, "SLIME04", "SLIME01", 8),
        new Def(false, "SLIME08", "SLIME05", 8),
        new Def(false, "SLIME12", "SLIME09", 8),
        new Def(true, "BLODGR4", "BLODGR1", 8),
        new Def(true, "SLADRIP3", "SLADRIP1", 8),
        new Def(true, "BLODRIP4", "BLODRIP1", 8),
        new Def(true, "FIREWALL", "FIREWALA", 8),
        new Def(true, "GSTFONT3", "GSTFONT1", 8),
        new Def(true, "FIRELAVA", "FIRELAV3", 8),
        new Def(true, "FIREMAG3", "FIREMAG1", 8),
        new Def(true, "FIREBLU2", "FIREBLU1", 8),
        new Def(true, "ROCKRED3", "ROCKRED1", 8),
        new Def(true, "BFALL4", "BFALL1", 8),
        new Def(true, "SFALL4", "SFALL1", 8),
        new Def(true, "WFALL4", "WFALL1", 8),
        new Def(true, "DBRAIN4", "DBRAIN1", 8),
    };

    private record Member(String[] seq, int index, int speed) {}

    /** texture key ("flats/nukage1") -> its sequence + own phase index. */
    private static final Map<String, Member> MEMBERS = new HashMap<>();

    /** Builds the animation map after texture compositing, given the wall and flat names
     * in WAD directory order. A Boom {@code ANIMATED} lump, if the WAD provides one,
     * replaces the vanilla definitions and supplies its own per-animation speed. */
    public static void build(List<String> orderedWalls, List<String> orderedFlats,
                             WadFile wad) {
        MEMBERS.clear();
        Def[] defs = DEFS;
        final byte[] lump = wad != null ? wad.lumpBytes("ANIMATED") : null;
        if (lump != null && lump.length >= 23) {
            final java.util.List<Def> parsed = new java.util.ArrayList<>();
            for (int o = 0; o + 23 <= lump.length; o += 23) {
                if (lump[o] == -1) {
                    break;
                }
                final int speed = (lump[o + 19] & 0xFF) | ((lump[o + 20] & 0xFF) << 8)
                    | ((lump[o + 21] & 0xFF) << 16) | ((lump[o + 22] & 0xFF) << 24);
                parsed.add(new Def((lump[o] & 1) != 0,
                    lumpStr(lump, o + 1), lumpStr(lump, o + 10), Math.max(1, speed)));
            }
            if (!parsed.isEmpty()) {
                defs = parsed.toArray(new Def[0]);
            }
        }
        final List<String> walls = lastWins(orderedWalls);
        final List<String> flats = lastWins(orderedFlats);
        for (Def d : defs) {
            final List<String> order = d.wall() ? walls : flats;
            final String prefix = d.wall() ? "walls/" : "flats/";
            final int a = order.indexOf(d.first());
            final int b = order.indexOf(d.last());
            if (a < 0 || b < a) {
                continue; // sequence absent in this WAD (e.g. DOOM2 anims in DOOM1)
            }
            final String[] seq = new String[b - a + 1];
            for (int i = 0; i < seq.length; i++) {
                seq[i] = prefix + order.get(a + i).toLowerCase(Locale.ROOT);
            }
            for (int i = 0; i < seq.length; i++) {
                MEMBERS.put(seq[i], new Member(seq, i, d.speed()));
            }
        }
    }

    /**
     * The directory as {@code W_CheckNumForName} resolves it: that search runs backward, so
     * a name defined in more than one WAD resolves to the last definition, and a sequence
     * range has to be measured over that same view. A patch WAD extends a vanilla animation
     * by inserting its own frames between the first and last vanilla names, for example
     * {@code FWATER1, FWATER02..FWATER31, FWATER4}. Measured inside that WAD's own block the
     * range is 32 frames; measured over the base WAD's copies it collapses to four, two of
     * which the patch never replaced, so the animation alternates between the patch's art
     * and the base game's.
     */
    private static List<String> lastWins(List<String> order) {
        final java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String n : order) {
            seen.remove(n); // a later definition takes the earlier one's place
            seen.add(n);
        }
        return new java.util.ArrayList<>(seen);
    }

    /** The texture key to bind for this key at the given engine tic; returns the key
     * unchanged when the texture is not animated. */
    public static String frame(String key, int tic) {
        final Member m = MEMBERS.get(key);
        if (m == null) {
            return key;
        }
        final int step = tic / Math.max(1, m.speed());
        return m.seq()[((step + m.index()) % m.seq().length + m.seq().length) % m.seq().length];
    }

    private static String lumpStr(byte[] b, int off) {
        int len = 0;
        while (len < 8 && b[off + len] != 0) {
            len++;
        }
        return new String(b, off, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private LatteAnims() {}
}
