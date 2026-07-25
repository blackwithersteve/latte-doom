package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.play.DoomCollision;
import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.WadFile;

import java.nio.file.Path;
import java.util.List;

/**
 * Collision gate: from every map's player-1 start, runs 120 tics in each of eight directions
 * at running speed. A map passes only if, every tic, the player stays inside a real sector,
 * grounded feet sit on the floor, no climb exceeds {@link DoomCollision#STEP_UP} and all
 * coordinates stay finite.
 *
 * <p>{@code ./gradlew moveProbe -Pwad=/path/to/DOOM.WAD [-Pmap=E1M1]}
 */
public final class MoveProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].isEmpty()) {
            System.err.println("MoveProbe: pass a WAD path (gradle: -Pwad=/path/to/DOOM.WAD)");
            System.exit(2);
        }
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
