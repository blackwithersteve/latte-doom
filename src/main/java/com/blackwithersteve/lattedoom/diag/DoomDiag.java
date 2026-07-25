package com.blackwithersteve.lattedoom.diag;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;

/**
 * Ring-buffer recorder for motion problems, which span three clocks (Minecraft's tick, the
 * engine's tic and the frame rate) and are hard to pin down from a single observation.
 *
 * <p>Records positions, floor carries, position writes with their caller and state changes.
 * On an anomaly, a vertical move over one block in a frame, over four blocks in a tick, or a
 * dimension change, the last two seconds are written to {@code logs/lattedoom-diag.log}
 * under the trigger. Always on, one formatted string per event; {@code /doomdiag} dumps on
 * demand.
 */
public final class DoomDiag {

    private static final int CAPACITY = 4000;
    private static final ArrayDeque<String> RING = new ArrayDeque<>(CAPACITY);
    private static long epoch = System.nanoTime();
    private static double lastTickX = Double.NaN, lastTickY, lastTickZ;
    private static double lastFrameY = Double.NaN;
    private static String lastDim = "";
    private static long lastDumpMs;

    /** Records an important event: a command, level transition, engine state change or
     * error: into the ring buffer and writes it straight to the log file, so the log is
     * a complete chronological account even when no anomaly dump fires. */
    public static synchronized void logNow(String lane, String msg) {
        rec(lane, msg);
        try {
            final Path file = FabricLoader.getInstance().getGameDir()
                .resolve("logs").resolve("lattedoom-diag.log");
            Files.createDirectories(file.getParent());
            if (!headerWritten) {
                headerWritten = true;
                Files.writeString(file,
                    System.lineSeparator() + "######## SESSION " + java.time.LocalDateTime.now()
                        + " ########" + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            final double t = (System.nanoTime() - epoch) / 1.0e9;
            Files.writeString(file, String.format("%10.4f %-6s %s%n", t, lane, msg),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static boolean headerWritten;

    /** Records one event line into the ring buffer. The lane is a short tag such as
     * {@code pin}, {@code glue}, {@code tp} or {@code tick}. */
    public static synchronized void rec(String lane, String msg) {
        if (RING.size() >= CAPACITY) {
            RING.pollFirst();
        }
        final double t = (System.nanoTime() - epoch) / 1.0e9;
        RING.addLast(String.format("%10.4f %-6s %s", t, lane, msg));
    }

    /** Records the player's state once per client tick and runs the tick-scale anomaly
     * check (large displacement or a dimension change). */
    public static void tickPlayer(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        final double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        final String dim = mc.player.level().dimension().identifier().toString();
        rec("tick", String.format("pos=(%.2f, %.2f, %.2f) dim=%s ground=%s dm=%.3f",
            x, y, z, dim.substring(dim.indexOf(':') + 1), mc.player.onGround(),
            mc.player.getDeltaMovement().y));
        if (!Double.isNaN(lastTickX)) {
            final double d = Math.abs(x - lastTickX) + Math.abs(y - lastTickY)
                + Math.abs(z - lastTickZ);
            if (!dim.equals(lastDim)) {
                dump("DIMENSION CHANGE " + lastDim + " -> " + dim);
            } else if (d > 4.0) {
                dump(String.format("TICK JUMP %.2f blocks: (%.1f, %.1f, %.1f) -> (%.1f, %.1f, %.1f)",
                    d, lastTickX, lastTickY, lastTickZ, x, y, z));
            }
        }
        lastTickX = x;
        lastTickY = y;
        lastTickZ = z;
        lastDim = dim;
    }

    /** Per-frame vertical watch, called from the moving-floor rider path. A vertical move
     * greater than one block within a single frame is never legitimate. */
    public static void framePlayerY(double y, String context) {
        if (!Double.isNaN(lastFrameY) && Math.abs(y - lastFrameY) > 1.0) {
            rec("frame", String.format("Y POP %.2f -> %.2f (%s)", lastFrameY, y, context));
            dump(String.format("FRAME Y POP %.2f -> %.2f (%s)", lastFrameY, y, context));
        }
        lastFrameY = y;
    }

    /** Suppresses the next anomaly check for an intended position change, such as a warp
     * delivery or teleporter follow, and records the reason. */
    public static void expectJump(String why) {
        rec("intent", why);
        lastFrameY = Double.NaN;
        lastTickX = Double.NaN;
    }

    /** Flushes the ring buffer to {@code logs/lattedoom-diag.log} under a marker naming
     * the trigger. Rate-limited so a repeating anomaly cannot flood the log. */
    public static synchronized void dump(String trigger) {
        final long now = System.currentTimeMillis();
        if (now - lastDumpMs < 1500) {
            rec("diag", "(dump suppressed, <1.5s since last: " + trigger + ")");
            return;
        }
        lastDumpMs = now;
        try {
            final Path file = FabricLoader.getInstance().getGameDir()
                .resolve("logs").resolve("lattedoom-diag.log");
            Files.createDirectories(file.getParent());
            final StringBuilder sb = new StringBuilder();
            sb.append("\n==================== DUMP @ ").append(java.time.LocalTime.now())
                .append(" ====================\nTRIGGER: ").append(trigger).append('\n');
            for (String line : RING) {
                sb.append(line).append('\n');
            }
            sb.append("==================== END DUMP ====================\n");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[lattedoom-diag] dumped: " + trigger);
        } catch (IOException e) {
            System.err.println("[lattedoom-diag] dump failed: " + e);
        }
    }

    private DoomDiag() {}
}
