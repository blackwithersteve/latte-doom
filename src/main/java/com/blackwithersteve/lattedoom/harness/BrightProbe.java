package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.blackwithersteve.lattedoom.engine.WorldSnapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end check that the FF_FULLBRIGHT bit survives capture. Kills a barrel through
 * the real damage pipeline, then for every explosion-family mobj compares the engine's
 * live mobj_frame bright bit against the snapshot's mFrame for the same mobj. This
 * locates where the bit is lost: the engine tables, the capture, or the client.
 *
 * Run: gradlew brightProbe -Pwad=&lt;absolute path to an IWAD&gt;
 */
public final class BrightProbe {

    public static void main(String[] args) throws Exception {
        final Path iwad = Path.of(args[0]);
        final DoomHost host = DoomHost.boot(iwad, Path.of("doom-data"),
            new java.util.ArrayList<>(List.of("-warp", "23", "-skill", "4", "-nomusic")),
            () -> System.out.println("[bright] engine quit"),
            t -> { System.out.println("[bright] engine CRASHED: " + t); t.printStackTrace(); });

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
        host.setPlayerMirror(true, s.mx[s.playerMobj], s.my[s.playerMobj],
            s.mz[s.playerMobj], 90, s.levelEpoch);
        host.setPlayerHealth(100);

        // engine-thread tap: kill one barrel, then log every explosion-family
        // mobj's live frame bits each tic
        final AtomicBoolean killed = new AtomicBoolean();
        final StringBuilder log = new StringBuilder();
        final int[] seenEngineBright = {0};
        final int[] seenEngineDark = {0};
        final Runnable original = mochadoom.Engine.TIC_TAP;
        mochadoom.Engine.TIC_TAP = () -> {
            try {
                final doom.DoomMain<?, ?> d = mochadoom.Engine.getEngine().getDOOM();
                if (!killed.getAndSet(true)) {
                    for (doom.thinker_t th = d.actions.getThinkerCap().next;
                         th != d.actions.getThinkerCap(); th = th.next) {
                        if (th instanceof p.mobj_t mo && mo.type == data.mobjtype_t.MT_BARREL
                            && mo.health > 0) {
                            d.actions.DamageMobj(mo, null, null, 10000);
                            System.out.println("[bright] barrel killed at ("
                                + (mo.x >> 16) + "," + (mo.y >> 16) + ")");
                            break;
                        }
                    }
                }
                for (doom.thinker_t th = d.actions.getThinkerCap().next;
                     th != d.actions.getThinkerCap(); th = th.next) {
                    if (!(th instanceof p.mobj_t mo) || mo.mobj_sprite == null) {
                        continue;
                    }
                    final String sp = mo.mobj_sprite.name();
                    if (sp.equals("SPR_BEXP") || sp.equals("SPR_MISL")
                        || sp.equals("SPR_FIRE") || sp.equals("SPR_BAR1")) {
                        final boolean b = (mo.mobj_frame & 0x8000) != 0;
                        if (b) {
                            seenEngineBright[0]++;
                        } else {
                            seenEngineDark[0]++;
                        }
                        synchronized (log) {
                            log.append(String.format("tic=%d %s frame=0x%X bright=%s id=%d%n",
                                d.gametic, sp, mo.mobj_frame, b, System.identityHashCode(mo)));
                        }
                    }
                }
            } catch (Throwable t) {
                System.out.println("[bright] tap failed: " + t);
            }
            original.run();
        };

        // sample snapshots for ~4s and cross-check the same mobjs' captured bits
        int snapBright = 0;
        int snapDark = 0;
        final StringBuilder snapLog = new StringBuilder();
        deadline = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            host.setPlayerHealth(100);
            s = host.worldSnapshot();
            if (s == null) {
                continue;
            }
            for (int i = 0; i < s.mobjCount; i++) {
                final String nm = com.blackwithersteve.lattedoom.render.LatteSprites
                    .spriteName(s.mSprite[i]);
                if ("BEXP".equals(nm) || "FIRE".equals(nm)
                    || ("MISL".equals(nm) && (s.mFrame[i] & 0x7FFF) >= 1)) {
                    final boolean b = (s.mFrame[i] & 0x8000) != 0;
                    if (b) {
                        snapBright++;
                    } else {
                        snapDark++;
                    }
                    snapLog.append(String.format("tic=%d SNAP %s mFrame=0x%X bright=%s id=%d%n",
                        s.tic, nm, s.mFrame[i], b, s.mId[i]));
                }
            }
        }
        synchronized (log) {
            System.out.print(log);
        }
        System.out.print(snapLog);
        System.out.printf("[bright] engine sightings: bright=%d dark=%d | snapshot: bright=%d dark=%d%n",
            seenEngineBright[0], seenEngineDark[0], snapBright, snapDark);
        final boolean pass = seenEngineBright[0] > 0 && snapBright > 0 && snapDark == 0;
        System.out.println("RESULT: " + (pass
            ? "PASS, bright bit survives engine -> snapshot"
            : "FAIL, bit lost (engine bright=" + seenEngineBright[0]
              + " dark=" + seenEngineDark[0] + ", snap bright=" + snapBright
              + " dark=" + snapDark + ")"));
        host.terminate();
        System.exit(pass ? 0 : 1);
    }

    private BrightProbe() {}
}
