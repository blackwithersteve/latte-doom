package p;

import doom.thinker_t;

/**
 * Latte Doom additive patch — Boom v2.02 scroller thinker state (p_spec.c T_Scroll).
 * One instance per scroll effect: walls (sidedef offsets), floors/ceilings (visual),
 * and the CARRY types that convey things standing on the floor — the mechanism Boom
 * maps use to drive voodoo-doll scripting closets.
 */
public class scroll_t extends thinker_t {

    public static final int SC_SIDE = 0;
    public static final int SC_FLOOR = 1;
    public static final int SC_CEILING = 2;
    public static final int SC_CARRY = 3;

    public int type;
    /** Scroll amounts per tic, fixed_t. */
    public int dx, dy;
    /** Sidedef index (SC_SIDE) or sector index (others). */
    public int affectee;
    /** Control sector index for displacement/accelerative scrollers, -1 = none. */
    public int control = -1;
    /** True = accelerative (scroll speed accumulates with control height changes). */
    public boolean accel;
    /** Accumulated velocity for control-driven scrollers, fixed_t. */
    public int vdx, vdy;
    /** The control sector's last floor+ceiling height sum, fixed_t. */
    public int lastHeight;
}
