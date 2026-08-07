package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.engine.DoomHost;

/**
 * The level-session state machine. One place owns the transition intents the
 * advance and delivery lane runs on, and one place derives the session's state each
 * client tick from the tic-boundary publications rather than from live engine reads.
 * LatteWorld executes what the state and intents imply.
 *
 * Owned here: the delivery intents (the start-teleport obligation, the
 * intermission-advance hold that becomes one, and the fresh-epoch hold that keeps a
 * delivery from consuming a pre-restart snapshot), the warped state every render and
 * physics gate keys on, and the in-level death intent. Holding these in one class is
 * what makes the transition races unwritable; as separate flags they could disagree.
 */
public final class Session {

    /**
     * The session's stage, derived once per client tick.
     *
     * TITLE and ADVENTURE_END are separate on purpose. As one state they covered two
     * unrelated situations, an engine that never carried a level (boot, demo loop) and an
     * engine whose episode just ended, which the lane must treat differently: the first is
     * a teardown, the second ejects the player to the overworld after a debounce. Merged,
     * any switch on this enum runs the adventure-end path against a title-screen engine.
     */
    public enum State { IDLE, BOOTING, TITLE, ADVENTURE_END, LEVEL, ADVANCING, DELIVERING }

    private static State state = State.IDLE;

    /** A start-delivery is owed: the next standing level teleports the player to
     * its start (the /warp arrival, the level advance, the death-restart drop). */
    private static boolean deliveryOwed;
    /** Set while the engine is between levels (intermission/finale); converts into
     * a delivery when the next level stands. */
    private static boolean advancePending;
    /** The epoch the level had when a restart/load was requested: the delivery must
     * not consume until a DIFFERENT epoch's snapshot arrives, or the player lands
     * at the death spot off a pre-restart snapshot. 0 = no hold. */
    private static long holdEpoch;
    /** The player is warped into a level session — rendering, DOOM physics, the
     * announce lane and the death law all key on this. */
    private static boolean warped;
    /** The player died inside the level; the respawn owes a map restart. */
    private static boolean died;

    public static boolean warped() {
        return warped;
    }

    public static void setWarped(boolean on) {
        if (warped != on) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("session", "warped -> " + on);
            warped = on;
        }
    }

    public static void noteDeath() {
        died = true;
    }

    public static boolean deathPending() {
        return died;
    }

    public static void clearDeath() {
        died = false;
    }

    /** Arm the start-delivery obligation. */
    public static void requestDelivery() {
        deliveryOwed = true;
    }

    /** Hold the armed delivery until a snapshot from a DIFFERENT epoch arrives. */
    public static void holdDeliveryUntilAfter(long epoch) {
        holdEpoch = epoch;
    }

    /** The engine is tallying between levels: note the advance so the next standing
     * level owes the player a delivery to its start. */
    public static void noteAdvance(long standingEpoch) {
        if (!advancePending) {
            advancePending = true;
            advanceFromEpoch = standingEpoch;
        }
    }

    /** The level instance that stood when the advance was noted. */
    private static long advanceFromEpoch;

    /**
     * The next level now stands: an advance hold becomes a delivery. True when the
     * conversion happened this tick (the caller re-asserts the warped session).
     *
     * The conversion requires a different level instance: an advance may only complete on
     * a fresh-epoch snapshot. Without that test every hold converts, including the safety
     * hold that fires on a transient absent snapshot while the player is still standing in
     * a live level, which delivers them to the start of the level they are already
     * playing.
     */
    public static boolean consumeAdvance(
            com.blackwithersteve.lattedoom.engine.WorldSnapshot s) {
        if (!advancePending) {
            return false;
        }
        if (advanceFromEpoch != 0 && s != null && s.levelEpoch == advanceFromEpoch) {
            advancePending = false;
            advanceFromEpoch = 0;
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("session",
                "advance dropped: same level instance still standing");
            return false;
        }
        advancePending = false;
        advanceFromEpoch = 0;
        deliveryOwed = true;
        return true;
    }

    /** A start-delivery is armed. */
    public static boolean deliveryPending() {
        return deliveryOwed;
    }

    /** Any delivery obligation exists — armed, still held for a fresh epoch, or an
     * advance about to convert. The void rescue must never fire while this is
     * true: rescuing one tick before a delivery was the level-finish ejection. */
    public static boolean deliveryInFlight() {
        return deliveryOwed || advancePending || holdEpoch != 0;
    }

    /** The armed delivery must keep waiting: the snapshot still belongs to the
     * pre-restart level instance. */
    public static boolean deliveryHeldFor(com.blackwithersteve.lattedoom.engine.WorldSnapshot s) {
        return holdEpoch != 0 && s != null && s.levelEpoch == holdEpoch;
    }

    /** The delivery fires now: consume the obligation and the hold. */
    public static void consumeDelivery() {
        deliveryOwed = false;
        holdEpoch = 0;
    }

    /** The obligation can never complete (failed map, stale flag): drop it, on tape. */
    public static void abortDelivery(String why) {
        if (deliveryOwed || advancePending || holdEpoch != 0) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("session",
                "delivery aborted: " + why);
        }
        deliveryOwed = false;
        advancePending = false;
        advanceFromEpoch = 0;
        holdEpoch = 0;
    }

    /** Session teardown (world change, suit boot): every intent dies with it. */
    public static void reset() {
        deliveryOwed = false;
        advancePending = false;
        advanceFromEpoch = 0;
        holdEpoch = 0;
        setWarped(false);
        died = false;
        state = State.IDLE;
    }

    /** Derive the stage from this tick's published facts and record transitions on
     * the diagnostic tape. Pure observation for now — the lane still decides. */
    public static State observe(DoomHost host, boolean hasSnap, boolean inDim) {
        final State next;
        if (host == null) {
            next = State.IDLE;
        } else if (host.state() == DoomHost.State.BOOTING) {
            next = State.BOOTING;
        } else if (host.state() != DoomHost.State.RUNNING) {
            next = State.IDLE;
        } else if (hasSnap) {
            next = deliveryInFlight() ? State.DELIVERING : State.LEVEL;
        } else if (host.hadLevel() && host.isBetweenLevels()
            && host.gamestateKind() != 3) {
            next = State.ADVANCING;
        } else if (host.hadLevel() && host.gamestateKind() == 3) {
            // an engine that HAS carried a level and is now showing its title screen has
            // ended the adventure; the lane still owes this a debounce, because a
            // transient title read mid-transition must never eject the player
            next = State.ADVENTURE_END;
        } else if (host.hadLevel()) {
            next = State.ADVANCING; // null snapshot, stage unknown: transition-shaped
        } else {
            next = State.TITLE; // booted, demo loop, never carried a level
        }
        if (next != state) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("session", String.format(
                "%s -> %s (warped=%s inDim=%s)", state, next, warped, inDim));
            state = next;
        }
        return state;
    }

    /** The stage as of the last {@link #observe}. */
    public static State state() {
        return state;
    }

    private Session() {
    }
}
