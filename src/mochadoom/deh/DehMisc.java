package deh;

/**
 * Latte Doom patch: DEHACKED Misc values.
 *
 * The Misc block's globals. Only the entries with a clean runtime-variable site in
 * mocha are live (consulted from marked patch sites); the rest are parsed and
 * logged as skipped by DehLoader (their vanilla values are compile-time constants
 * inlined all over the engine).
 */
public final class DehMisc {

    private DehMisc() {
    }

    /** "Initial Health": G_PlayerReborn (marked site in player_t). */
    public static volatile int initialHealth = 100;
    /** "Initial Bullets": G_PlayerReborn (marked site in player_t). */
    public static volatile int initialBullets = 50;
}
