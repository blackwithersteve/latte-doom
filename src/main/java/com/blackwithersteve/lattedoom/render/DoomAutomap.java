package com.blackwithersteve.lattedoom.render;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The automap, drawn over the Minecraft HUD from the same map data the level mesh is built
 * from, rather than by showing the engine's own screen. It follows the rules in
 * {@code am_map.c}:
 * <ul>
 *   <li>Only lines the player has seen are drawn, as recorded by the engine's
 *       {@code ML_MAPPED} flag and carried in the snapshot. The computer area map power-up
 *       adds the remaining lines in grey.</li>
 *   <li>One-sided and secret lines are red, floor-change lines brown and ceiling-change
 *       lines yellow, while {@code ML_DONTDRAW} lines are hidden. Heights are compared
 *       against live sector values, so doors and lifts change colour as they move.</li>
 *   <li>The player is a white arrow of the original seven-segment shape, drawn north-up in
 *       follow mode.</li>
 *   <li>The field is black down to the status bar, which is drawn on top.</li>
 * </ul>
 * Keypad plus and minus zoom at the original's rate of 1.02 per tic, and the map is
 * toggled with the configurable automap key.
 */
public final class DoomAutomap {

    // Palette entries from am_map.c, matching the original's PLAYPAL colours.
    private static final int BG = 0xFF000000;
    private static final int WALL = 0xFFFC0000;      // WALLCOLORS (reds)
    private static final int FDWALL = 0xFFBC7845;    // FDWALLCOLORS (browns): floor change
    private static final int CDWALL = 0xFFFCFC00;    // CDWALLCOLORS (yellows): ceiling change
    private static final int UNSEEN = 0xFF6C6C6C;    // GRAYS: revealed by the area map only
    private static final int ARROW = 0xFFFFFFFF;     // white player arrow

    private static final int ML_SECRET = 0x0020;
    private static final int ML_DONTDRAW = 0x0080;

    private static boolean active;
    /** gui pixels per map unit, adjusted by the zoom keys and seeded on first draw. */
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

    public static void zoom(boolean in) {
        if (scale > 0) {
            scale *= in ? 1.02 : 1.0 / 1.02; // M_ZOOMIN / M_ZOOMOUT per tic
        }
    }

    /** Draws the map beneath the status bar. The player position and angle are given in
     * map coordinates. */
    static void draw(GuiGraphicsExtractor g, DoomMap map, WorldSnapshot snap,
                     double px, double py, double pAngDeg, int guiW, int guiH) {
        // The automap field covers the view area: everything above the status bar, which
        // begins at canvas y 168.
        final int amH = (int) Math.round(168.0 * guiH / 200.0);
        g.fill(0, 0, guiW, amH, BG);
        if (scale <= 0) {
            // On first open, fit roughly 1500 map units across, close to the
            // original's initial window.
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
                    color = WALL; // one-sided, and secret lines are drawn as one-sided
                } else if (heightDiff(snap, map, l, true)) {
                    color = FDWALL;
                } else if (heightDiff(snap, map, l, false)) {
                    color = CDWALL;
                } else {
                    continue; // two-sided with equal heights: not drawn
                }
            } else if (snap.allmap && (l.flags() & ML_DONTDRAW) == 0) {
                color = UNSEEN;
            } else {
                continue;
            }
            line(g, cx + (l.x1() - px) * scale, cy - (l.y1() - py) * scale,
                cx + (l.x2() - px) * scale, cy - (l.y2() - py) * scale, color, amH);
        }

        // The player arrow: the original's seven segments at a radius of 16 map units,
        // rotated to the facing. Screen y is inverted relative to map y, so the rotation
        // angle changes sign.
        final double r = 16.0 * scale;
        final double a = -Math.toRadians(pAngDeg);
        final double[][] arrow = {
            {-r - r / 8, 0, r, 0},              // main shaft
            {r, 0, r - r / 2, r / 4},           // head barb up
            {r, 0, r - r / 2, -r / 4},          // head barb down
            {-r - r / 8, 0, -r - r / 8, r / 4}, // tail barbs
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

    /** Compares live sector heights across a two-sided line, so doors and lifts change
     * colour as they move. */
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

    /** Draws a one-pixel line at an arbitrary angle by rotating the gui pose and filling a
     * thin quad. Lines entirely outside the automap field are skipped, since a rotated pose
     * fill cannot be scissored. */
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
