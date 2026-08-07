package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.SectorTriangulator;
import com.blackwithersteve.lattedoom.render.WadFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coplanar-overlap detector for the flat mesh. Two sectors at the SAME floor (or
 * ceiling) height whose triangulations overlap in area produce the same texture
 * drawn twice at different light levels, which the lit renderer shows as dark
 * triangular sheets: depth-equal overdraw whose winner is decided by batch order.
 * This walks every map and reports every cross-sector overlapping triangle pair.
 *
 * Run: gradlew overlapProbe -Pwad=<absolute path> [-Pmap=ExMy]
 */
public final class OverlapProbe {

    private static final double EPS = 0.01;

    public static void main(String[] args) throws Exception {
        final WadFile wad = WadFile.read(Path.of(args[0]));
        final List<String> maps = args.length > 1 && !args[1].isEmpty()
            ? List.of(args[1]) : wad.mapNames();
        int totalPairs = 0;
        for (String m : maps) {
            totalPairs += probe(wad, m);
        }
        System.out.println(totalPairs == 0 ? "RESULT: CLEAN" : "RESULT: " + totalPairs + " OVERLAPS");
        System.exit(totalPairs == 0 ? 0 : 1);
    }

    private static int probe(WadFile wad, String mapName) throws Exception {
        final DoomMap map = DoomMap.loadFromWad(wad, mapName);
        final SectorTriangulator.Result r = SectorTriangulator.triangulate(map);
        final double[][] tris = r.sectorTris();
        final int n = Math.min(tris.length, map.sectors.size());

        // bucket sectors by height so only genuinely coplanar flats are compared
        final Map<Long, List<Integer>> byFloor = new HashMap<>();
        final Map<Long, List<Integer>> byCeil = new HashMap<>();
        for (int s = 0; s < n; s++) {
            if (tris[s] == null || tris[s].length == 0) {
                continue;
            }
            byFloor.computeIfAbsent((long) map.floorNow(s), k -> new ArrayList<>()).add(s);
            byCeil.computeIfAbsent((long) map.ceilNow(s), k -> new ArrayList<>()).add(s);
        }
        int pairs = 0;
        pairs += scan(mapName, "floor", byFloor, tris, map);
        pairs += scan(mapName, "ceil", byCeil, tris, map);
        // floors and ceilings meeting at the SAME height are coplanar too
        final Map<Long, List<Integer>> cross = new HashMap<>();
        for (Map.Entry<Long, List<Integer>> e : byFloor.entrySet()) {
            if (byCeil.containsKey(e.getKey())) {
                final List<Integer> both = new ArrayList<>(e.getValue());
                for (Integer c : byCeil.get(e.getKey())) {
                    if (!both.contains(c)) {
                        both.add(c);
                    }
                }
                if (both.size() > e.getValue().size()) {
                    cross.put(e.getKey(), both);
                }
            }
        }
        pairs += scan(mapName, "floor-x-ceil", cross, tris, map);
        return pairs;
    }

    private static int scan(String mapName, String kind, Map<Long, List<Integer>> buckets,
                            double[][] tris, DoomMap map) {
        int found = 0;
        for (List<Integer> bucket : buckets.values()) {
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    final int a = bucket.get(i), b = bucket.get(j);
                    final double[] overlap = firstOverlap(tris[a], tris[b]);
                    if (overlap != null) {
                        if (found == 0) {
                            System.out.printf("[overlap] %s:%n", mapName);
                        }
                        if (found < 12) {
                            System.out.printf(
                                "  %s s%d(light %d) x s%d(light %d) near (%.0f, %.0f)%n",
                                kind, a, map.sectors.get(a).light(),
                                b, map.sectors.get(b).light(),
                                overlap[0], overlap[1]);
                        }
                        found++;
                    }
                }
            }
        }
        if (found >= 12) {
            System.out.printf("  ... %d %s overlap pairs total%n", found, kind);
        }
        return found;
    }

    /** First overlapping triangle pair's meeting point, or null. Strict-interior
     * tests with an epsilon margin so shared edges between neighbours never count. */
    private static double[] firstOverlap(double[] ta, double[] tb) {
        for (int i = 0; i + 5 < ta.length; i += 6) {
            for (int j = 0; j + 5 < tb.length; j += 6) {
                final double[] p = triPairOverlap(ta, i, tb, j);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    private static double[] triPairOverlap(double[] a, int ai, double[] b, int bi) {
        // degenerate (zero-area) triangles never overlap anything
        if (area(a, ai) < EPS || area(b, bi) < EPS) {
            return null;
        }
        // any vertex or the centroid of one strictly inside the other
        for (int k = 0; k < 3; k++) {
            if (inside(b, bi, a[ai + k * 2], a[ai + k * 2 + 1])) {
                return new double[]{a[ai + k * 2], a[ai + k * 2 + 1]};
            }
            if (inside(a, ai, b[bi + k * 2], b[bi + k * 2 + 1])) {
                return new double[]{b[bi + k * 2], b[bi + k * 2 + 1]};
            }
        }
        final double cax = (a[ai] + a[ai + 2] + a[ai + 4]) / 3;
        final double cay = (a[ai + 1] + a[ai + 3] + a[ai + 5]) / 3;
        if (inside(b, bi, cax, cay)) {
            return new double[]{cax, cay};
        }
        final double cbx = (b[bi] + b[bi + 2] + b[bi + 4]) / 3;
        final double cby = (b[bi + 1] + b[bi + 3] + b[bi + 5]) / 3;
        if (inside(a, ai, cbx, cby)) {
            return new double[]{cbx, cby};
        }
        return null;
    }

    private static double area(double[] t, int i) {
        return Math.abs((t[i + 2] - t[i]) * (t[i + 5] - t[i + 1])
            - (t[i + 3] - t[i + 1]) * (t[i + 4] - t[i])) / 2;
    }

    /** Strictly inside (every signed area the same sign, margin EPS x edge length). */
    private static boolean inside(double[] t, int i, double px, double py) {
        double sign = 0;
        for (int k = 0; k < 3; k++) {
            final double x1 = t[i + k * 2], y1 = t[i + k * 2 + 1];
            final double x2 = t[i + ((k + 1) % 3) * 2], y2 = t[i + ((k + 1) % 3) * 2 + 1];
            final double cross = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1);
            final double len = Math.hypot(x2 - x1, y2 - y1);
            if (Math.abs(cross) < EPS * Math.max(len, 1)) {
                return false; // on an edge: shared boundaries don't count
            }
            final double s = Math.signum(cross);
            if (sign == 0) {
                sign = s;
            } else if (s != sign) {
                return false;
            }
        }
        return true;
    }

    private OverlapProbe() {
    }
}
