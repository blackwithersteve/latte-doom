package com.blackwithersteve.lattedoom.render;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Frame-cost instrumentation behind /stats. Phase accumulators only, never per vertex.
 * The submit lambdas execute after submit() returns, through the deferred collector, so
 * their time accumulates into fields that the next frame's roll folds in. Switched off it
 * costs one boolean test per bracket. Every 200 frames the rolling line goes to the log
 * and the overlay.
 */
public final class LatteFrameStats {

    public static volatile boolean on;

    private static final int WINDOW = 200;
    private static long frameStart;
    private static long flattenNs, lambdaNs, skyNs, spriteNs, syncNs, persistNs;
    private static long vertices, batches;
    private static int drawnSectors, culledSectors;
    private static long wFlatten, wLambda, wSky, wSprite, wSync, wPersist, wVerts, wBatches;
    private static final long[] frameNs = new long[WINDOW];
    private static int frames;
    // persist-path draw counts (last frame) + which path actually drew
    private static int pMerged, pIndiv, pCulled;
    private static String mode = "classic";

    /** Roll the previous frame and open a new one. Called once per world submit. */
    public static void frame() {
        if (!on) {
            frameStart = 0;
            return;
        }
        final long now = System.nanoTime();
        if (frameStart != 0) {
            final long delta = now - frameStart;
            if (delta < 1_000_000_000L) { // menu/pause gaps are not frames
                frameNs[frames % WINDOW] = delta;
                wFlatten += flattenNs;
                wLambda += lambdaNs;
                wSky += skyNs;
                wSprite += spriteNs;
                wSync += syncNs;
                wPersist += persistNs;
                wVerts += vertices;
                wBatches += batches;
                if (++frames % WINDOW == 0) {
                    report();
                }
            }
        }
        frameStart = now;
        flattenNs = lambdaNs = skyNs = spriteNs = syncNs = persistNs = 0;
        vertices = 0;
        batches = 0;
        if (!LatteSectorBuffers.ENABLED) {
            mode = "classic";
        }
    }

    private static void report() {
        final long[] sorted = frameNs.clone();
        java.util.Arrays.sort(sorted);
        long sum = 0;
        for (long x : sorted) {
            sum += x;
        }
        final double avgMs = sum / (double) WINDOW / 1e6;
        String line = String.format(
            "%s | %.1fms p99 %.1f (%.0f fps) | flat %.2f sub %.2f sky %.2f spr %.2f sync %.2f pers %.2f | %dk v %d b | %d/%d sec",
            mode, avgMs, sorted[WINDOW - 2] / 1e6, avgMs > 0 ? 1000.0 / avgMs : 0,
            wFlatten / (double) WINDOW / 1e6, wLambda / (double) WINDOW / 1e6,
            wSky / (double) WINDOW / 1e6, wSprite / (double) WINDOW / 1e6,
            wSync / (double) WINDOW / 1e6, wPersist / (double) WINDOW / 1e6,
            wVerts / WINDOW / 1000, wBatches / WINDOW,
            drawnSectors, drawnSectors + culledSectors);
        if (!"classic".equals(mode)) {
            line += String.format(" | draws %dm+%di/%dc", pMerged, pIndiv, pCulled);
        }
        System.out.println("[lattedoom stats] " + line);
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal(line));
        }
        wFlatten = wLambda = wSky = wSprite = wSync = wPersist = wVerts = wBatches = 0;
    }

    public static long t() {
        return on ? System.nanoTime() : 0L;
    }

    public static void flatten(long t0) {
        if (t0 != 0) {
            flattenNs += System.nanoTime() - t0;
        }
    }

    public static void lambda(long t0, int verts) {
        if (t0 != 0) {
            lambdaNs += System.nanoTime() - t0;
            vertices += verts;
            batches++;
        }
    }

    public static void sky(long t0) {
        if (t0 != 0) {
            skyNs += System.nanoTime() - t0;
        }
    }

    public static void sprites(long t0) {
        if (t0 != 0) {
            spriteNs += System.nanoTime() - t0;
        }
    }

    public static void sync(long t0) {
        if (t0 != 0) {
            syncNs += System.nanoTime() - t0;
        }
    }

    public static void persist(long t0) {
        if (t0 != 0) {
            persistNs += System.nanoTime() - t0;
        }
    }

    public static void sectors(int drawn, int culled) {
        if (on) {
            drawnSectors = drawn;
            culledSectors = culled;
        }
    }

    /** Persist draw counts for this frame: merged draws, evicted-sector draws,
     * evicted sectors culled; lit = the live-light pipeline actually bound. */
    public static void persistDraws(int merged, int indiv, int culled, boolean lit) {
        if (on) {
            pMerged = merged;
            pIndiv = indiv;
            pCulled = culled;
            mode = lit ? "persist-lit" : "persist-flat";
        }
    }

    private LatteFrameStats() {
    }
}
