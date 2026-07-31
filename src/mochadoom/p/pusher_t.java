package p;

import doom.thinker_t;

/**
 * Latte Doom additive patch — Boom v2.02 pusher thinker state (p_spec.c pusher_t).
 * One instance per pusher effect: wind (224) affects players above the floor at full
 * force and grounded players at half force; current (225) affects only grounded
 * players at full force; a point source (226) pushes or pulls players within
 * 2 x magnitude of the map's MT_PUSH/MT_PULL point with linear distance falloff.
 * All magnitudes derive from the spawning line's length, exactly like Boom.
 */
public class pusher_t extends thinker_t {

    /** Boom p_spec.h pushertype_e: p_push, p_pull are both PT_POINT here (pull flag). */
    public static final int PT_WIND = 0;
    public static final int PT_CURRENT = 1;
    public static final int PT_POINT = 2;

    public int type;
    /** Force components, INTEGER map units (line delta >> FRACBITS), Boom x_mag/y_mag. */
    public int xMag, yMag;
    /** P_AproxDistance(xMag, yMag) — integer map units; scales the point falloff. */
    public int magnitude;
    /** Effective radius of a point source, fixed_t (magnitude << (FRACBITS+1)). */
    public int radius;
    /** Point source position, fixed_t (the recorded 5001/5002 map thing). */
    public int x, y;
    /** True for MT_PULL (5002): force toward the point; false pushes away (5001). */
    public boolean pull;
    /**
     * Detached stand-in mobj for the sight check (PIT_PushThing's P_CheckSight):
     * positioned at the point source, never linked into the world or thinker list.
     * Mocha has no MT_PUSH/MT_PULL mobj types, so the real thing never spawns.
     */
    public mobj_t source;
    /** Controlled sector index; its special's PUSH_MASK bit gates the effect per tic. */
    public int affectee;
}
