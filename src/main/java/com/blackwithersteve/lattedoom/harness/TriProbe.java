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
 * Headless gate for the sector triangulator: runs every map of a WAD and checks
 *  (1) AREA — new triangle area per map vs the legacy BSP-fragment reconstruction
 *      (which UNDER-covers by its dropped slivers; small positive delta expected),
 *  (2) WATERTIGHT — every boundary edge of a sector's triangulation (an edge used by
 *      exactly ONE triangle) must lie ON a linedef of that sector: the floor rim IS the
 *      wall base, gaps impossible by construction,
 *  (3) triangle counts vs the legacy flat-quad counts (the performance headline).
 * Exit code 0 only if every map passes. Run: gradlew triProbe [-Pwad=...] [-Pmap=ExMy]
 */
public final class TriProbe {

    public static void main(String[] args) throws Exception {
        final WadFile wad = WadFile.read(Path.of(args[0]));
        final List<String> maps = args.length > 1 && !args[1].isEmpty()
            ? List.of(args[1]) : wad.mapNames();
        int failed = 0;
        for (String m : maps) {
            if (!probe(wad, m)) {
                failed++;
            }
        }
        System.out.printf("%n=== %d/%d maps PASS ===%n", maps.size() - failed, maps.size());
        System.exit(failed == 0 ? 0 : 1);
    }

    private static boolean probe(WadFile wad, String mapName) throws Exception {
        final DoomMap map = DoomMap.loadFromWad(wad, mapName);
        final SectorTriangulator.Result r = SectorTriangulator.triangulate(map);

        // (1) area vs legacy reconstruction
        double newArea = 0;
        int tris = 0;
        for (double[] t : r.sectorTris()) {
            for (int i = 0; i + 5 < t.length; i += 6) {
                newArea += Math.abs((t[i + 2] - t[i]) * (t[i + 5] - t[i + 1])
                    - (t[i + 3] - t[i + 1]) * (t[i + 4] - t[i])) / 2;
                tris++;
            }
        }
        double oldArea = 0;
        int oldPolys = 0;
        try {
            for (DoomMap.SubPoly p : map.subsectorPolys()) {
                final int n = p.xs().length;
                double a = 0;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    a += p.xs()[j] * p.ys()[i] - p.xs()[i] * p.ys()[j];
                }
                oldArea += Math.abs(a / 2);
                oldPolys++;
            }
        } catch (Exception e) {
            oldArea = -1; // legacy path may fail on exotic nodes; not a gate
        }
        final double delta = oldArea > 0 ? (newArea - oldArea) / oldArea * 100.0 : 0;

        // (2) watertight: boundary edges must lie on a linedef of their sector. Sectors on
        // the BSP fallback (unclosed-boundary map defects) are exempt — their geometry is
        // broken in the WAD itself; every port special-cases them.
        int openEdges = 0;
        int fallbacks = 0;
        for (int s = 0; s < r.sectorTris().length; s++) {
            if (r.fallback()[s]) {
                fallbacks++;
                continue;
            }
            final double[] t = r.sectorTris()[s];
            if (t == null || t.length == 0) {
                continue;
            }
            final Map<Long, Integer> edgeUse = new HashMap<>();
            final Map<Long, double[]> edgeCoords = new HashMap<>();
            for (int i = 0; i + 5 < t.length; i += 6) {
                for (int e = 0; e < 3; e++) {
                    final double ax = t[i + e * 2], ay = t[i + e * 2 + 1];
                    final double bx = t[i + (e + 1) % 3 * 2], by = t[i + (e + 1) % 3 * 2 + 1];
                    final long k = edgeKey(ax, ay, bx, by);
                    edgeUse.merge(k, 1, Integer::sum);
                    edgeCoords.putIfAbsent(k, new double[]{ax, ay, bx, by});
                }
            }
            final List<double[]> lines = sectorLines(map, s);
            for (Map.Entry<Long, Integer> e : edgeUse.entrySet()) {
                if (e.getValue() != 1) {
                    continue; // interior edge (shared by two triangles, or a hole bridge pair)
                }
                if (!onAnyLine(edgeCoords.get(e.getKey()), lines)) {
                    openEdges++;
                }
            }
        }

        // "warn:" issues (auto-closed vanilla mapping errors) don't fail the gate; anything
        // else (stalls, exceptions) and any open boundary edge does.
        long fatal = r.issues().stream().filter(x -> !x.startsWith("warn:")).count();
        final boolean pass = openEdges == 0 && fatal == 0;
        System.out.printf("%-6s: %5d tris (legacy %5d polys), area %+.2f%% vs legacy, openEdges=%d, fallback=%d, warn=%d, fatal=%d %s%n",
            mapName, tris, oldPolys, delta, openEdges, fallbacks, r.issues().size() - fatal, fatal,
            pass ? "PASS" : "FAIL");
        for (String issue : r.issues()) {
            System.out.println("        " + issue);
        }
        return pass;
    }

    private static List<double[]> sectorLines(DoomMap map, int s) {
        final List<double[]> out = new ArrayList<>();
        for (DoomMap.Line l : map.lines) {
            if ((l.frontSector() == s) != (l.backSector() == s)) {
                out.add(new double[]{l.x1(), l.y1(), l.x2(), l.y2()});
            }
        }
        return out;
    }

    private static boolean onAnyLine(double[] e, List<double[]> lines) {
        for (double[] l : lines) {
            if (onSegment(e[0], e[1], l) && onSegment(e[2], e[3], l)) {
                return true;
            }
        }
        return false;
    }

    private static boolean onSegment(double px, double py, double[] l) {
        final double ex = l[2] - l[0], ey = l[3] - l[1];
        final double len2 = ex * ex + ey * ey;
        if (len2 < 1e-9) {
            return false;
        }
        final double t = ((px - l[0]) * ex + (py - l[1]) * ey) / len2;
        if (t < -1e-6 || t > 1 + 1e-6) {
            return false;
        }
        final double dx = px - (l[0] + t * ex), dy = py - (l[1] + t * ey);
        return dx * dx + dy * dy < 1e-4;
    }

    /** Exact unordered-pair key: DOOM vertices are ints in ±32768, 16 bits per coordinate. */
    private static long edgeKey(double ax, double ay, double bx, double by) {
        final long a = (((Math.round(ax) + 32768) & 0xFFFFL) << 16) | ((Math.round(ay) + 32768) & 0xFFFFL);
        final long b = (((Math.round(bx) + 32768) & 0xFFFFL) << 16) | ((Math.round(by) + 32768) & 0xFFFFL);
        return a < b ? (a << 32) | b : (b << 32) | a;
    }

    private TriProbe() {}
}
