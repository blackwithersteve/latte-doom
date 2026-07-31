package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.LatteMesh;
import com.blackwithersteve.lattedoom.render.SectorTriangulator;
import com.blackwithersteve.lattedoom.render.WadFile;

import java.nio.file.Path;
import java.util.Map;

/**
 * Client-side half of the E1M8 diagnostic: the engine provably lowers sector 30's floor
 * (E1m8Probe), so this bakes that sector and its wall-sharing neighbors the way the
 * renderer does — static at the start height, interpolated mid-glide, static at the end —
 * and compares the outputs. Whatever the glide bake does differently from the static bake
 * is what the player sees while the walls come down.
 *
 * Run: gradlew meshProbe -Pwad=/path/to/DOOM.WAD [-Pmap=E1M8] [-Psector=30]
 */
public final class MeshGlideProbe {

    public static void main(String[] args) throws Exception {
        final Path iwad = Path.of(args[0]);
        final String mapName = args.length > 1 ? args[1] : "E1M8";
        final int sector = args.length > 2 ? Integer.parseInt(args[2]) : 30;

        // headless: no texture registry exists, and an unregistered texture drops its
        // quads — answer a fixed size for everything so the geometry paths run for real
        LatteMesh.setTexSize(k -> new int[]{64, 128});

        final WadFile wad = WadFile.read(iwad);
        final DoomMap map = DoomMap.loadFromWad(wad, mapName);
        final SectorTriangulator.Result tri = SectorTriangulator.triangulate(map);
        final double cx = 0, cy = 0;

        final double startF = map.floorNow(sector);
        final double endF = 5;
        System.out.printf("[mesh] %s sector %d: floor %.0f (drops to %.0f in play)%n",
            mapName, sector, startF, endF);

        // the neighbors whose walls face the moving sector
        final java.util.Set<Integer> secs = new java.util.TreeSet<>();
        secs.add(sector);
        for (final DoomMap.Line l : map.lines) {
            if (l.frontSector() == sector && l.backSector() >= 0) {
                secs.add(l.backSector());
            }
            if (l.backSector() == sector && l.frontSector() >= 0) {
                secs.add(l.frontSector());
            }
        }
        System.out.println("[mesh] baking sectors " + secs);

        for (final int s : secs) {
            final Map<String, float[]> stat = LatteMesh.buildFor(map, tri, s, cx, cy);
            for (final double a : new double[]{0.0, 0.5, 1.0}) {
                final LatteMesh.HeightFn hf = new LatteMesh.HeightFn() {
                    public double floor(int s2) {
                        return s2 == sector ? startF + (endF - startF) * a : map.floorNow(s2);
                    }

                    public double ceil(int s2) {
                        return map.ceilNow(s2);
                    }
                };
                final Map<String, float[]> glide =
                    LatteMesh.buildForInterp(map, tri, s, cx, cy, hf);
                if (a == 0.0) {
                    // at a=0 the glide bake must equal the static bake exactly
                    final String diff = diff(stat, glide);
                    System.out.printf("[mesh] s%d a=0.0 vs static: %s%n", s,
                        diff == null ? "IDENTICAL" : "DIFFERS - " + diff);
                } else {
                    System.out.printf("[mesh] s%d a=%.1f: %s%n", s, a, summary(glide));
                }
            }
        }
    }

    private static String summary(Map<String, float[]> g) {
        int quads = 0;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (final float[] v : g.values()) {
            quads += v.length / 24;
            for (int i = 0; i + 5 < v.length; i += 6) {
                minY = Math.min(minY, v[i + 1]);
                maxY = Math.max(maxY, v[i + 1]);
            }
        }
        return String.format("%d textures, %d quads, y %.2f..%.2f", g.size(), quads, minY, maxY);
    }

    private static String diff(Map<String, float[]> a, Map<String, float[]> b) {
        if (!a.keySet().equals(b.keySet())) {
            final java.util.Set<String> onlyA = new java.util.TreeSet<>(a.keySet());
            onlyA.removeAll(b.keySet());
            final java.util.Set<String> onlyB = new java.util.TreeSet<>(b.keySet());
            onlyB.removeAll(a.keySet());
            return "textures only-static=" + onlyA + " only-glide=" + onlyB;
        }
        for (final String k : a.keySet()) {
            final float[] va = a.get(k);
            final float[] vb = b.get(k);
            if (va.length != vb.length) {
                return k + " vertex count " + va.length + " vs " + vb.length;
            }
            for (int i = 0; i < va.length; i++) {
                if (Math.abs(va[i] - vb[i]) > 1e-4) {
                    return String.format("%s differs at float %d (%.4f vs %.4f)",
                        k, i, va[i], vb[i]);
                }
            }
        }
        return null;
    }

    private MeshGlideProbe() {}
}
