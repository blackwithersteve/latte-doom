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
 * The flight recorder. Every interesting lane records one compact line into
 * a ring buffer: player position each tick, the rider pin each frame, every glue snap,
 * every rideFloor carry, every setPos/teleport with its caller, every session flag flip.
 *
 * When something ANOMALOUS happens — the player moves more than a block within a single
 * frame, more than four blocks within a tick, or changes dimension — the whole last ~2
 * seconds of the buffer is dumped to logs/lattedoom-diag.log with a marker naming the
 * trigger. One repro = the full story, in order, with timestamps. /doomdiag dumps
 * manually; recording is always on (cost is one formatted string per event).
 */
public final class DoomDiag {

    private static final int CAPACITY = 4000;
    private static final ArrayDeque<String> RING = new ArrayDeque<>(CAPACITY);
    private static long epoch = System.nanoTime();
    private static double lastTickX = Double.NaN, lastTickY, lastTickZ;
    private static double lastFrameY = Double.NaN;
    private static String lastDim = "";
    private static long lastDumpMs;

    /** IMPORTANT event: into the ring AND written through to the file immediately —
     * commands, level transitions, engine state, errors. The diag file reads as the
     * session's chronological story even without an anomaly dump. */
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

    /** One event line into the ring. lane = short tag ("pin", "glue", "tp", "tick"...). */
    public static synchronized void rec(String lane, String msg) {
        if (RING.size() >= CAPACITY) {
            RING.pollFirst();
        }
        final double t = (System.nanoTime() - epoch) / 1.0e9;
        RING.addLast(String.format("%10.4f %-6s %s", t, lane, msg));
    }

    /** Per-CLIENT-TICK player snapshot + the tick-scale anomaly check. */
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

    /** Per-FRAME vertical watch (called from the rider pin path — the hot lane in the
     * platform reports). A >1-block Y move within one frame is never legitimate. */
    public static void framePlayerY(double y, String context) {
        if (!Double.isNaN(lastFrameY) && Math.abs(y - lastFrameY) > 1.0) {
            rec("frame", String.format("Y POP %.2f -> %.2f (%s)", lastFrameY, y, context));
            dump(String.format("FRAME Y POP %.2f -> %.2f (%s)", lastFrameY, y, context));
        }
        lastFrameY = y;
    }

    /** Reset the frame watch (teleports that are INTENDED — warp deliveries). */
    public static void expectJump(String why) {
        rec("intent", why);
        lastFrameY = Double.NaN;
        lastTickX = Double.NaN;
    }

    /** Flush the ring to logs/lattedoom-diag.log with a trigger marker. Rate-limited. */
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
