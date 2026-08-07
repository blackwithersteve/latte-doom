package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import com.blackwithersteve.lattedoom.play.Session;

/**
 * Gate on the session state machine. The machine decides every hold, delivery and
 * teardown in a level transition, and its failures strand or misplace the player: a lost
 * obligation leaves them behind, while one that fires twice or against the wrong level
 * instance teleports them somewhere they never went. Those interleavings are expensive to
 * reproduce in a live game and cheap to assert here, because Session is plain static state
 * with no Minecraft dependency.
 *
 * The invariant it exists for: an advance completes only on a fresh-epoch snapshot.
 *
 * Run: gradlew sessionProbe
 */
public final class SessionProbe {

    private static int failures;

    public static void main(String[] args) {
        advanceConvertsOnAFreshLevel();
        advanceDoesNotConvertOnTheSameLevel();
        advanceSurvivesRepeatedHoldTicks();
        deliveryHoldWaitsForANewInstance();
        abortClearsEveryObligation();
        resetClearsTheWholeMachine();
        deliveryInFlightCoversEveryStage();

        if (failures > 0) {
            System.out.println("RESULT: FAIL, " + failures + " assertion(s)");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /** The real advance: a different level instance stands, so the hold becomes a delivery. */
    private static void advanceConvertsOnAFreshLevel() {
        Session.reset();
        Session.setWarped(true);
        Session.noteAdvance(snap(100).levelEpoch);
        check("advance is in flight while held", Session.deliveryInFlight());
        check("advance converts on a fresh epoch", Session.consumeAdvance(snap(200)));
        check("conversion arms the delivery", Session.deliveryPending());
    }

    /**
     * THE DEFECT THIS GATE WAS WRITTEN FOR. holdBetweenLevels notes an advance from the
     * safety branch that fires on a transient null snapshot while the player is still
     * standing in a live level. Converting that into a delivery teleports the player to the
     * start of the level they were already playing.
     */
    private static void advanceDoesNotConvertOnTheSameLevel() {
        Session.reset();
        Session.setWarped(true);
        Session.noteAdvance(snap(100).levelEpoch);
        check("same level instance does NOT convert", !Session.consumeAdvance(snap(100)));
        check("the spurious advance is dropped, not left armed", !Session.deliveryPending());
        check("nothing stays in flight to suppress the void rescue",
            !Session.deliveryInFlight());
    }

    /** holdBetweenLevels runs every client tick of the hold: the epoch must be stamped once. */
    private static void advanceSurvivesRepeatedHoldTicks() {
        Session.reset();
        Session.setWarped(true);
        for (int i = 0; i < 40; i++) {
            Session.noteAdvance(snap(100).levelEpoch); // re-stamping would defeat the guard
        }
        check("a long hold still refuses the departed level",
            !Session.consumeAdvance(snap(100)));
        Session.reset();
        Session.setWarped(true);
        for (int i = 0; i < 40; i++) {
            Session.noteAdvance(snap(100).levelEpoch);
        }
        check("a long hold still converts on the arriving level",
            Session.consumeAdvance(snap(300)));
    }

    /** The death-restart / save-load wait: deliver off the NEW instance, not the old one. */
    private static void deliveryHoldWaitsForANewInstance() {
        Session.reset();
        Session.setWarped(true);
        Session.requestDelivery();
        Session.holdDeliveryUntilAfter(snap(100).levelEpoch);
        check("held against the pre-restart snapshot", Session.deliveryHeldFor(snap(100)));
        check("released by a new instance", !Session.deliveryHeldFor(snap(101)));
        check("still in flight while held", Session.deliveryInFlight());
        Session.consumeDelivery();
        check("consuming clears the obligation", !Session.deliveryPending());
        check("consuming clears the hold", !Session.deliveryInFlight());
    }

    private static void abortClearsEveryObligation() {
        Session.reset();
        Session.requestDelivery();
        Session.noteAdvance(snap(100).levelEpoch);
        Session.holdDeliveryUntilAfter(snap(100).levelEpoch);
        Session.abortDelivery("probe");
        check("abort clears the delivery", !Session.deliveryPending());
        check("abort clears everything in flight", !Session.deliveryInFlight());
        check("an aborted advance cannot convert later", !Session.consumeAdvance(snap(999)));
    }

    private static void resetClearsTheWholeMachine() {
        Session.setWarped(true);
        Session.noteDeath();
        Session.requestDelivery();
        Session.noteAdvance(snap(100).levelEpoch);
        Session.reset();
        check("reset clears warped", !Session.warped());
        check("reset clears death", !Session.deathPending());
        check("reset clears the delivery", !Session.deliveryPending());
        check("reset clears everything in flight", !Session.deliveryInFlight());
        check("reset returns the machine to IDLE", Session.state() == Session.State.IDLE);
    }

    /**
     * The void rescue keys on deliveryInFlight(). Rescuing one tick before a delivery WAS
     * the level-finish ejection, so every stage of an obligation must report in flight.
     */
    private static void deliveryInFlightCoversEveryStage() {
        Session.reset();
        check("idle is not in flight", !Session.deliveryInFlight());
        Session.noteAdvance(snap(100).levelEpoch);
        check("a held advance is in flight", Session.deliveryInFlight());
        Session.reset();
        Session.requestDelivery();
        check("an armed delivery is in flight", Session.deliveryInFlight());
        Session.reset();
        Session.requestDelivery();
        Session.holdDeliveryUntilAfter(snap(100).levelEpoch);
        Session.consumeDelivery();
        check("a consumed delivery is no longer in flight", !Session.deliveryInFlight());
    }

    private static WorldSnapshot snap(long epoch) {
        return WorldSnapshot.forEpoch(epoch);
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        } else {
            System.out.println("  ok:   " + what);
        }
    }

    private SessionProbe() {
    }
}
