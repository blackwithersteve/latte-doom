package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.render.BspTriangulator;
import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.LatteMesh;
import com.blackwithersteve.lattedoom.render.SectorTriangulator;
import com.blackwithersteve.lattedoom.render.WadFile;

import java.nio.file.Path;
import java.util.Map;

/**
 * The BSP mesh against the classic mesh, every map of a WAD. On non-trick maps the two
 * must agree: per-sector flat area within tolerance (same ground, different cutting),
 * and total wall length within tolerance (segs fragment the same linedefs). A trick map
 * is EXPECTED to differ — the disagreement is the point — so this gate runs on the
 * IWADs, where every divergence is a bug in the new path.
 *
 * Run: gradlew bspProbe -Pwad=/path/to/DOOM.WAD
 */
public final class BspProbe {

    public static void main(String[] args) throws Exception {
        LatteMesh.setTexSize(k -> new int[]{64, 128});
        final WadFile wad = WadFile.read(Path.of(args[0]));
        int pass = 0, fail = 0;
        for (final WadFile.Lump l : wad.lumps) {
            if (!l.name().matches("E\\dM\\d|MAP\\d\\d")) {
                continue;
            }
            final DoomMap map = DoomMap.loadFromWad(wad, l.name());
            if (!map.hasBsp()) {
                System.out.println(l.name() + ": no BSP lumps, skipped");
                continue;
            }
            final SectorTriangulator.Result classic = SectorTriangulator.triangulate(map);
            final SectorTriangulator.Result bsp = BspTriangulator.triangulate(map);
            double worstArea = 0;
            int worstSec = -1;
            double areaClassic = 0, areaBsp = 0;
            for (int s = 0; s < map.sectors.size(); s++) {
                final double a = area(classic.sectorTris()[s]);
                final double b = area(bsp.sectorTris()[s]);
                areaClassic += a;
                areaBsp += b;
                if (classic.fallback()[s]) {
                    continue; // the classic path GUESSED here (unclosed sector, a map
                              // defect) — the BSP answer is the authority, not the peer
                }
                final double d = Math.abs(a - b) / Math.max(64.0, Math.max(a, b));
                if (d > worstArea) {
                    worstArea = d;
                    worstSec = s;
                }
            }
            // wall length: build both meshes for every sector and total the quad spans
            LatteMesh.setBspMode(false);
            final double wallsClassic = wallLength(map, classic);
            LatteMesh.setBspMode(true);
            final double wallsBsp = wallLength(map, bsp);
            LatteMesh.setBspMode(false);
            final double wallDelta = Math.abs(wallsClassic - wallsBsp)
                / Math.max(1.0, wallsClassic);
            final boolean ok = worstArea < 0.02 && wallDelta < 0.02;
            System.out.printf("%-6s: area %.0f vs %.0f (worst sector %d %.2f%%), "
                    + "walls %.0f vs %.0f (%.2f%%) %s%n",
                l.name(), areaClassic, areaBsp, worstSec, worstArea * 100,
                wallsClassic, wallsBsp, wallDelta * 100, ok ? "PASS" : "FAIL");
            if (ok) {
                pass++;
            } else {
                fail++;
            }
        }
        System.out.println();
        System.out.println("=== " + pass + "/" + (pass + fail) + " maps PASS ===");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static double area(double[] tris) {
        double sum = 0;
        for (int i = 0; i + 5 < tris.length; i += 6) {
            sum += Math.abs((tris[i + 2] - tris[i]) * (tris[i + 5] - tris[i + 1])
                - (tris[i + 4] - tris[i]) * (tris[i + 3] - tris[i + 1])) / 2.0;
        }
        return sum;
    }

    private static double wallLength(DoomMap map, SectorTriangulator.Result tri) {
        double total = 0;
        for (int s = 0; s < map.sectors.size(); s++) {
            final Map<String, float[]> g = LatteMesh.buildFor(map, tri, s, 0, 0);
            for (final float[] v : g.values()) {
                // quads: 4 vertices x 6 floats; a wall quad's horizontal span is the
                // distance between its first two vertices (flat tris have equal heights
                // per triangle and count once in both modes, cancelling out)
                for (int i = 0; i + 23 < v.length; i += 24) {
                    // flats ride as degenerate quads (4th vertex repeats the 3rd) and
                    // the two cutting schemes slice them differently — walls only here
                    if (v[i + 18] == v[i + 12] && v[i + 19] == v[i + 13]
                        && v[i + 20] == v[i + 14]) {
                        continue;
                    }
                    final double dx = v[i + 6] - v[i];
                    final double dz = v[i + 8] - v[i + 2];
                    total += Math.hypot(dx, dz);
                }
            }
        }
        return total;
    }

    private BspProbe() {}
}
