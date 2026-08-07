package com.blackwithersteve.lattedoom.render;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * DOOM's automap, drawn over the Minecraft HUD rather than as a flat engine screen. It is
 * rebuilt from the same DoomMap the level mesh uses, under vanilla am_map.c rules:
 *  - only lines the engine's own renderer has seen (ML_MAPPED, via the snapshot) draw,
 *    and the Computer Area Map power adds the unseen rest in grey;
 *  - one-sided and secret lines red, floor-change lines brown, ceiling-change yellow and
 *    ML_DONTDRAW hidden, with heights compared live so doors and lifts change colour as
 *    they move;
 *  - the white player arrow (the original 7-segment shape), north-up, follow mode;
 *  - black field down to the status bar, STBAR drawn on top, like 1993.
 * Zoom with keypad +/- (vanilla's 1.02x per tic). Toggled by the rebindable automap key.
 */
public final class DoomAutomap {

    // vanilla am_map.c palette entries, PLAYPAL-faithful
    private static final int BG = 0xFF000000;
    private static final int WALL = 0xFFFC0000;      // WALLCOLORS (reds)
    private static final int FDWALL = 0xFFBC7845;    // FDWALLCOLORS (browns) — floor change
    private static final int CDWALL = 0xFFFCFC00;    // CDWALLCOLORS (yellows) — ceiling change
    private static final int UNSEEN = 0xFF6C6C6C;    // GRAYS — allmap-revealed, never walked
    private static final int ARROW = 0xFFFFFFFF;     // white player arrow

    private static final int ML_SECRET = 0x0020;
    private static final int ML_DONTDRAW = 0x0080;

    private static boolean active;
    /** GUI pixels per doom unit, zoomed by the +/- keys. Seeded on first draw. */
    private static double scale;

    public static boolean active() {
        return active;
    }

    public static void toggle() {
        active = !active;
    }

    public static void reset() {
        active = false;
        scale = 0;
    }

    private static double minScale;

    /** One zoom step. Vanilla's M_ZOOMIN is per 35Hz tic; this runs on the 20Hz client
     * tick, so the step is larger to reach the same feel — a held key crosses the whole
     * range in about two seconds instead of ten. */
    public static void zoom(boolean in) {
        zoomBy(in ? 1.06 : 1.0 / 1.06);
    }

    /** A wheel notch, worth several held-key steps. */
    public static void zoomNotch(double direction) {
        zoomBy(direction > 0 ? 1.35 : 1.0 / 1.35);
    }

    private static void zoomBy(double factor) {
        if (scale > 0) {
            scale *= factor;
            if (minScale > 0 && scale < minScale) {
                scale = minScale; // the whole map is in view; further out is just haze
            }
            // and a ceiling, or a held key walks off into a single corridor wall
            if (minScale > 0 && scale > minScale * 24.0) {
                scale = minScale * 24.0;
            }
        }
    }

    /** Draw under the STBAR. Player position/angle in DOOM map coords (the mirror's). */
    static void draw(GuiGraphicsExtractor g, DoomMap map, WorldSnapshot snap,
                     double px, double py, double pAngDeg, int guiW, int guiH) {
        // the map owns the whole screen; only the classic status bar keeps its strip,
        // exactly the original's split — at larger screen sizes nothing of the world
        // shows through underneath
        final boolean bar = com.blackwithersteve.lattedoom.LatteDoomClient.hudSize() == 0;
        final int amH = bar ? (int) Math.round(168.0 * guiH / 200.0) : guiH;
        g.fill(0, 0, guiW, amH, BG);
        if (scale <= 0) {
            // seed at "most of the map in view": readable at a glance, and +/- zooms
            // from there — the old seed framed a corridor and read as a wall of lines
            final double mw = Math.max(64.0, map.maxX - map.minX);
            final double mh = Math.max(64.0, map.maxY - map.minY);
            final double fit = Math.min(guiW / mw, guiH / mh);
            scale = fit * 1.4;
            minScale = fit * 0.85;
        }
        if (false) {
            // first open: fit ~1500 map units across, the vanilla-ish initial window
            scale = guiW / 1500.0;
        }
        final double cx = guiW / 2.0, cy = amH / 2.0;

        for (int i = 0; i < map.lines.size(); i++) {
            final DoomMap.Line l = map.lines.get(i);
            final boolean mapped = snap.lineMapped != null && i < snap.lineMapped.length
                && snap.lineMapped[i];
            int color;
            if (mapped) {
                if ((l.flags() & ML_DONTDRAW) != 0) {
                    continue;
                }
                if (l.backSector() < 0 || (l.flags() & ML_SECRET) != 0) {
                    color = WALL; // one-sided (and secrets disguise as one-sided)
                } else if (heightDiff(snap, map, l, true)) {
                    color = FDWALL;
                } else if (heightDiff(snap, map, l, false)) {
                    color = CDWALL;
                } else {
                    continue; // two-sided, same heights: invisible without IDDT
                }
            } else if (snap.allmap && (l.flags() & ML_DONTDRAW) == 0) {
                color = UNSEEN;
            } else {
                continue;
            }
            line(g, cx + (l.x1() - px) * scale, cy - (l.y1() - py) * scale,
                cx + (l.x2() - px) * scale, cy - (l.y2() - py) * scale, color, amH);
        }

        // the player arrow: vanilla's 7 segments, R=16 map units, rotated to the facing.
        // Screen y is inverted vs map y, so the rotation angle flips sign.
        final double r = 16.0 * scale;
        final double a = -Math.toRadians(pAngDeg);
        final double[][] arrow = {
            {-r - r / 8, 0, r, 0},              // main shaft
            {r, 0, r - r / 2, r / 4},           // head barb up
            {r, 0, r - r / 2, -r / 4},          // head barb down
            {-r - r / 8, 0, -r - r / 8, r / 4}, // tail barb up... vanilla tail
            {-r - r / 8, 0, -r - r / 8, -r / 4},
            {-r + r / 8, 0, -r - r / 8, r / 4}, // tail chevron
            {-r + r / 8, 0, -r - r / 8, -r / 4},
        };
        final double ca = Math.cos(a), sa = Math.sin(a);
        for (double[] s : arrow) {
            final double x1 = s[0] * ca - s[1] * sa, y1 = s[0] * sa + s[1] * ca;
            final double x2 = s[2] * ca - s[3] * sa, y2 = s[2] * sa + s[3] * ca;
            line(g, cx + x1, cy + y1, cx + x2, cy + y2, ARROW, amH);
        }
    }

    /** LIVE height comparison across a two-sided line (doors/lifts recolor as they move). */
    private static boolean heightDiff(WorldSnapshot snap, DoomMap map, DoomMap.Line l,
                                      boolean floor) {
        final int f = l.frontSector(), b = l.backSector();
        if (f < 0 || b < 0) {
            return false;
        }
        final double[] h = floor ? snap.floorH : snap.ceilH;
        if (h != null && f < h.length && b < h.length) {
            return h[f] != h[b];
        }
        final DoomMap.Sector sf = map.sectors.get(f), sb = map.sectors.get(b);
        return floor ? sf.floorH() != sb.floorH() : sf.ceilH() != sb.ceilH();
    }

    /** Arbitrary-angle 1px line: rotate the GUI pose, fill a thin quad. Clipped to the
     * automap field by skipping lines fully outside (the pose fill has no scissor). */
    private static void line(GuiGraphicsExtractor g, double x1, double y1,
                             double x2, double y2, int color, int amH) {
        if ((y1 < 0 && y2 < 0) || (y1 > amH && y2 > amH)) {
            return;
        }
        final double len = Math.hypot(x2 - x1, y2 - y1);
        if (len < 0.5) {
            return;
        }
        final var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) x1, (float) y1);
        pose.rotate((float) Math.atan2(y2 - y1, x2 - x1));
        g.fill(0, 0, (int) Math.max(1, Math.round(len)), 1, color);
        pose.popMatrix();
    }

    private DoomAutomap() {}
}
