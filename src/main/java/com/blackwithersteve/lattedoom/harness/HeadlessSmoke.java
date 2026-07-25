package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import g.Signals.ScanCode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Boots the engine without Minecraft and dumps demo frames as PNGs, exercising WAD loading,
 * the renderer, frame publishing, input injection and freeze/resume.
 *
 * <p>{@code ./gradlew doomSmoke -Pwad=...}, or the {@code DOOM_WAD} environment variable, or
 * {@code DOOM.WAD} in the working directory.
 */
public final class HeadlessSmoke {

    public static void main(String[] args) throws Exception {
        final String env = System.getenv("DOOM_WAD");
        final Path iwad = Path.of(args.length > 0 && !args[0].isEmpty() ? args[0]
            : (env != null && !env.isEmpty() ? env : "DOOM.WAD"));
        final Path frames = Path.of("frames");
        Files.createDirectories(frames);

        System.out.println("=== Latte Doom headless smoke: " + iwad + " ===");
        final DoomHost host = DoomHost.boot(iwad, Path.of("doom-data"), List.of("-nomusic"),
            () -> System.out.println("[smoke] engine quit normally"),
            t -> System.out.println("[smoke] engine crashed: " + t));

        final long deadline = System.currentTimeMillis() + 30_000;
        while (host.state() == DoomHost.State.BOOTING && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        System.out.println("[smoke] state=" + host.state() + " video=" + host.width() + "x" + host.height());
        if (host.state() != DoomHost.State.RUNNING) {
            System.exit(2);
        }

        final int w = host.width(), h = host.height();
        final int[] px = new int[w * h];
        for (int s = 1; s <= 12; s++) {
            Thread.sleep(1000);
            final long n = host.copyFrame(px);
            final BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            bi.setRGB(0, 0, w, h, px, 0, w);
            ImageIO.write(bi, "png", frames.resolve(String.format("frame-%02d.png", s)).toFile());
            System.out.println("[smoke] t=" + s + "s framesPublished=" + n);

            if (s == 6) {
                // Prove input injection: DOOM's menu should be visible in frames 7+.
                host.postKey(ScanCode.SC_ESCAPE, true);
                host.postKey(ScanCode.SC_ESCAPE, false);
            }
        }

        System.out.println("[smoke] freezing engine for 2s...");
        host.setFrozen(true);
        final long frozenAt = host.framesPublished();
        Thread.sleep(2000);
        final long stillFrozen = host.framesPublished();
        host.setFrozen(false);
        Thread.sleep(500);
        final long resumed = host.framesPublished();
        System.out.println("[smoke] freeze check: " + frozenAt + " -> " + stillFrozen
            + " (want equal-ish), resumed -> " + resumed + " (want bigger)");
        final boolean freezeOk = (stillFrozen - frozenAt) <= 1 && resumed > stillFrozen;

        // ---- Snapshot check. The title demo is real gameplay (recorded tics in a real
        // level), so once it starts the snapshot must show a level, moving player
        // coordinates and map objects. ----
        System.out.println("[smoke] waiting for a world snapshot (title demo)...");
        final long snapDeadline = System.currentTimeMillis() + 30_000;
        com.blackwithersteve.lattedoom.engine.WorldSnapshot snap = null;
        while (snap == null && System.currentTimeMillis() < snapDeadline) {
            Thread.sleep(200);
            snap = host.worldSnapshot();
        }
        boolean snapOk = false;
        if (snap == null) {
            System.out.println("[smoke] SNAPSHOT FAIL: none within 30s");
        } else {
            double lastX = snap.px, lastY = snap.py;
            double travelled = 0;
            for (int s = 1; s <= 4; s++) {
                Thread.sleep(1000);
                final com.blackwithersteve.lattedoom.engine.WorldSnapshot ws = host.worldSnapshot();
                if (ws == null) {
                    break; // demo ended mid-sample; evaluate the samples collected so far
                }
                travelled += Math.hypot(ws.px - lastX, ws.py - lastY);
                lastX = ws.px;
                lastY = ws.py;
                System.out.printf("[smoke] snap t=%ds tic=%d E%dM%d player=(%.1f, %.1f, %.1f) ang=%.0f mobjs=%d sectors=%d sec0 floor=%.0f ceil=%.0f light=%d%n",
                    s, ws.tic, ws.episode, ws.map, ws.px, ws.py, ws.pz, ws.angleDeg,
                    ws.mobjCount, ws.floorH.length, ws.floorH[0], ws.ceilH[0], ws.light[0]);
                snap = ws;
            }
            snapOk = travelled > 32 && snap.mobjCount > 5 && snap.floorH.length > 10;
            System.out.printf("[smoke] snapshot check: travelled=%.0f u (want >32), mobjs=%d (want >5), sectors=%d (want >10)%n",
                travelled, snap.mobjCount, snap.floorH.length);
        }

        final boolean ok = freezeOk && snapOk;
        System.out.println(ok ? "[smoke] SMOKE OK"
            : "[smoke] SMOKE FAIL (" + (freezeOk ? "" : "freeze ") + (snapOk ? "" : "snapshot") + ")");
        System.exit(ok ? 0 : 3);
    }

    private HeadlessSmoke() {}
}
