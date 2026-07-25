package p;

import doom.SourceCode.fixed_t;
import rr.SectorAction;

/**
 * Latte Doom patch: Boom v2.02 elevator thinker state (p_spec.h elevator_t).
 * An elevator moves a sector's floor and ceiling together, keeping their gap constant
 * (jff 2/22/98). Driven by the T_MoveElevator ticker in ActionsBoom; created only by
 * the Boom fixed linedef types 227-238: vanilla never instantiates one.
 */
public class elevator_t extends SectorAction {

    /** Boom elevator_e: up to next floor / down to next floor / to triggering floor. */
    public static final int ELEVATE_UP = 0;
    public static final int ELEVATE_DOWN = 1;
    public static final int ELEVATE_CURRENT = 2;

    public int type;

    /** 1 = up, -1 = down. */
    public int direction;

    @fixed_t
    public int floordestheight;

    @fixed_t
    public int ceilingdestheight;

    @fixed_t
    public int speed;
}
