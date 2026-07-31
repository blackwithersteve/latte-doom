package com.blackwithersteve.lattedoom.render;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The WAD's sprite table — R_InitSpriteDefs's job, done on our side of the translator from
 * the same WAD file the engine booted. Scans S_START..S_END, parses each lump name into
 * (4-char sprite)(frame letter)(rotation digit)[(mirrored frame)(rotation)], and answers
 * "which lump + flip for sprite S, frame F, rotation R". Rotation 0 lumps cover all 8
 * views; mirrored pairs (e.g. TROOA2A8) share one lump with flip on the second.
 */
public final class SpriteSet {

    /** One resolved view: the lump name (texture key suffix) and whether to mirror it. */
    public record View(String lump, boolean flip) {}

    /** key = "TROOA" (sprite+frame letter) -> 8 rotations (index 0-7 = DOOM rot 0-7... rot0-only fills all). */
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
