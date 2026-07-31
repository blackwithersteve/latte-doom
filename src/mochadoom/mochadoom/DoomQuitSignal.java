package mochadoom;

/**
 * Cocoa Doom: thrown on the engine thread instead of System.exit(0) when the player
 * quits DOOM from its own menu. The embedding host (Minecraft) catches this and
 * tears down the engine while the JVM lives on.
 */
public class DoomQuitSignal extends RuntimeException {
    public DoomQuitSignal() {
        super("DOOM quit normally");
    }
}
