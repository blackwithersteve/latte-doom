package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.blackwithersteve.lattedoom.engine.WorldSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Savegame round trip through the engine's own G_SaveGame/G_LoadGame on a per-set
 * folder: warp in, save to slot 0, verify the .dsg lands carrying our description,
 * load it back and verify a fresh level instance publishes (the same signal the
 * client's load-delivery waits on).
 *
 * Run: gradlew saveProbe -Pwad=...
 */
public final class SaveProbe {

    public static void main(String[] args) throws Exception {
        final Path iwad = Path.of(args[0]);
        final Path data = Path.of("doom-data");
        final String setKey = "probe-set";
        final Path dsg = data.resolve("saves").resolve(setKey).resolve("doomsav0.dsg");
        Files.deleteIfExists(dsg);

        final DoomHost host = DoomHost.boot(iwad, data,
            List.of("-warp", "11", "-skill", "3", "-nomusic", "-nosfx"),
            () -> System.out.println("[save] engine quit"),
            t -> { System.out.println("[save] engine CRASHED: " + t); t.printStackTrace(); },
            setKey, -1);

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
        final long epoch1 = s.levelEpoch;
        System.out.printf("[save] level standing (epoch %d), requesting save%n", epoch1);
        host.requestSave(0, "PROBE SAVE");

        deadline = System.currentTimeMillis() + 15_000;
        while (!Files.exists(dsg) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        if (!Files.exists(dsg)) {
            System.out.println("RESULT: FAIL, no .dsg written at " + dsg);
            System.exit(4);
        }
        Thread.sleep(500); // let the writer finish the file
        final byte[] head = Files.readAllBytes(dsg);
        final StringBuilder desc = new StringBuilder();
        for (int i = 0; i < 24 && i < head.length && head[i] != 0; i++) {
            desc.append((char) (head[i] & 0xFF));
        }
        System.out.printf("[save] wrote %d bytes, description \"%s\"%n", head.length, desc);
        if (!desc.toString().startsWith("PROBE SAVE")) {
            System.out.println("RESULT: FAIL, header description mismatch");
            System.exit(5);
        }

        System.out.println("[save] loading the save back");
        host.requestLoad(0);
        deadline = System.currentTimeMillis() + 20_000;
        WorldSnapshot s2 = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            s2 = host.worldSnapshot();
            if (s2 != null && s2.levelEpoch != epoch1 && s2.mobjCount > 0) {
                break;
            }
        }
        if (s2 == null || s2.levelEpoch == epoch1) {
            System.out.println("RESULT: FAIL, load produced no fresh level instance");
            System.exit(6);
        }
        System.out.printf("[save] loaded: fresh instance (epoch %d), E%dM%d, mobjs=%d%n",
            s2.levelEpoch, s2.episode, s2.map, s2.mobjCount);
        System.out.println("RESULT: PASS");
        System.exit(0);
    }

    private SaveProbe() {
    }
}
