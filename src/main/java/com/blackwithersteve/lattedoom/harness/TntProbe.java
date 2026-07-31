package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import com.blackwithersteve.lattedoom.render.LatteSprites;

import java.nio.file.Path;
import java.util.List;

/**
 * TNT MAP30 diagnostic. Two bugs reported on this map share a probe because they share a
 * boot: the red gate ignoring USE, and the pad-grid teleport that lands on the voodoo doll
 * and must kill the player rather than strand them.
 *
 * Map facts, read from the WAD directory: two player-1 starts (the extra one is the doll,
 * at 2080,0); every pad in the start grid is ringed by WR teleport lines (special 97)
 * targeting tag 8, the doll's 64x64 sector 186; the only keyed line is 1456, a D1 red
 * door (special 33) from (-896,-1056) to (-1024,-1056) with door sector 265.
 *
 * Run: gradlew tntProbe -Pwad=/path/to/TNT.WAD
 */
public final class TntProbe {

    private static final int DOOR_LINE = 1456;
    private static final int DOOR_SECTOR = 265;
    private static final double DOOR_X = -960;
    private static final double DOOR_Y = -1056;
    private static final int PAD_LINE = 859;      // (-4800,-320)..(-4608,-320), pad to its north
    private static final double PAD_X = -4704;
    private static final double DOLL_X = 2080;
    private static final double DOLL_Y = 0;

    public static void main(String[] args) throws Exception {
        final Path iwad = Path.of(args[0]);
        // extra arguments become -file entries, so WAD combinations can be probed
        final java.util.List<String> boot = new java.util.ArrayList<>(
            List.of("-warp", "30", "-skill", "4", "-nomusic", "-nomonsters"));
        if (args.length > 1) {
            boot.add("-file");
            for (int i = 1; i < args.length; i++) {
                boot.add(args[i]);
            }
        }
        final DoomHost host = DoomHost.boot(iwad, Path.of("doom-data"), boot,
            () -> System.out.println("[tnt] engine quit"),
            t -> { System.out.println("[tnt] engine CRASHED: " + t); t.printStackTrace(); });

        long deadline = System.currentTimeMillis() + 30_000;
        while (host.state() == DoomHost.State.BOOTING && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (host.state() != DoomHost.State.RUNNING) {
            System.out.println("RESULT: FAIL, engine did not reach RUNNING");
            System.exit(2);
        }
        WorldSnapshot s = null;
        deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            s = host.worldSnapshot();
            if (s != null && s.mobjCount > 0 && s.map == 30) {
                break;
            }
        }
        if (s == null || s.map != 30) {
            System.out.println("RESULT: FAIL, MAP30 did not load");
            System.exit(3);
        }
        final int pm = s.playerMobj;
        System.out.printf("[tnt] MAP30 loaded, tic=%d mobjs=%d player at (%.0f,%.0f)%n",
            s.tic, s.mobjCount, s.mx[pm], s.my[pm]);
        if (s.floorH.length <= DOOR_SECTOR) {
            // some other WAD's MAP30: the boot itself succeeded, which is all a
            // non-TNT run can prove — the geometry assertions are TNT's
            System.out.println("RESULT: BOOT OK (not TNT MAP30; geometry checks skipped)");
            host.terminate();
            System.exit(0);
        }

        // The doll: the PLAY-sprite mobj that is not the player
        int doll = -1;
        for (int i = 0; i < s.mobjCount; i++) {
            if (i != pm && "PLAY".equals(LatteSprites.spriteName(s.mSprite[i]))) {
                doll = i;
                break;
            }
        }
        System.out.printf("[tnt] doll mobj: %s%n", doll < 0 ? "NOT FOUND"
            : String.format("at (%.0f,%.0f) frame=%d solid=%s",
                s.mx[doll], s.my[doll], s.mFrame[doll], s.mSolid[doll]));
        final int dollId = doll >= 0 ? s.mId[doll] : -1;

        // possess at the spawn
        final long epoch = s.levelEpoch;
        mirror(host, s.mx[pm], s.my[pm], s.mz[pm], 90, epoch);
        host.setPlayerHealth(100);
        Thread.sleep(500);

        // ---- TEST B: the red gate. All keys, then USE at descending distances, first from
        // the south (the approach side), then the north. D1 fires once, so the first
        // success both ends the sweep and reports the maximum working distance.
        host.typeCheat("idkfa");
        Thread.sleep(700);
        s = host.worldSnapshot();
        System.out.printf("[tnt] cards after idkfa: %s%n", java.util.Arrays.toString(s.cards));
        System.out.printf("[tnt] door sector %d: floor=%.0f ceil=%.0f (closed if equal)%n",
            DOOR_SECTOR, s.floorH[DOOR_SECTOR], s.ceilH[DOOR_SECTOR]);

        double opened = -1;
        String openedFrom = null;
        outer:
        for (String side : new String[]{"south", "north"}) {
            for (double dist : new double[]{88, 72, 56, 40, 24}) {
                final double y = "south".equals(side) ? DOOR_Y - dist : DOOR_Y + dist;
                final double ang = "south".equals(side) ? 90 : 270;
                mirror(host, DOOR_X, y, 0, ang, epoch);
                host.setPlayerHealth(100);
                Thread.sleep(300);
                host.requestUse();
                Thread.sleep(500);
                s = host.worldSnapshot();
                final boolean open = s.ceilH[DOOR_SECTOR] > s.floorH[DOOR_SECTOR] + 1;
                System.out.printf("[tnt] use from %s at %daway: door ceil=%.0f -> %s%n",
                    side, (int) dist, s.ceilH[DOOR_SECTOR], open ? "OPEN" : "shut");
                if (open) {
                    opened = dist;
                    openedFrom = side;
                    break outer;
                }
            }
        }
        System.out.println("[tnt] TEST B: door opened "
            + (openedFrom == null ? "NEVER (engine-side use bug)"
                : "from the " + openedFrom + " at " + (int) opened + "u"));

        // ---- TEST A: the pad teleport. Walk the mirror across the pad's south line and
        // fire the crossing the way the client would; then follow the teleport and total
        // the damage bill the host wants Minecraft to pay.
        host.drainDamage();
        host.drainHeal();
        s = host.worldSnapshot();
        final int tc0 = s.teleportCount;
        double px = PAD_X, py = -344;
        mirror(host, px, py, 0, 90, epoch);
        host.setPlayerHealth(100);
        Thread.sleep(400);
        int side = 0;
        int tcNow = tc0;
        for (int attempt = 0; attempt < 2 && tcNow == tc0; attempt++) {
            for (double y = -344; y <= -296; y += 8) {
                mirror(host, px, y, 0, 90, epoch);
                host.setPlayerHealth(100);
                if (y >= -320 && y - 8 < -320) {
                    host.requestCross(PAD_LINE, side);
                }
                Thread.sleep(60);
            }
            Thread.sleep(400);
            s = host.worldSnapshot();
            tcNow = s.teleportCount;
            if (tcNow == tc0) {
                System.out.println("[tnt] no teleport with side=" + side + ", retrying other side");
                side = 1 - side;
                mirror(host, px, -344, 0, 90, epoch);
                Thread.sleep(300);
            }
        }
        s = host.worldSnapshot();
        System.out.printf("[tnt] teleportCount %d -> %d, player mobj at (%.0f,%.0f)%n",
            tc0, s.teleportCount, s.mx[s.playerMobj], s.my[s.playerMobj]);
        final boolean atDoll = Math.abs(s.mx[s.playerMobj] - DOLL_X) < 2
            && Math.abs(s.my[s.playerMobj] - DOLL_Y) < 2;

        // adopt the destination like the client's teleport-follow, then total the bill
        if (s.teleportCount != tc0) {
            mirror(host, s.mx[s.playerMobj], s.my[s.playerMobj], s.mz[s.playerMobj],
                s.mAngleDeg[s.playerMobj], epoch);
        }
        int bill = 0;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            host.setPlayerHealth(100);
            bill += host.drainDamage();
        }
        int dollNow = -1;
        s = host.worldSnapshot();
        for (int i = 0; i < s.mobjCount; i++) {
            if (s.mId[i] == dollId) {
                dollNow = i;
                break;
            }
        }
        System.out.printf("[tnt] damage bill after stomp: %d doom points%n", bill);
        System.out.printf("[tnt] doll after stomp: %s%n", dollNow < 0 ? "gone"
            : String.format("frame=%d solid=%s", s.mFrame[dollNow], s.mSolid[dollNow]));

        System.out.println();
        System.out.println("RESULT: door=" + (openedFrom != null ? "opens from " + openedFrom
                + " at " + (int) opened + "u" : "NEVER OPENS")
            + "; teleport=" + (s.teleportCount != tc0) + " landedOnDoll=" + atDoll
            + "; bill=" + bill
            + (bill > 1000 ? " (EXCEEDS the 1000 cap PlayerDamageC2S drops silently)" : ""));
        host.terminate();
        System.exit(0);
    }

    private static void mirror(DoomHost host, double x, double y, double h, double ang,
                               long epoch) {
        host.setPlayerMirror(true, x, y, h, ang, epoch);
    }

    private TntProbe() {}
}
