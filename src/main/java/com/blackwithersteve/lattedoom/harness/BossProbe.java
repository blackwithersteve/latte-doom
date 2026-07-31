package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.blackwithersteve.lattedoom.engine.WorldSnapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Boss-special diagnostic, for the whole family: warp to a map, kill every monster of
 * the named type through the engine's own damage pipeline (so A_BossDeath / A_KeenDie
 * run off the authentic death states), and watch which sector heights the snapshot
 * publishes afterwards. Covers E1M8 (barons, floor), E4M6 (cyberdemon, door), E4M8
 * (mastermind, floor), MAP07 (mancubi 666, arachnotrons 667) and the Keen doors.
 *
 * Run: gradlew bossProbe -Pwad=... [-Pwarp=18] [-Ptype=MT_BRUISER] [-Pfile=extra.wad]
 */
public final class BossProbe {

    public static void main(String[] args) throws Exception {
        final Path iwad = Path.of(args[0]);
        final String warp = args.length > 1 ? args[1] : "18";
        final String typeName = args.length > 2 ? args[2] : "MT_BRUISER";
        final data.mobjtype_t type = data.mobjtype_t.valueOf(typeName);
        final java.util.List<String> boot = new java.util.ArrayList<>(
            List.of("-warp", warp, "-skill", "4", "-nomusic"));
        if (args.length > 3) {
            boot.add("-file");
            for (int i = 3; i < args.length; i++) {
                boot.add(args[i]);
            }
        }
        final DoomHost host = DoomHost.boot(iwad, Path.of("doom-data"), boot,
            () -> System.out.println("[boss] engine quit"),
            t -> { System.out.println("[boss] engine CRASHED: " + t); t.printStackTrace(); });

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
            if (s != null && s.mobjCount > 0) {
                break;
            }
        }
        if (s == null) {
            System.out.println("RESULT: FAIL, map did not load");
            System.exit(3);
        }
        System.out.printf("[boss] E%dM%d loaded, mobjs=%d sectors=%d, killing %s%n",
            s.episode, s.map, s.mobjCount, s.floorH.length, typeName);
        final double[] beforeF = s.floorH.clone();
        final double[] beforeC = s.ceilH.clone();

        host.setPlayerMirror(true, s.mx[s.playerMobj], s.my[s.playerMobj],
            s.mz[s.playerMobj], 90, s.levelEpoch);
        host.setPlayerHealth(100);

        final Runnable original = mochadoom.Engine.TIC_TAP;
        final AtomicBoolean done = new AtomicBoolean();
        mochadoom.Engine.TIC_TAP = () -> {
            if (!done.getAndSet(true)) {
                try {
                    final doom.DoomMain<?, ?> d = mochadoom.Engine.getEngine().getDOOM();
                    int killed = 0;
                    for (doom.thinker_t th = d.actions.getThinkerCap().next;
                         th != d.actions.getThinkerCap(); th = th.next) {
                        if (th.thinkerFunction != p.ActiveStates.P_MobjThinker) {
                            continue;
                        }
                        final p.mobj_t mo = (p.mobj_t) th;
                        if (mo.type == type && mo.health > 0) {
                            d.actions.DamageMobj(mo, null, null, 10000);
                            killed++;
                        }
                    }
                    System.out.println("[boss] " + typeName + " killed: " + killed);
                } catch (Throwable t) {
                    System.out.println("[boss] kill failed: " + t);
                }
            }
            original.run();
        };

        for (int sec : new int[]{2, 5, 9}) {
            Thread.sleep(sec == 2 ? 2000 : 3000);
            host.setPlayerHealth(100);
            s = host.worldSnapshot();
            final StringBuilder sb = new StringBuilder();
            int moved = 0;
            for (int i = 0; i < s.floorH.length && i < beforeF.length; i++) {
                if (s.floorH[i] != beforeF[i] || s.ceilH[i] != beforeC[i]) {
                    moved++;
                    if (moved <= 8) {
                        sb.append(String.format(" s%d f%.0f->%.0f c%.0f->%.0f",
                            i, beforeF[i], s.floorH[i], beforeC[i], s.ceilH[i]));
                    }
                }
            }
            System.out.printf("[boss] t=%ds: %d sector(s) changed%s%n", sec, moved, sb);
        }
        int moved = 0;
        for (int i = 0; i < s.floorH.length && i < beforeF.length; i++) {
            if (s.floorH[i] != beforeF[i] || s.ceilH[i] != beforeC[i]) {
                moved++;
            }
        }
        System.out.println("RESULT: " + (moved > 0
            ? "PASS, the boss special moved " + moved + " sector(s)"
            : "FAIL, no sector moved after the boss death"));
        host.terminate();
        System.exit(moved > 0 ? 0 : 1);
    }

    private BossProbe() {}
}
