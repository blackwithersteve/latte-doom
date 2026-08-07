package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.engine.DoomHost;

import java.nio.file.Path;
import java.util.List;

/**
 * HEADS-UP MESSAGE GATE. Boots the engine on the title demo and asserts that pickup
 * messages actually reach {@link DoomHost#drainMessages()}.
 *
 * This exists because the message path failed silently twice. The engine's own HU.Ticker
 * consumes player_t.message and NULLS it, and it runs inside Ticker() — before the tic tap
 * that captures the snapshot. So every message was already gone by the time the port looked
 * at the field, and nothing on the drawing side could have shown one. Nothing in the build
 * noticed, because no gate covered it.
 *
 * Run: gradlew messageProbe -Pwad=&lt;absolute path to an IWAD&gt;
 */
public final class MessageProbe {

    public static void main(String[] args) throws Exception {
        final Path iwad = Path.of(args[0]);
        final DoomHost host = DoomHost.boot(iwad, Path.of("doom-data"),
            List.of("-nomusic", "-nosfx"),
            () -> System.out.println("[msg] engine quit"),
            t -> { System.out.println("[msg] engine CRASHED: " + t); t.printStackTrace(); });

        long deadline = System.currentTimeMillis() + 30_000;
        while (host.state() == DoomHost.State.BOOTING
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        // the attract demo walks a recorded marine through E1M5 collecting things
        final java.util.List<String> seen = new java.util.ArrayList<>();
        deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && seen.size() < 3) {
            Thread.sleep(50);
            for (String m : host.drainMessages()) {
                System.out.println("[msg] " + m);
                seen.add(m);
            }
        }

        if (seen.isEmpty()) {
            System.out.println("RESULT: FAIL, no heads-up message reached drainMessages()");
            System.exit(2);
        }
        System.out.println("RESULT: PASS, " + seen.size() + " message(s)");
        System.exit(0);
    }

    private MessageProbe() {
    }
}
