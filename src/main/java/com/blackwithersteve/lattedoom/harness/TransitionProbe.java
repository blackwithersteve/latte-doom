package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.blackwithersteve.lattedoom.engine.WorldSnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * Level-advance publication proof. Drives the engine through several level exits
 * and polls the published phase/snapshot pair the whole way, asserting the
 * invariant the client depends on: a published phase of "in a level" always comes
 * with a same-tic snapshot. The live gamestate flips to GS_LEVEL before
 * P_SetupLevel builds the level, and a client observing that window tore the
 * world down mid-advance — the level-finish ejection. The tic-boundary
 * publication makes the window unobservable; this probe watches for it across
 * real advances at full engine speed.
 *
 * Run: gradlew transitionProbe -Pwad=<absolute path to DOOM.WAD>
 */
public final class TransitionProbe {

    private static final int ADVANCES = 3;

    public static void main(String[] args) throws Exception {
        final Path iwad = Path.of(args[0]);
        final DoomHost host = DoomHost.boot(iwad, Path.of("doom-data"),
            List.of("-warp", "11", "-skill", "3", "-nomusic", "-nosfx"),
            () -> System.out.println("[trans] engine quit"),
            t -> { System.out.println("[trans] engine CRASHED: " + t); t.printStackTrace(); });

        long deadline = System.currentTimeMillis() + 30_000;
        while (host.state() == DoomHost.State.BOOTING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        WorldSnapshot s = null;
        deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            s = host.worldSnapshot();
            if (s != null && s.mobjCount > 0) {
                break;
            }
        }
        if (s == null) {
            System.out.println("RESULT: FAIL, first map never stood");
            System.exit(2);
        }

        long epoch = s.levelEpoch;
        int violations = 0;
        for (int adv = 1; adv <= ADVANCES; adv++) {
            System.out.printf("[trans] advance %d: exiting E%dM%d (epoch %d)%n",
                adv, s.episode, s.map, epoch);
            host.requestExitLevel();

            boolean sawBetween = false;
            final long stop = System.currentTimeMillis() + 30_000;
            long nextPress = 0;
            WorldSnapshot fresh = null;
            while (System.currentTimeMillis() < stop) {
                // invariant check under double-read: if the phase is stable across
                // the snapshot read and says "in a level", the snapshot must exist
                final int k1 = host.gamestateKind();
                final WorldSnapshot now = host.worldSnapshot();
                final int k2 = host.gamestateKind();
                if (k1 == 0 && k2 == 0 && now == null && host.hadLevel()) {
                    violations++;
                    System.out.println("[trans] VIOLATION: phase says level, snapshot null");
                }
                if (k1 == 1 || k1 == 2) {
                    sawBetween = true;
                    // the tally waits for a press, exactly like the live screens
                    // forward one — tap SPACE through it
                    if (System.currentTimeMillis() >= nextPress) {
                        host.postKey(g.Signals.ScanCode.SC_SPACE, true);
                        Thread.sleep(60);
                        host.postKey(g.Signals.ScanCode.SC_SPACE, false);
                        nextPress = System.currentTimeMillis() + 400;
                    }
                }
                if (now != null && now.levelEpoch != epoch) {
                    fresh = now;
                    break;
                }
                Thread.sleep(2);
            }
            if (fresh == null) {
                System.out.println("RESULT: FAIL, advance " + adv + " never produced a new level");
                System.exit(3);
            }
            if (fresh.levelEpoch <= epoch) {
                System.out.println("RESULT: FAIL, epoch did not increase");
                System.exit(4);
            }
            System.out.printf("[trans] arrived E%dM%d (epoch %d, sawBetween=%s)%n",
                fresh.episode, fresh.map, fresh.levelEpoch, sawBetween);
            s = fresh;
            epoch = fresh.levelEpoch;
        }

        if (violations > 0) {
            System.out.println("RESULT: FAIL, " + violations + " publication violations");
            System.exit(5);
        }
        System.out.println("RESULT: PASS");
        System.exit(0);
    }

    private TransitionProbe() {
    }
}
