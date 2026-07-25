package com.blackwithersteve.lattedoom.render;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The WAD's sprite table, built on the rendering side from the same WAD file the engine
 * booted; it performs the equivalent of the engine's {@code R_InitSpriteDefs}. The
 * {@code S_START}..{@code S_END} range is scanned and each lump name is parsed as a
 * four-character sprite name, a frame letter and a rotation digit, optionally followed by
 * a second frame and rotation for a mirrored view. Lookups then answer which lump to draw,
 * and whether to mirror it, for a given sprite, frame and rotation. Rotation 0 lumps cover
 * all eight views, and mirrored pairs such as {@code TROOA2A8} share one lump with the
 * flip applied to the second view.
 */
public final class SpriteSet {

    /** One resolved view: the lump name (texture key suffix) and whether to mirror it. */
    public record View(String lump, boolean flip) {}

    /** Sprite name plus frame letter, such as "TROOA", to its eight rotations. A lump with
     * rotation 0 fills all eight. */
    private final Map<String, View[]> frames = new HashMap<>();

    public static SpriteSet load(WadFile wad) {
        final SpriteSet set = new SpriteSet();
        boolean in = false;
        for (WadFile.Lump l : wad.lumps) {
            final String n = l.name().toUpperCase(Locale.ROOT);
            if (n.equals("S_START") || n.equals("SS_START")) {
                in = true;
                continue;
            }
            if (n.equals("S_END") || n.equals("SS_END")) {
                in = false;
                continue;
            }
            if (!in || n.length() < 6) {
                continue;
            }
            set.add(n.substring(0, 4), n.charAt(4), n.charAt(5) - '0', n, false);
            if (n.length() >= 8) {
                set.add(n.substring(0, 4), n.charAt(6), n.charAt(7) - '0', n, true);
            }
        }
        return set;
    }

    private void add(String sprite, char frame, int rot, String lump, boolean flip) {
        if (rot < 0 || rot > 8) {
            return;
        }
        final View[] views = frames.computeIfAbsent(sprite + frame, k -> new View[8]);
        final View v = new View(lump.toLowerCase(Locale.ROOT), flip);
        if (rot == 0) {
            for (int i = 0; i < 8; i++) {
                if (views[i] == null) {
                    views[i] = v;
                }
            }
        } else {
            views[rot - 1] = v;
        }
    }

    /** Resolve (sprite name "TROO", frame index 0='A', DOOM rotation 0-7); null if absent. */
    public View view(String sprite, int frameIdx, int rot) {
        final View[] views = frames.get(sprite + (char) ('A' + frameIdx));
        return views == null ? null : views[rot & 7];
    }

    public int size() {
        return frames.size();
    }

    private SpriteSet() {}
}
