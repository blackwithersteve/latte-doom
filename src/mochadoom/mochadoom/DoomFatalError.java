package mochadoom;

/**
 * Latte Doom patch: thrown instead of System.exit(-1) on I_Error and other fatal engine
 * conditions, so a dying DOOM cannot take the host JVM (Minecraft) down with it.
 */
public class DoomFatalError extends RuntimeException {
    public DoomFatalError(String message) {
        super(message);
    }

    public DoomFatalError(String message, Throwable cause) {
        super(message, cause);
    }
}
