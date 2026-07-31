package com.blackwithersteve.lattedoom.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Flats from the BSP's own leaves: every subsector polygon (already clipped through the
 * node planes and healed of T-junctions) fan-triangulated under the sector the BSP
 * assigns it. This is the software renderer's ground truth — on a trick map a leaf can
 * belong to a different sector than the surrounding records suggest, and drawing the
 * leaves is what makes self-referencing constructions come out the way 1993 shows them.
 * Produces the same {@link SectorTriangulator.Result} shape, so every mesh path and
 * height function works unchanged on either source.
 */
public final class BspTriangulator {

    public static SectorTriangulator.Result triangulate(DoomMap map) {
        final int n = map.sectors.size();
        final List<List<double[]>> tris = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tris.add(new ArrayList<>());
        }
        for (final DoomMap.SubPoly sp : map.subsectorPolys()) {
            if (sp.sector() < 0 || sp.sector() >= n) {
                continue;
            }
            final double[] xs = sp.xs();
            final double[] ys = sp.ys();
            if (xs.length < 3) {
                continue;
            }
            // consistent winding: fan from vertex 0, flipped when the ring is clockwise
            double area2 = 0;
            for (int i = 0; i < xs.length; i++) {
                final int j = (i + 1) % xs.length;
                area2 += xs[i] * ys[j] - xs[j] * ys[i];
            }
            final boolean flip = area2 < 0;
            for (int i = 1; i + 1 < xs.length; i++) {
                final int a = 0;
                final int b = flip ? i + 1 : i;
                final int c = flip ? i : i + 1;
                tris.get(sp.sector()).add(new double[]{
                    xs[a], ys[a], xs[b], ys[b], xs[c], ys[c]});
            }
        }
        final double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            final List<double[]> list = tris.get(i);
            final double[] flat = new double[list.size() * 6];
            for (int t = 0; t < list.size(); t++) {
                System.arraycopy(list.get(t), 0, flat, t * 6, 6);
            }
            out[i] = flat;
        }
        return new SectorTriangulator.Result(out, new boolean[n], List.of());
    }

    private BspTriangulator() {}
}
