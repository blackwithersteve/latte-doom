package com.blackwithersteve.lattedoom.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the level mesh. Floors and ceilings come from {@link SectorTriangulator} as
 * watertight sector polygons, and each wall surface is a single quad built from the same
 * linedef vertices those polygons use, so a floor rim and the base of its wall meet
 * exactly. Neither is split along a grid or per texture repeat: every registered texture
 * uses a repeating sampler, so tiling happens on the GPU and the mesh stays small.
 *
 * <p>Texture pegging follows the vendored engine's own renderer, where {@code texturemid}
 * is the world height at which texture row 0 sits and the vertical coordinate is
 * {@code (texturemid - h) / textureHeight}:
 * <ul>
 *   <li>one-sided middle: lower-unpegged uses floor + texture height, otherwise ceiling</li>
 *   <li>upper: upper-unpegged uses the front ceiling, otherwise back ceiling + height</li>
 *   <li>lower: lower-unpegged uses the front ceiling, otherwise the back floor</li>
 *   <li>masked middle: lower-unpegged uses opening bottom + height, otherwise opening top,
 *       drawn once and clamped to the opening</li>
 * </ul>
 *
 * <p>Sky handling follows the engine: where two sectors both have sky ceilings no upper
 * wall is emitted between them, and sky flats are not emitted at all, leaving the sky
 * backdrop visible through the gap.
 *
 * <p>Each vertex is {@code {x, y, z, u, v, sectorIndex}} and geometry is emitted as
 * four-vertex quads, with triangles written as degenerate quads. Map coordinates are
 * converted to world space as {@code x' = (x - cx) / 32}, {@code y' = h / 32} and
 * {@code z' = (cy - y) / 32}.
 */
public final class LatteMesh {

    public static final int FLOATS_PER_VERTEX = 6;
    private static final double UNITS = LatteWorld.UNITS;
    private static final double FLAT = 64.0;
    private static final String FALLBACK_TEX = "gray1";

    /** Switch texture pairs from a WAD's SWITCHES lump, upper-case name to partner. */
    private static java.util.Map<String, String> SWITCH_PAIRS = java.util.Map.of();

    public static void setSwitchPairs(java.util.Map<String, String> pairs) {
        SWITCH_PAIRS = pairs != null ? pairs : java.util.Map.of();
    }

    /** Texture key to size in texels, supplied by the texture registry; null when the
     * texture is not registered. */
    private static Function<String, int[]> texSize = k -> null;

    public static void setTexSize(Function<String, int[]> f) {
        texSize = f;
    }

    /** Converts a sector light level to a 0-255 grey, using the engine's 24-255 clamp. */
    public static int shadeByte(int light) {
        return Math.max(24, Math.min(255, light));
    }

    /** Source of sector heights for a build: either the map's current values or an
     * interpolated overlay. */
    public interface HeightFn {
        double floor(int sector);

        double ceil(int sector);
    }

    /**
     * Builds one sector's render group: its floor and ceiling triangles plus every wall
     * surface whose side faces this sector, at the sector's current heights.
     */
    public static Map<String, float[]> buildFor(DoomMap map, SectorTriangulator.Result tri,
                                                int s, double cx, double cy) {
        return buildForInterp(map, tri, s, cx, cy, new HeightFn() {
            public double floor(int sector) {
                return map.floorNow(sector);
            }

            public double ceil(int sector) {
                return map.ceilNow(sector);
            }
        });
    }

    /**
     * The same build at arbitrary heights, used per frame for sectors that are moving.
     * The renderer interpolates between the engine's last two 35 Hz keyframes so that
     * doors and lifts move smoothly at the display's frame rate while the engine remains
     * the authority on their actual positions.
     */
    public static Map<String, float[]> buildForInterp(DoomMap map, SectorTriangulator.Result tri,
                                                      int s, double cx, double cy, HeightFn hf) {
        return buildForInterp(map, tri, s, cx, cy, hf, null);
    }

    /**
     * As above, but taking a precomputed list of the linedef indices that touch the
     * sector. The per-frame path passes this so it visits only the relevant lines instead
     * of rescanning every linedef in the level for each moving sector on each frame, which
     * costs sectors times lines on large maps. Passing {@code null} falls back to a full
     * scan.
     */
    public static Map<String, float[]> buildForInterp(DoomMap map, SectorTriangulator.Result tri,
                                                      int s, double cx, double cy, HeightFn hf,
                                                      int[] lineIdx) {
        final Builder b = new Builder(map, cx, cy, hf);
        b.flats(tri, s);
        if (lineIdx != null) {
            for (final int li : lineIdx) {
                final DoomMap.Line l = map.lines.get(li);
                if (l.frontSector() == s) {
                    b.side(li, l, true);
                }
                if (l.backSector() == s) {
                    b.side(li, l, false);
                }
            }
        } else {
            for (int li = 0; li < map.lines.size(); li++) {
                final DoomMap.Line l = map.lines.get(li);
                if (l.frontSector() == s) {
                    b.side(li, l, true);
                }
                if (l.backSector() == s) {
                    b.side(li, l, false);
                }
            }
        }
        return b.pack();
    }

    // ------------------------------------------------------------------ builder

    private static final class Builder {
        final DoomMap map;
        final double cx, cy;
        final Map<String, List<float[]>> out = new HashMap<>();

        final HeightFn hf;

        Builder(DoomMap map, double cx, double cy, HeightFn hf) {
            this.hf = hf;
            this.map = map;
            this.cx = cx;
            this.cy = cy;
        }

        void flats(SectorTriangulator.Result tri, int s) {
            final double[] t = tri.sectorTris()[s];
            if (t == null || t.length == 0) {
                return;
            }
            final DoomMap.Sector sec = map.sectors.get(s);
            final String floorFlat = map.floorFlatNow(s);
            if (!isSky(floorFlat)) {
                emitFlat(t, s, hf.floor(s), floorFlat, false);
            }
            if (!isSky(sec.ceilFlat())) {
                emitFlat(t, s, hf.ceil(s), sec.ceilFlat(), true);
            }
            // Sky ceilings are left open so that the sky backdrop shows through. Filling
            // them with occluding geometry that projects the sky texture per vertex is not
            // viable at this granularity: affine interpolation of an angular projection
            // across large polygons visibly distorts the sky. Doing that correctly requires
            // subdividing the geometry or a dedicated render pass.
        }

        private void emitFlat(double[] tris, int s, double h, String flat, boolean ceiling) {
            final String key = "flats/" + flat.toLowerCase(Locale.ROOT);
            if (texSize.apply(key) == null) {
                return;
            }
            final float y = (float) (h / UNITS);
            final float si = s;
            for (int i = 0; i + 5 < tris.length; i += 6) {
                final float[][] v = new float[3][];
                for (int k = 0; k < 3; k++) {
                    final double x = tris[i + k * 2], dy = tris[i + k * 2 + 1];
                    v[k] = new float[]{
                        (float) ((x - cx) / UNITS), y, (float) ((cy - dy) / UNITS),
                        (float) (x / FLAT), (float) (-dy / FLAT), si};
                }
                // Ceilings reverse their winding so both faces are consistent. The
                // pipeline does not cull faces, but a consistent order keeps culling and
                // lighting options open.
                if (ceiling) {
                    quad(key, v[0], v[2], v[1], v[1]);
                } else {
                    quad(key, v[0], v[1], v[2], v[2]);
                }
            }
        }

        /** One side of one linedef: up to three wall surfaces, engine pegging rules. */
        private static String swSwap(String tex) {
            if (tex == null || tex.length() < 4) {
                return tex;
            }
            final String u = tex.toUpperCase(java.util.Locale.ROOT);
            // A WAD's own SWITCHES pairs take precedence over the built-in name prefixes.
            final String pair = SWITCH_PAIRS.get(u);
            if (pair != null) {
                return pair;
            }
            if (u.startsWith("SW1")) {
                return "SW2" + tex.substring(3);
            }
            if (u.startsWith("SW2")) {
                return "SW1" + tex.substring(3);
            }
            return tex;
        }

        void side(int li, DoomMap.Line l, boolean front) {
            final int sideIdx = front ? l.frontSide() : l.backSide();
            final int mySec = front ? l.frontSector() : l.backSector();
            if (sideIdx < 0 || mySec < 0) {
                return;
            }
            DoomMap.Side sd = map.sides.get(sideIdx);
            // The engine has flipped this line's front side, so swap the switch texture
            // for its partner and show the pressed state. Only switch textures are
            // affected; every other name passes through unchanged.
            if (front && map.switchedLines.contains(li)) {
                sd = new DoomMap.Side(sd.xofs(), sd.yofs(), swSwap(sd.upper()),
                    swSwap(sd.lower()), swSwap(sd.middle()), sd.sector());
            }
            final int otherSec = front ? l.backSector() : l.frontSector();
            final double x1 = front ? l.x1() : l.x2(), y1 = front ? l.y1() : l.y2();
            final double x2 = front ? l.x2() : l.x1(), y2 = front ? l.y2() : l.y1();
            final double sFloor = hf.floor(mySec), sCeil = hf.ceil(mySec);
            final boolean unpegTop = (l.flags() & DoomMap.ML_DONTPEGTOP) != 0;
            final boolean unpegBottom = (l.flags() & DoomMap.ML_DONTPEGBOTTOM) != 0;

            if (otherSec < 0) {
                // One-sided line: a solid middle surface from floor to ceiling.
                final String tex = wallOrFallback(sd.middle());
                final int texH = wallHeight(tex);
                final double mid = (unpegBottom ? sFloor + texH : sCeil) + sd.yofs();
                wall(tex, x1, y1, x2, y2, sFloor, sCeil, mid, sd.xofs(), mySec);
                return;
            }

            final double oFloor = hf.floor(otherSec), oCeil = hf.ceil(otherSec);
            final boolean skyBoth = isSky(map.sectors.get(mySec).ceilFlat())
                && isSky(map.sectors.get(otherSec).ceilFlat());

            if (oCeil < sCeil && !skyBoth) {
                final String tex = wallOrFallback(sd.upper());
                final int texH = wallHeight(tex);
                final double mid = (unpegTop ? sCeil : oCeil + texH) + sd.yofs();
                wall(tex, x1, y1, x2, y2, oCeil, sCeil, mid, sd.xofs(), mySec);
            }
            if (oFloor > sFloor) {
                final String tex = wallOrFallback(sd.lower());
                final int texH = wallHeight(tex);
                final double mid = (unpegBottom ? sCeil : oFloor) + sd.yofs();
                wall(tex, x1, y1, x2, y2, sFloor, oFloor, mid, sd.xofs(), mySec);
            }
            if (front && present(sd.middle())) {
                // Masked middle textures, such as grates: exactly one texture height,
                // clamped to the opening and never tiled vertically. A line with no middle
                // texture is see-through, so no fallback texture is substituted.
                final String key = "walls/" + sd.middle().toLowerCase(Locale.ROOT);
                final int[] size = texSize.apply(key);
                if (size != null) {
                    final double openBot = Math.max(sFloor, oFloor);
                    final double openTop = Math.min(sCeil, oCeil);
                    final double mid = (unpegBottom ? openBot + size[1] : openTop) + sd.yofs();
                    final double top = Math.min(mid, openTop);
                    final double bot = Math.max(mid - size[1], openBot);
                    if (top > bot) {
                        wall(sd.middle().toLowerCase(Locale.ROOT), x1, y1, x2, y2,
                            bot, top, mid, sd.xofs(), mySec);
                    }
                }
            }
        }

        /**
         * Emits a whole wall surface as a single quad. Repeat sampling tiles the texture,
         * so the surface needs no subdivision, and the quad's corners are exactly the
         * linedef's vertices, which are the same vertices the sector polygons use.
         * {@code texturemid} is the world height at which texture row 0 sits.
         */
        private void wall(String tex, double x1, double y1, double x2, double y2,
                          double zb, double zt, double texturemid, int xofs, int sectorIdx) {
            final String key = "walls/" + tex;
            final int[] size = texSize.apply(key);
            if (size == null || zt <= zb) {
                return;
            }
            final double texW = size[0], texH = size[1];
            final double len = Math.hypot(x2 - x1, y2 - y1);
            if (len < 1e-9) {
                return;
            }
            final float ua = (float) (xofs / texW);
            final float ub = (float) ((xofs + len) / texW);
            final float va = (float) ((texturemid - zt) / texH);
            final float vb = (float) ((texturemid - zb) / texH);
            final float ax = (float) ((x1 - cx) / UNITS), az = (float) ((cy - y1) / UNITS);
            final float bx = (float) ((x2 - cx) / UNITS), bz = (float) ((cy - y2) / UNITS);
            final float yT = (float) (zt / UNITS), yB = (float) (zb / UNITS);
            final float si = sectorIdx;
            quad(key,
                new float[]{ax, yT, az, ua, va, si},
                new float[]{bx, yT, bz, ub, va, si},
                new float[]{bx, yB, bz, ub, vb, si},
                new float[]{ax, yB, az, ua, vb, si});
        }

        private void quad(String key, float[] a, float[] b, float[] c, float[] d) {
            final float[] q = new float[FLOATS_PER_VERTEX * 4];
            System.arraycopy(a, 0, q, 0, 6);
            System.arraycopy(b, 0, q, 6, 6);
            System.arraycopy(c, 0, q, 12, 6);
            System.arraycopy(d, 0, q, 18, 6);
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }

        Map<String, float[]> pack() {
            final Map<String, float[]> packed = new HashMap<>();
            for (Map.Entry<String, List<float[]>> e : out.entrySet()) {
                final float[] all = new float[e.getValue().size() * FLOATS_PER_VERTEX * 4];
                int at = 0;
                for (float[] q : e.getValue()) {
                    System.arraycopy(q, 0, all, at, q.length);
                    at += q.length;
                }
                packed.put(e.getKey(), all);
            }
            return packed;
        }

        /** A missing solid wall texture renders as gray1 (map error made visible, not a hole). */
        private String wallOrFallback(String tex) {
            if (!present(tex)) {
                return FALLBACK_TEX;
            }
            final String k = tex.toLowerCase(Locale.ROOT);
            return texSize.apply("walls/" + k) != null ? k : FALLBACK_TEX;
        }

        private int wallHeight(String tex) {
            final int[] s = texSize.apply("walls/" + tex);
            return s != null ? s[1] : 128;
        }

        private static boolean present(String tex) {
            return tex != null && !tex.isEmpty() && !"-".equals(tex);
        }

        private static boolean isSky(String flat) {
            return "F_SKY1".equals(flat);
        }
    }

    private LatteMesh() {}
}
