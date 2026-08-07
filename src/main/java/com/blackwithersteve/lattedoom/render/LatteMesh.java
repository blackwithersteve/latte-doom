package com.blackwithersteve.lattedoom.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The new level mesh: flats from {@link SectorTriangulator} (watertight sector polygons),
 * walls as ONE quad per surface sharing the same linedef vertices the flat loops are made
 * of — so floor rim and wall base meet bit-exactly. No 64u grid splitting, no per-repeat
 * wall cuts: every registered texture carries a REPEAT sampler, so UVs tile on the GPU
 * (which is also why this mesh is an order of magnitude lighter than a per-repeat cut one).
 *
 * Texture pegging is re-derived from the vendored engine's own renderer
 * (rr/RendererState.java, the r_segs.c port — texturemid = the world height where texture
 * row 0 sits; v(h) = (texturemid - h) / texHeight):
 *   one-sided mid: DONTPEGBOTTOM ? floor + texH : ceil
 *   upper:         DONTPEGTOP    ? frontCeil    : backCeil + texH
 *   lower:         DONTPEGBOTTOM ? frontCeil    : backFloor
 *   masked mid:    DONTPEGBOTTOM ? openBottom + texH : openTop  (drawn once, clamped)
 *   sky hack: both ceilings F_SKY1 -> no upper wall between them; sky flats not emitted.
 *
 * Vertex layout matches the renderer: {X, Y, Z, u, v, sectorIndex} per vertex, 4-vertex
 * quads (triangles emitted as degenerate quads). World transform: X=(x-cx)/32, Y=h/32,
 * Z=(cy-y)/32.
 */
public final class LatteMesh {

    public static final int FLOATS_PER_VERTEX = 6;
    private static final double UNITS = LatteWorld.UNITS;
    private static final double FLAT = 64.0;
    private static final String FALLBACK_TEX = "gray1";

    /** Boom SWITCHES pairs (upper-case name -> partner), from DoomRuntimeTextures. */
    private static java.util.Map<String, String> SWITCH_PAIRS = java.util.Map.of();

    public static void setSwitchPairs(java.util.Map<String, String> pairs) {
        SWITCH_PAIRS = pairs != null ? pairs : java.util.Map.of();
    }

    /** BSP mode: walls from segs, flats from subsector leaves — the renderer's truth
     * for trick maps. Off = the classic sector/linedef mesh. */
    private static volatile boolean bspMode;

    public static void setBspMode(boolean on) {
        bspMode = on;
    }

    public static boolean bspMode() {
        return bspMode;
    }

    /** Wired by DoomRuntimeTextures: texture key -> [w,h] texels, null while unregistered. */
    private static Function<String, int[]> texSize = k -> null;

    public static void setTexSize(Function<String, int[]> f) {
        texSize = f;
    }

    /** DOOM's gamma correction level, 0 (off) to 4, id's own lookup tables. Applied at
     * this one choke point, so the world, the sprites and the view weapon all brighten
     * together the way the software renderer's palette did. */
    private static volatile int gammaLevel;

    public static void setGamma(int level) {
        gammaLevel = Math.max(0, Math.min(v.tables.GammaTables.LUT.length - 1, level));
        // the lit path carries gamma as a pow() exponent in the vertex alpha
        // fit the LUT's curve at its midpoint
        final double mid = v.tables.GammaTables.LUT[gammaLevel][128] / 255.0;
        final double exp = Math.log(Math.max(mid, 1e-3)) / Math.log(128.0 / 255.0);
        gammaAlpha = (int) Math.round(Math.max(0.05, Math.min(1.0, exp)) / 4.0 * 255.0);
    }

    public static int gamma() {
        return gammaLevel;
    }

    /** A flat raise of every sector's light, in DOOM light units. Distinct from gamma,
     * which curves the result instead of shifting it. */
    private static volatile int lightBoost;

    public static void setLightBoost(int units) {
        lightBoost = Math.max(0, Math.min(128, units));
    }

    public static int lightBoost() {
        return lightBoost;
    }

    /** The software light chain per pixel instead of the flat per-sector shade. Read at
     * submit time, so toggling is instant and needs no rebake. */
    private static volatile boolean doomLight = true;

    public static void setDoomLight(boolean on) {
        if (doomLight != on) {
            doomLight = on;
            // the persistent buffers bake a per-mode encoding: rebuild them
            LatteSectorBuffers.dispose();
        }
    }

    public static boolean doomLight() {
        return doomLight;
    }

    /** Diagnostic: the lit pipeline with constant inputs (full light, no contrast,
     * neutral gamma), so the shader provably outputs the unmodified texture. This
     * separates bad light data on real overdrawn geometry from the shader's structure
     * faulting the driver. Toggled by /doomlight's third position. */
    private static volatile boolean debugFlatLight;

    public static void setDebugFlatLight(boolean on) {
        debugFlatLight = on;
    }

    public static boolean debugFlatLight() {
        return debugFlatLight;
    }

    /** Gamma packed for the lit path's vertex alpha: pow exponent / 4. */
    private static volatile int gammaAlpha = 64;

    public static int gammaAlpha() {
        return gammaAlpha;
    }

    /** The lit-path shade for a sprite at a distance in DOOM units: the same banded
     * colormap curve the world shader runs, with LUT-exact gamma, so things sit in the
     * light of the room around them. Fullbright frames, meaning muzzle flashes, explosions
     * and plasma, take colormap zero exactly as R_DrawVissprite picks. Falls back to the
     * flat shade while doom light is off. */
    public static int doomShade(int light, double distUnits, boolean fullbright) {
        if (!doomLight) {
            return shadeByte(fullbright ? 255 : light);
        }
        int index = 0;
        if (!fullbright) {
            final int l = Math.max(0, Math.min(255, light + lightBoost));
            final int lightnum = l >> 4;
            index = Math.max(0, Math.min(31,
                (15 - lightnum) * 4 - (int) (1280.0 / Math.max(1.0, distUnits))));
        }
        final int base = Math.round(255f * (32 - index) / 32f);
        return v.tables.GammaTables.LUT[gammaLevel][base];
    }

    /** The 0..255 grey for a sector light level (DOOM's own 24..255 clamp + boost + gamma). */
    public static int shadeByte(int light) {
        // 32 per step: 16 read as barely-there in play, and the slider's whole span
        // must be an unmistakable difference (top = a 128 sector at full bright)
        final int base = Math.max(24, Math.min(255, light + lightBoost));
        return v.tables.GammaTables.LUT[gammaLevel][base];
    }

    /** Heights source for a bake: the discrete live map, or an interpolated overlay. */
    public interface HeightFn {
        double floor(int sector);

        double ceil(int sector);
    }

    /**
     * Build one sector's render group: its floor/ceiling triangles plus every wall surface
     * whose SIDE faces this sector, at the discrete live heights (the cache's truth).
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
     * Same bake at arbitrary heights — the per-frame path for MOVING sectors: the renderer
     * interpolates between the engine's last two 35Hz keyframes so doors glide at full FPS
     * (uncapped-interpolation, the modern source-port standard) while the engine stays the
     * authority on where things actually are.
     */
    public static Map<String, float[]> buildForInterp(DoomMap map, SectorTriangulator.Result tri,
                                                      int s, double cx, double cy, HeightFn hf) {
        return buildForInterp(map, tri, s, cx, cy, hf, null);
    }

    /**
     * As above, but with a precomputed list of the linedef indices that touch sector {@code s}
     * ({@code lineIdx}) — the per-frame mover path passes this so it walks a handful of lines
     * instead of rescanning the ENTIRE level's linedefs for every moving sector, every frame
     * (an O(sectors x lines) hot loop on big maps). {@code null} falls back to the full scan.
     */
    public static Map<String, float[]> buildForInterp(DoomMap map, SectorTriangulator.Result tri,
                                                      int s, double cx, double cy, HeightFn hf,
                                                      int[] lineIdx) {
        final Builder b = new Builder(map, cx, cy, hf);
        b.flats(tri, s);
        if (bspMode && map.hasBsp()) {
            // walls per SEG: the fragment's endpoints, the seg's own facing, and the
            // subsector's sector as the front — the BSP's answer, not the records'
            for (final int seg : map.sectorSegs()[s]) {
                b.seg(seg, s);
            }
            return b.pack();
        }
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

        /** The batch key of this sector's sky: its Boom-transferred texture, or "*" for
         * the level default (the renderer substitutes the map's own sky there). */
        private String skyKey(int s) {
            final String transfer = map.skyTextureFor(s);
            return "sky!" + (transfer != null
                ? transfer.toLowerCase(Locale.ROOT) : "*");
        }

        /** A sky surface: same triangles as a flat, keyed for the sky pass, whose shader
         * ignores position for color and samples by view direction. Depth still writes
         * at the real height — the original's sky clipping for free. */
        private void emitSkyFlat(double[] tris, int s, double h) {
            final String key = "f" + skyKey(s); // "fsky!...": the far-pushed flat pass
            final float y = (float) (h / UNITS);
            final float si = s;
            for (int i = 0; i + 5 < tris.length; i += 6) {
                final float[][] v = new float[3][];
                for (int k = 0; k < 3; k++) {
                    final double x = tris[i + k * 2], dy = tris[i + k * 2 + 1];
                    v[k] = new float[]{
                        (float) ((x - cx) / UNITS), y, (float) ((cy - dy) / UNITS),
                        0f, 0f, si};
                }
                quad(key, v[0], v[1], v[2], v[2]);
            }
        }

        /** As skyQuad, but on the far (never-occluding) pipeline — for sky that must
         * show without clipping anything, like the wedge between two sky heights. */
        private void skyFlatQuad(int s, double x1, double y1, double x2, double y2,
                                 double zb, double zt) {
            skyBand("f" + skyKey(s), s, x1, y1, x2, y2, zb, zt);
        }

        /** A sky wall: the band of sky above a border, from bot to top along the line. */
        private void skyQuad(int s, double x1, double y1, double x2, double y2,
                             double zb, double zt) {
            skyBand(skyKey(s), s, x1, y1, x2, y2, zb, zt);
        }

        private void skyBand(String key, int s, double x1, double y1, double x2,
                             double y2, double zb, double zt) {
            final float ax = (float) ((x1 - cx) / UNITS), az = (float) ((cy - y1) / UNITS);
            final float bx = (float) ((x2 - cx) / UNITS), bz = (float) ((cy - y2) / UNITS);
            final float yT = (float) (zt / UNITS), yB = (float) (zb / UNITS);
            final float si = s;
            quad(key,
                new float[]{ax, yT, az, 0f, 0f, si},
                new float[]{bx, yT, bz, 0f, 0f, si},
                new float[]{bx, yB, bz, 0f, 0f, si},
                new float[]{ax, yB, az, 0f, 0f, si});
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
            } else {
                emitSkyFlat(t, s, hf.floor(s));
            }
            if (!isSky(sec.ceilFlat())) {
                emitFlat(t, s, hf.ceil(s), sec.ceilFlat(), true);
            } else {
                emitSkyFlat(t, s, hf.ceil(s));
            }
            // sky ceilings stay HOLES (the backdrop cylinder shows through). The occluder
            // shell with per-vertex projected UVs was tried and REVERTED: affine
            // interpolation across big polygons warped the sky ("plastic mirrors").
            // A correct shell needs subdivision or a dedicated pass — see PLAN.
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
                // ceilings flip winding so both faces resolve consistently (pipeline is no-cull,
                // but a single consistent order keeps future culling/lighting options open)
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
            // Boom SWITCHES pairs first (custom sets — Eviternity), vanilla prefix after
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

        /** One seg's wall surfaces: the linedef's textures and pegging, the seg's own
         * span and start offset, the BSP's sector as the front. */
        void seg(int seg, int bspSec) {
            final int li = map.segLine(seg);
            final DoomMap.Line l = map.lines.get(li);
            final boolean front = map.segSide(seg) == 0;
            final int sideIdx = front ? l.frontSide() : l.backSide();
            if (sideIdx < 0) {
                return;
            }
            surfaces(li, l, front, sideIdx, bspSec,
                map.segX1(seg), map.segY1(seg), map.segX2(seg), map.segY2(seg),
                map.segOffset(seg));
        }

        void side(int li, DoomMap.Line l, boolean front) {
            final int sideIdx = front ? l.frontSide() : l.backSide();
            final int mySec = front ? l.frontSector() : l.backSector();
            if (sideIdx < 0 || mySec < 0) {
                return;
            }
            surfaces(li, l, front, sideIdx, mySec,
                front ? l.x1() : l.x2(), front ? l.y1() : l.y2(),
                front ? l.x2() : l.x1(), front ? l.y2() : l.y1(), 0);
        }

        private void surfaces(int li, DoomMap.Line l, boolean front, int sideIdx,
                              int mySec, double x1, double y1, double x2, double y2,
                              int extraXofs) {
            if (mySec < 0) {
                return;
            }
            DoomMap.Side sd = map.sides.get(sideIdx);
            // PRESSED SWITCHES (the engine flipped this line's front side): swap the
            // SW1xxx <-> SW2xxx texture names so the button visibly lights/changes.
            // Only switch-named textures transform; everything else passes through.
            if (front && map.switchedLines.contains(li)) {
                sd = new DoomMap.Side(sd.xofs(), sd.yofs(), swSwap(sd.upper()),
                    swSwap(sd.lower()), swSwap(sd.middle()), sd.sector());
            }
            final int otherSec = front ? l.backSector() : l.frontSector();
            final double sFloor = hf.floor(mySec), sCeil = hf.ceil(mySec);
            final boolean unpegTop = (l.flags() & DoomMap.ML_DONTPEGTOP) != 0;
            final boolean unpegBottom = (l.flags() & DoomMap.ML_DONTPEGBOTTOM) != 0;

            if (otherSec < 0) {
                // one-sided: solid middle, floor to ceiling
                final String tex = wallOrFallback(sd.middle());
                final int texH = wallHeight(tex);
                final double mid = (unpegBottom ? sFloor + texH : sCeil) + sd.yofs();
                wall(tex, x1, y1, x2, y2, sFloor, sCeil, mid,
                    sd.xofs() + extraXofs, mySec);
                if (isSky(map.sectors.get(mySec).ceilFlat())) {
                    // the sky continues above the wall: without this band, looking over
                    // the wall from a taller spot shows past the ceiling plane's edge
                    skyQuad(mySec, x1, y1, x2, y2, sCeil, sCeil + 8192);
                }
                return;
            }

            final double oFloor = hf.floor(otherSec), oCeil = hf.ceil(otherSec);
            final boolean mySky = isSky(map.sectors.get(mySec).ceilFlat());
            final boolean skyBoth = mySky
                && isSky(map.sectors.get(otherSec).ceilFlat());

            if (skyBoth && oCeil != sCeil) {
                // the wedge between two sky ceilings shows sky — but through the FAR
                // pipeline: the depth-writing version of this band was an invisible
                // barrier, and removing it outright left a void gap in the sky
                skyFlatQuad(mySec, x1, y1, x2, y2, Math.min(oCeil, sCeil),
                    Math.max(oCeil, sCeil));
            }
            if (mySky && !skyBoth && oCeil <= sCeil) {
                // sky-over-indoors: above the upper wall the column seals with sky,
                // same as a one-sided border (vanilla's sky hack). Without this band
                // a high viewpoint looks over an indoor room's ceiling straight into
                // its interior. Sky-sky borders stay open (the wedge above) so tall
                // outdoor rock behind a low sky edge still shows.
                skyQuad(mySec, x1, y1, x2, y2, sCeil, sCeil + 8192);
            }
            if (oCeil < sCeil && !skyBoth) {
                final String tex = wallOrFallback(sd.upper());
                final int texH = wallHeight(tex);
                final double mid = (unpegTop ? sCeil : oCeil + texH) + sd.yofs();
                wall(tex, x1, y1, x2, y2, oCeil, sCeil, mid,
                    sd.xofs() + extraXofs, mySec);
            }
            if (oFloor > sFloor) {
                final String tex = wallOrFallback(sd.lower());
                final int texH = wallHeight(tex);
                final double mid = (unpegBottom ? sCeil : oFloor) + sd.yofs();
                wall(tex, x1, y1, x2, y2, sFloor, oFloor, mid,
                    sd.xofs() + extraXofs, mySec);
            }
            if (front && present(sd.middle())) {
                // masked middle (grates): ONE texture height, clamped to the opening, never
                // tiled vertically. A missing middle is a see-through grate — no fallback.
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
                            bot, top, mid, sd.xofs() + extraXofs, mySec);
                    }
                }
            }
        }

        /**
         * One whole wall surface as ONE quad — REPEAT sampling tiles the texture, so no
         * cell splitting: the quad's corners are exactly the linedef's vertices, which are
         * exactly the flat loops' vertices. texturemid = world height of texture row 0.
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

        /** A missing SOLID wall texture renders as gray1 (map error made visible, not a hole). */
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
