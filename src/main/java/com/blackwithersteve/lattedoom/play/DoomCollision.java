package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.render.DoomMap;

/**
 * The engine's {@code P_CheckPosition}, {@code P_TryMove} and {@code P_SlideMove} for the
 * mirrored player, in map units on the map plane.
 *
 * <p>Collision follows the original's method: the destination bounding box, of radius 16,
 * is tested against every linedef it straddles. One-sided and blocking lines reject the
 * move outright, while two-sided lines narrow the floor and ceiling limits through their
 * opening. The verdict then follows from three numbers: the opening must be at least 56
 * units tall, a step up may be at most 24 units, and grounded feet snap to the resulting
 * floor, which is what makes stairs a smooth rise rather than a jump. A blocked move
 * slides as {@code P_SlideMove} does, by projecting the momentum onto the blocking wall
 * and falling back to per-axis movement.
 *
 * <p>This class is pure geometry with no Minecraft dependencies, which is what allows the
 * movement harness to exercise it headlessly.
 */
public final class DoomCollision {

    public static final double RADIUS = 16.0;
    public static final double HEIGHT = 56.0;
    public static final double STEP_UP = 24.0;

    /** PIT_CheckThing stand-in: does a solid thing's box overlap the player box at (px,py)? */
    public interface ThingBlocker {
        boolean blockedAt(double px, double py);
    }

    public static final ThingBlocker NOTHING = (px, py) -> false;

    /** Where the move ended: position, height, what happened to momentum. */
    public record Result(double x, double y, double h,
                         boolean blockedX, boolean blockedY,
                         double slideDirX, double slideDirY,
                         boolean onGround, double floorZ, double ceilZ) {
        public boolean slid() {
            return slideDirX != 0 || slideDirY != 0;
        }
    }

    /**
     * One movement step: {@code (mx, my)} across the plane and {@code mh} vertically.
     * Moves longer than 24 units are halved first, because the destination-box method
     * must never step further than its own box.
     */
    public static Result move(DoomMap map, double x, double y, double h,
                              double mx, double my, double mh) {
        return move(map, x, y, h, mx, my, mh, NOTHING);
    }

    /** As above, but a ThingBlocker also stops the box: barrels, pillars, live monsters. */
    public static Result move(DoomMap map, double x, double y, double h,
                              double mx, double my, double mh, ThingBlocker things) {
        return move(map, x, y, h, mx, my, mh, things, HEIGHT);
    }

    /**
     * As above for a body of the given height in map units. A transformed player is the
     * engine's 56 units tall, while an untransformed one keeps their Minecraft box of 1.8
     * blocks, which is 50.4 units. Clipping the latter at 56 refuses openings their body
     * visibly fits through and stops them on a step up into a low ceiling.
     */
    public static Result move(DoomMap map, double x, double y, double h,
                              double mx, double my, double mh, ThingBlocker things,
                              double height) {
        return move(map, x, y, h, mx, my, mh, things, height, RADIUS);
    }

    /**
     * As above for a body of the given height and radius in map units. A transformed player
     * is the engine's 56 by 16; an untransformed one keeps their Minecraft box, which is
     * narrower as well as shorter, and clipping them at the wider radius refuses gaps their
     * body plainly fits through.
     */
    public static Result move(DoomMap map, double x, double y, double h,
                              double mx, double my, double mh, ThingBlocker things,
                              double height, double radius) {
        if (Math.abs(mx) > 24 || Math.abs(my) > 24) {
            final Result first = move(map, x, y, h, mx / 2, my / 2, mh / 2, things, height, radius);
            final Result second = move(map, first.x, first.y, first.h,
                mx / 2, my / 2, mh / 2, things, height, radius);
            return new Result(second.x, second.y, second.h,
                first.blockedX || second.blockedX, first.blockedY || second.blockedY,
                second.slid() ? second.slideDirX : first.slideDirX,
                second.slid() ? second.slideDirY : first.slideDirY,
                second.onGround, second.floorZ, second.ceilZ);
        }
        return moveOnce(map, x, y, h, mx, my, mh, things, height, radius);
    }

    private static Result moveOnce(DoomMap map, double x, double y, double h,
                                   double mx, double my, double mh, ThingBlocker things,
                                   double height, double radius) {
        double nx = x, ny = y;
        boolean blockedX = false, blockedY = false;
        double slideX = 0, slideY = 0;

        final Check dest = check(map, x + mx, y + my, h, things, height, radius);
        if (dest.ok) {
            nx = x + mx;
            ny = y + my;
        } else if (dest.line != null) {
            // P_SlideMove: project the motion onto the wall and continue along it.
            final double ldx = dest.line.x2() - dest.line.x1();
            final double ldy = dest.line.y2() - dest.line.y1();
            final double len = Math.hypot(ldx, ldy);
            if (len > 1.0e-9) {
                final double ux = ldx / len, uy = ldy / len;
                final double t = mx * ux + my * uy;
                // The slide is the wall-parallel component of the motion. Applying that
                // component in full can overshoot into the next linedef, at a curved wall's
                // segment joint or the far wall of a corner, so the original clips it to the
                // largest fraction that fits while keeping the wall-parallel momentum.
                // Testing only the full endpoint discards the entire slide in those cases
                // and falls back to per-axis movement, which zeroes both axes against a
                // diagonal wall and leaves the player unable to move along it.
                if (t != 0) {
                    double frac = 1.0;
                    for (int i = 0; i < 5; i++) {
                        final Check slide = check(map, x + t * frac * ux, y + t * frac * uy, h, things, height, radius);
                        if (slide.ok) {
                            nx = x + t * frac * ux;
                            ny = y + t * frac * uy;
                            slideX = ux;
                            slideY = uy;
                            break;
                        }
                        frac *= 0.5;
                    }
                }
            }
            if (nx == x && ny == y) {
                // Per-axis fallbacks, as P_XYMovement does.
                final Check onlyX = check(map, x + mx, y, h, things, height, radius);
                if (onlyX.ok && mx != 0) {
                    nx = x + mx;
                    blockedY = true;
                } else {
                    final Check onlyY = check(map, x, y + my, h, things, height, radius);
                    if (onlyY.ok && my != 0) {
                        ny = y + my;
                        blockedX = true;
                    } else {
                        blockedX = true;
                        blockedY = true;
                    }
                }
            }
        } else if (dest.thingBlocked) {
            // A solid object, with no wall to project onto: fall back to per-axis movement
            // so the player slides around its bounding box rather than sticking to it.
            final Check onlyX = check(map, x + mx, y, h, things, height, radius);
            if (onlyX.ok && mx != 0) {
                nx = x + mx;
                blockedY = true;
            } else {
                final Check onlyY = check(map, x, y + my, h, things, height, radius);
                if (onlyY.ok && my != 0) {
                    ny = y + my;
                    blockedX = true;
                } else {
                    blockedX = true;
                    blockedY = true;
                }
            }
        } else {
            blockedX = true;
            blockedY = true;
        }

        // Resolve the heights from the box the move ended in. That box is free of objects
        // by construction, so the plain map test gives the floor and ceiling.
        final Check stand = check(map, nx, ny, h, NOTHING, height, radius);
        double floorZ = stand.floorZ, ceilZ = stand.ceilZ;
        if (stand.line == null && !stand.ok && floorZ == 0 && ceilZ == 0) {
            // A point outside every sector returns the sentinel Check(false, null, 0, 0),
            // whose zeroes mean "no answer" rather than a floor at height zero. Using them
            // drops the player to map height 0, which on most levels is far below the floor.
            // Keeping the height the move started at leaves the decision to the caller's own
            // out-of-level handling instead.
            floorZ = h;
            ceilZ = h + height;
        }

        // P_ZMovement
        double nh = h + mh;
        boolean ground = false;
        if (nh <= floorZ) {
            nh = floorZ;
            ground = true;
        }
        if (nh + height > ceilZ) {
            nh = Math.max(floorZ, ceilZ - height);
        }
        // A grounded player rises with the floor when stepping up.
        if (mh == 0 && h <= floorZ + 0.001) {
            nh = floorZ;
            ground = true;
        }
        return new Result(nx, ny, nh, blockedX, blockedY, slideX, slideY, ground, floorZ, ceilZ);
    }

    private record Check(boolean ok, DoomMap.Line line, double floorZ, double ceilZ,
                         boolean thingBlocked) {}

    /** P_CheckPosition at (px,py) for a marine whose feet are at height h. */
    private static Check check(DoomMap map, double px, double py, double h,
                               ThingBlocker things, double height, double radius) {
        final int sec = map.sectorAt(px, py);
        if (sec < 0) {
            return new Check(false, null, 0, 0, false); // the void
        }
        double floorZ = map.floorNow(sec);
        double ceilZ = map.ceilNow(sec);

        for (DoomMap.Line l : map.lines) {
            // Cheap bounding-box rejection first.
            if (Math.max(l.x1(), l.x2()) < px - radius || Math.min(l.x1(), l.x2()) > px + radius
                || Math.max(l.y1(), l.y2()) < py - radius || Math.min(l.y1(), l.y2()) > py + radius) {
                continue;
            }
            if (!boxStraddlesLine(px, py, radius, l)) {
                continue;
            }
            if (l.backSector() < 0 || l.frontSector() < 0 || (l.flags() & 0x1) != 0) {
                return new Check(false, l, floorZ, ceilZ, false); // solid / ML_BLOCKING
            }
            final double ff = map.floorNow(l.frontSector()), bf = map.floorNow(l.backSector());
            final double fc = map.ceilNow(l.frontSector()), bc = map.ceilNow(l.backSector());
            final double openBot = Math.max(ff, bf);
            final double openTop = Math.min(fc, bc);
            if (openTop - openBot < height    // the opening is shorter than the player
                || openBot - h > STEP_UP) {   // the step up is taller than 24 units
                return new Check(false, l, floorZ, ceilZ, false);
            }
            floorZ = Math.max(floorZ, openBot);
            ceilZ = Math.min(ceilZ, openTop);
        }
        if (ceilZ - floorZ < height || ceilZ - h < height || floorZ - h > STEP_UP) {
            return new Check(false, null, floorZ, ceilZ, false);
        }
        if (things.blockedAt(px, py)) {
            return new Check(false, null, floorZ, ceilZ, true); // linedefs clear, a body in the box
        }
        return new Check(true, null, floorZ, ceilZ, false);
    }

    private static boolean boxStraddlesLine(double px, double py, double radius, DoomMap.Line l) {
        final double dx = l.x2() - l.x1(), dy = l.y2() - l.y1();
        boolean neg = false, pos = false;
        for (int c = 0; c < 4; c++) {
            final double cx = px + ((c & 1) == 0 ? -radius : radius);
            final double cy = py + ((c & 2) == 0 ? -radius : radius);
            final double cross = dx * (cy - l.y1()) - dy * (cx - l.x1());
            if (cross < 0) {
                neg = true;
            } else {
                pos = true;
            }
        }
        return neg && pos;
    }

    private DoomCollision() {}
}
