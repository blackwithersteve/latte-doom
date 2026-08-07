package com.blackwithersteve.lattedoom.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GZDoom-style sector triangulation: each sector's floor/ceiling becomes polygons-with-holes,
 * triangulated directly from its linedef boundary loops by the vendored mapbox {@link Earcut}.
 * Walls are later built from the SAME linedef vertices these loops are made of, so floor rim
 * and wall base share vertices by construction, so the class of random gaps that BSP-fragment
 * gluing produces cannot exist here. No BSP involvement; NODES can be garbage.
 *
 * This class owns the DOOM-specific parts: (1) tracing boundary loops from directed linedef
 * edges (front side = v1->v2, back = v2->v1; interior on the RIGHT; self-referencing lines
 * skipped; sharpest-right turn at junction vertices splits figure-8 contacts), (2) classifying
 * loops by ORIENTATION — interior-on-right makes outer loops clockwise (negative shoelace) and
 * hole loops counter-clockwise — and (3) assigning each hole to its smallest containing outer.
 * Everything hard (bridging, clipping, degenerate cures, splits) is Earcut's, battle-tested.
 */
public final class SectorTriangulator {

    /**
     * Triangles per sector, packed {x1,y1, x2,y2, x3,y3}* in DOOM units, CCW.
     * fallback[s] marks sectors whose boundary is BROKEN IN THE MAP DATA (unclosed sector,
     * a real vanilla mapping error — E1M3 s7, E3M2 s21/22, E3M8 s1): those triangulate from
     * BSP subsector fragments instead, exactly like every real port special-cases them.
     */
    public record Result(double[][] sectorTris, boolean[] fallback, List<String> issues) {}

    public static Result triangulate(DoomMap map) {
        final int n = map.sectors.size();
        final double[][] out = new double[n][];
        final boolean[] fallback = new boolean[n];
        final List<String> issues = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            try {
                out[s] = triangulateSector(map, s, issues);
            } catch (Exception e) {
                issues.add("sector " + s + ": EXCEPTION " + e);
                out[s] = null;
            }
            if (out[s] == null) { // unclosed boundary — the map itself is broken here
                out[s] = bspFallback(map, s);
                fallback[s] = true;
                issues.add("warn: sector " + s + " has an unclosed boundary (map defect) — BSP-fragment fallback");
            }
        }
        return new Result(out, fallback, issues);
    }

    /** Fan-triangulate the sector's BSP subsector polygons (the broken-map special case). */
    private static double[] bspFallback(DoomMap map, int s) {
        final List<Double> tris = new ArrayList<>();
        for (DoomMap.SubPoly p : map.subsectorPolys()) {
            if (p.sector() != s) {
                continue;
            }
            final double[] xs = p.xs(), ys = p.ys();
            for (int i = 1; i + 1 < xs.length; i++) {
                final double area = (xs[i] - xs[0]) * (ys[i + 1] - ys[0])
                    - (ys[i] - ys[0]) * (xs[i + 1] - xs[0]);
                if (Math.abs(area) < 1e-9) {
                    continue;
                }
                tris.add(xs[0]);
                tris.add(ys[0]);
                if (area > 0) {
                    tris.add(xs[i]);
                    tris.add(ys[i]);
                    tris.add(xs[i + 1]);
                    tris.add(ys[i + 1]);
                } else {
                    tris.add(xs[i + 1]);
                    tris.add(ys[i + 1]);
                    tris.add(xs[i]);
                    tris.add(ys[i]);
                }
            }
        }
        final double[] packed = new double[tris.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = tris.get(i);
        }
        return packed;
    }

    // ------------------------------------------------------------ per sector

    /** Returns null when the boundary is unclosed (map defect) — caller uses the BSP fallback. */
    private static double[] triangulateSector(DoomMap map, int s, List<String> issues) {
        final List<double[][]> loops = buildLoops(map, s);
        if (loops == null) {
            return null; // unclosed boundary
        }
        if (loops.isEmpty()) {
            return new double[0]; // sector referenced by no lines: genuinely nothing to draw
        }
        // Orientation classifies: traced interior-on-right => outers CW (shoelace < 0),
        // holes CCW (> 0). No parity ray-casting — the fragility that filled pillars.
        final List<double[][]> outers = new ArrayList<>();
        final List<double[][]> holes = new ArrayList<>();
        for (double[][] loop : loops) {
            if (signedArea(loop) < 0) {
                outers.add(loop);
            } else {
                holes.add(loop);
            }
        }
        if (outers.isEmpty()) {
            return null; // hole loops with no outer: the boundary data is broken too
        }
        // assign each hole to the smallest outer that contains it
        final Map<Integer, List<double[][]>> byOuter = new HashMap<>();
        for (double[][] h : holes) {
            int best = -1;
            double bestArea = Double.MAX_VALUE;
            for (int o = 0; o < outers.size(); o++) {
                final double a = -signedArea(outers.get(o)); // outers are CW: positive magnitude
                if (a < bestArea && containsPoint(outers.get(o), leftmost(h))) {
                    best = o;
                    bestArea = a;
                }
            }
            if (best < 0) {
                issues.add("warn: sector " + s + " hole not inside any outer; dropped");
            } else {
                byOuter.computeIfAbsent(best, k -> new ArrayList<>()).add(h);
            }
        }

        final List<Double> tris = new ArrayList<>();
        for (int o = 0; o < outers.size(); o++) {
            triangulateGroup(outers.get(o), byOuter.getOrDefault(o, List.of()), tris);
        }
        final double[] packed = new double[tris.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = tris.get(i);
        }
        return packed;
    }

    /** Flatten one outer + its holes into Earcut's input, map index output back to coords. */
    private static void triangulateGroup(double[][] outer, List<double[][]> holes,
                                         List<Double> out) {
        int total = outer.length;
        for (double[][] h : holes) {
            total += h.length;
        }
        final double[] data = new double[total * 2];
        final int[] holeIdx = new int[holes.size()];
        int v = 0;
        for (double[] p : outer) {
            data[v * 2] = p[0];
            data[v * 2 + 1] = p[1];
            v++;
        }
        for (int h = 0; h < holes.size(); h++) {
            holeIdx[h] = v;
            for (double[] p : holes.get(h)) {
                data[v * 2] = p[0];
                data[v * 2 + 1] = p[1];
                v++;
            }
        }
        final int[] idx = Earcut.earcut(data, holeIdx);
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final double ax = data[idx[i] * 2], ay = data[idx[i] * 2 + 1];
            final double bx = data[idx[i + 1] * 2], by = data[idx[i + 1] * 2 + 1];
            final double cx = data[idx[i + 2] * 2], cy = data[idx[i + 2] * 2 + 1];
            final double area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            if (Math.abs(area) < 1e-9) {
                continue; // degenerate sliver
            }
            out.add(ax);
            out.add(ay);
            if (area > 0) { // normalize every triangle to CCW in doom coords
                out.add(bx);
                out.add(by);
                out.add(cx);
                out.add(cy);
            } else {
                out.add(cx);
                out.add(cy);
                out.add(bx);
                out.add(by);
            }
        }
    }

    // ------------------------------------------------------------ boundary loops

    /**
     * Directed boundary edges of a sector: walking them keeps the sector on the RIGHT
     * (DOOM's front side faces right of v1->v2). At a junction vertex the sharpest
     * right turn hugs the sector interior, splitting figure-8 contacts into simple loops.
     */
    private static List<double[][]> buildLoops(DoomMap map, int s) {
        final List<double[]> edges = new ArrayList<>(); // {x1,y1,x2,y2}
        for (DoomMap.Line l : map.lines) {
            final boolean front = l.frontSector() == s;
            final boolean back = l.backSector() == s;
            if (front == back) {
                continue; // not ours, or self-referencing (deep-water hack) — no boundary
            }
            if (front) {
                edges.add(new double[]{l.x1(), l.y1(), l.x2(), l.y2()});
            } else {
                edges.add(new double[]{l.x2(), l.y2(), l.x1(), l.y1()});
            }
        }
        if (edges.isEmpty()) {
            return List.of();
        }
        final Map<Long, List<Integer>> byStart = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            byStart.computeIfAbsent(key(edges.get(i)[0], edges.get(i)[1]), k -> new ArrayList<>()).add(i);
        }
        final boolean[] used = new boolean[edges.size()];
        final List<double[][]> loops = new ArrayList<>();
        for (int start = 0; start < edges.size(); start++) {
            if (used[start]) {
                continue;
            }
            final List<double[]> loop = new ArrayList<>();
            int cur = start;
            boolean closed = false;
            for (int guard = 0; guard <= edges.size(); guard++) {
                used[cur] = true;
                final double[] e = edges.get(cur);
                loop.add(new double[]{e[0], e[1]});
                if (key(e[2], e[3]) == key(edges.get(start)[0], edges.get(start)[1])) {
                    closed = true;
                    break;
                }
                final List<Integer> nexts = byStart.get(key(e[2], e[3]));
                int chosen = -1;
                double bestTurn = Double.MAX_VALUE;
                if (nexts != null) {
                    final double inx = e[2] - e[0], iny = e[3] - e[1];
                    for (int cand : nexts) {
                        if (used[cand]) {
                            continue;
                        }
                        final double[] c = edges.get(cand);
                        final double ox = c[2] - c[0], oy = c[3] - c[1];
                        double a = Math.atan2(inx * oy - iny * ox, inx * ox + iny * oy);
                        if (a <= -Math.PI + 1e-9) {
                            a = Math.PI; // exact U-turn: last resort
                        }
                        if (a < bestTurn) {
                            bestTurn = a;
                            chosen = cand;
                        }
                    }
                }
                if (chosen < 0) {
                    break; // dead end — unclosed sector (mapping error); handled below
                }
                cur = chosen;
            }
            if (closed && loop.size() >= 3) {
                loops.add(loop.toArray(new double[0][]));
            } else if (!closed) {
                return null; // unclosed boundary: the map data is broken — BSP fallback
            }
        }
        return loops;
    }

    private static long key(double x, double y) {
        return ((long) Math.round(x) << 32) ^ (Math.round(y) & 0xffffffffL);
    }

    // ------------------------------------------------------------ small geometry

    private static double signedArea(double[][] loop) {
        double a = 0;
        for (int i = 0, j = loop.length - 1; i < loop.length; j = i++) {
            a += (loop[j][0] * loop[i][1]) - (loop[i][0] * loop[j][1]);
        }
        return a / 2;
    }

    private static double[] leftmost(double[][] loop) {
        double[] best = loop[0];
        for (double[] v : loop) {
            if (v[0] < best[0] || (v[0] == best[0] && v[1] < best[1])) {
                best = v;
            }
        }
        return best;
    }

    /** Half-open even-odd ray cast (robust at vertex crossings). */
    private static boolean containsPoint(double[][] loop, double[] p) {
        boolean in = false;
        for (int i = 0, j = loop.length - 1; i < loop.length; j = i++) {
            final double[] a = loop[i], b = loop[j];
            if ((a[1] > p[1]) != (b[1] > p[1])
                && p[0] < (b[0] - a[0]) * (p[1] - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    private SectorTriangulator() {}
}
