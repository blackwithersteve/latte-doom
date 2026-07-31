package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.play.DoomCollision;
import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.WadFile;

import java.nio.file.Path;
import java.util.List;

/**
 * Headless gate for the player collision port: from every map's P1 start, sprint 120 tics
 * in each of 8 directions at run momentum. PASS requires, on every single tic: the marine
 * stays in a real sector (never the void behind a one-sided wall), grounded feet sit
 * exactly on the aggregate floor, no single-tic climb exceeds STEP_UP, and coordinates
 * stay finite. Fresh verification — no inherited claims.
 */
public final class MoveProbe {

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
        double sx = 0, sy = 0;
        boolean found = false;
        for (DoomMap.Thing t : map.things) {
            if (t.type() == 1) {
                sx = t.x();
                sy = t.y();
                found = true;
                break;
            }
        }
        if (!found) {
            System.out.printf("%-6s: no P1 start FAIL%n", mapName);
            return false;
        }
        int voidTics = 0, floorGaps = 0, bigSteps = 0, nans = 0;
        final double runMom = 16.67; // u/tic, the friction-equilibrium sprint speed
        for (int dir = 0; dir < 8; dir++) {
            final double a = Math.toRadians(dir * 45);
            final double mx = Math.cos(a) * runMom, my = Math.sin(a) * runMom;
            final int startSec = map.sectorAt(sx, sy);
            double x = sx, y = sy;
            double h = startSec >= 0 ? map.floorNow(startSec) : 0;
            double lastH = h;
            for (int tic = 0; tic < 120; tic++) {
                final DoomCollision.Result r = DoomCollision.move(map, x, y, h, mx, my, 0);
                x = r.x();
                y = r.y();
                h = r.h();
                if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(h)) {
                    nans++;
                    break;
                }
                if (map.sectorAt(x, y) < 0) {
                    voidTics++;
                }
                if (r.onGround() && Math.abs(h - r.floorZ()) > 0.01) {
                    floorGaps++;
                }
                if (h - lastH > DoomCollision.STEP_UP + 0.01) {
                    bigSteps++;
                }
                lastH = h;
            }
        }
        final boolean pass = voidTics == 0 && floorGaps == 0 && bigSteps == 0 && nans == 0;
        System.out.printf("%-6s: void=%d floorGap=%d overStep=%d nan=%d %s%n",
            mapName, voidTics, floorGaps, bigSteps, nans, pass ? "PASS" : "FAIL");
        return pass;
    }

    private MoveProbe() {}
}
