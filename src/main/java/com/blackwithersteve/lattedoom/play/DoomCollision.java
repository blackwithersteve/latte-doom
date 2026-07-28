package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.render.DoomMap;

/**
 * DOOM-style player collision.
 *
 * <p>The movement is swept in small horizontal increments so thin linedefs such as
 * doors cannot be crossed in a single large movement step. Vertical collision is
 * resolved separately, so hitting a ceiling never becomes a horizontal wall hit.
 */
public final class DoomCollision {

    public static final double RADIUS = 16.0;
    public static final double HEIGHT = 56.0;
    public static final double STEP_UP = 24.0;

    /*
     * Maximum horizontal distance per collision sample.
     *
     * Keeping this below the player radius means a movement step cannot jump
     * completely over a door or other thin linedef.
     */
    private static final double SWEEP_STEP = 6.0;

    private static final double EPSILON = 0.001;

    public interface ThingBlocker {
        boolean blockedAt(double px, double py);
    }

    public static final ThingBlocker NOTHING = (px, py) -> false;

    public record Result(
        double x,
        double y,
        double h,
        boolean blockedX,
        boolean blockedY,
        double slideDirX,
        double slideDirY,
        boolean onGround,
        double floorZ,
        double ceilZ,
        boolean hitCeiling
    ) {
        public boolean slid() {
            return slideDirX != 0 || slideDirY != 0;
        }
    }

    public static Result move(
        DoomMap map,
        double x,
        double y,
        double h,
        double mx,
        double my,
        double mh
    ) {
        return move(map, x, y, h, mx, my, mh, NOTHING);
    }

    public static Result move(
        DoomMap map,
        double x,
        double y,
        double h,
        double mx,
        double my,
        double mh,
        ThingBlocker things
    ) {
        return move(map, x, y, h, mx, my, mh, things, HEIGHT);
    }

    public static Result move(
        DoomMap map,
        double x,
        double y,
        double h,
        double mx,
        double my,
        double mh,
        ThingBlocker things,
        double height
    ) {
        return move(map, x, y, h, mx, my, mh, things, height, RADIUS);
    }

    public static Result move(
        DoomMap map,
        double x,
        double y,
        double h,
        double mx,
        double my,
        double mh,
        ThingBlocker things,
        double height,
        double radius
    ) {
        /*
         * Split large movements first.
         *
         * This is important for doors. A 30-unit DOOM movement can otherwise
         * start on one side of a door and end completely beyond its linedef.
         */
        final double horizontal = Math.hypot(mx, my);

        if (horizontal > SWEEP_STEP) {
            final int pieces = Math.max(
                2,
                (int) Math.ceil(horizontal / SWEEP_STEP)
            );

            final double sx = mx / pieces;
            final double sy = my / pieces;
            final double sh = mh / pieces;

            Result result = new Result(
                x,
                y,
                h,
                false,
                false,
                0,
                0,
                false,
                h,
                h + height,
                false
            );

            for (int i = 0; i < pieces; i++) {
                result = moveOnce(
                    map,
                    result.x,
                    result.y,
                    result.h,
                    sx,
                    sy,
                    sh,
                    things,
                    height,
                    radius
                );

                /*
                 * Once a horizontal wall completely blocks us, do not keep
                 * trying to shove the player through it during the remaining
                 * sweep pieces.
                 *
                 * Sliding is different: a valid slide can continue.
                 */
                if (result.blockedX
                    && result.blockedY
                    && !result.slid()) {

                    break;
                }
            }

            return result;
        }

        return moveOnce(
            map,
            x,
            y,
            h,
            mx,
            my,
            mh,
            things,
            height,
            radius
        );
    }

    private static Result moveOnce(
        DoomMap map,
        double x,
        double y,
        double h,
        double mx,
        double my,
        double mh,
        ThingBlocker things,
        double height,
        double radius
    ) {
        double nx = x;
        double ny = y;

        boolean blockedX = false;
        boolean blockedY = false;

        double slideX = 0;
        double slideY = 0;

        /*
         * First try the complete horizontal movement.
         */
        final Check dest = check(
            map,
            x + mx,
            y + my,
            h,
            things,
            height,
            radius
        );

        if (dest.ok) {
            nx = x + mx;
            ny = y + my;
        } else if (dest.line != null) {
            /*
             * A linedef blocked us.
             *
             * IMPORTANT:
             * This is purely horizontal collision. A ceiling collision never
             * reaches this path because ceiling handling happens below.
             */
            final double ldx = dest.line.x2() - dest.line.x1();
            final double ldy = dest.line.y2() - dest.line.y1();
            final double len = Math.hypot(ldx, ldy);

            if (len > EPSILON) {
                final double ux = ldx / len;
                final double uy = ldy / len;

                final double t = mx * ux + my * uy;

                if (Math.abs(t) > EPSILON) {
                    double frac = 1.0;
                    boolean slideFound = false;

                    for (int i = 0; i < 8; i++) {
                        final double sx = x + t * frac * ux;
                        final double sy = y + t * frac * uy;

                        final Check slide = check(
                            map,
                            sx,
                            sy,
                            h,
                            things,
                            height,
                            radius
                        );

                        if (slide.ok) {
                            nx = sx;
                            ny = sy;
                            slideX = ux;
                            slideY = uy;
                            slideFound = true;
                            break;
                        }

                        frac *= 0.5;
                    }

                    /*
                     * If sliding cannot fit, don't manufacture a slide
                     * direction. Fall through to the axis tests.
                     */
                    if (!slideFound) {
                        slideX = 0;
                        slideY = 0;
                    }
                }
            }

            /*
             * If sliding didn't work, try each axis independently.
             */
            if (nx == x && ny == y) {
                final Check onlyX = check(
                    map,
                    x + mx,
                    y,
                    h,
                    things,
                    height,
                    radius
                );

                if (onlyX.ok && Math.abs(mx) > EPSILON) {
                    nx = x + mx;
                    blockedY = true;
                } else {
                    final Check onlyY = check(
                        map,
                        x,
                        y + my,
                        h,
                        things,
                        height,
                        radius
                    );

                    if (onlyY.ok && Math.abs(my) > EPSILON) {
                        ny = y + my;
                        blockedX = true;
                    } else {
                        blockedX = Math.abs(mx) > EPSILON;
                        blockedY = Math.abs(my) > EPSILON;
                    }
                }
            }
        } else if (dest.thingBlocked) {
            /*
             * Things are handled like solid objects, but they don't provide
             * a wall direction to slide along.
             */
            final Check onlyX = check(
                map,
                x + mx,
                y,
                h,
                things,
                height,
                radius
            );

            if (onlyX.ok && Math.abs(mx) > EPSILON) {
                nx = x + mx;
                blockedY = true;
            } else {
                final Check onlyY = check(
                    map,
                    x,
                    y + my,
                    h,
                    things,
                    height,
                    radius
                );

                if (onlyY.ok && Math.abs(my) > EPSILON) {
                    ny = y + my;
                    blockedX = true;
                } else {
                    blockedX = Math.abs(mx) > EPSILON;
                    blockedY = Math.abs(my) > EPSILON;
                }
            }
        } else {
            blockedX = Math.abs(mx) > EPSILON;
            blockedY = Math.abs(my) > EPSILON;
        }

        /*
         * Re-check the final horizontal position.
         *
         * This is also what gives us the actual floor and ceiling of the
         * space we ended in.
         */
        final Check stand = check(
            map,
            nx,
            ny,
            h,
            NOTHING,
            height,
            radius
        );

        double floorZ = stand.floorZ;
        double ceilZ = stand.ceilZ;

        /*
         * Outside the map/sector system.
         */
        if (stand.noSector) {
            floorZ = h;
            ceilZ = h + height;
        }

        /*
         * Vertical movement is completely independent of horizontal blocking.
         *
         * This is the important ceiling fix:
         *
         *     upward movement -> ceiling collision -> vertical momentum stops
         *
         * It does NOT set blockedX / blockedY.
         */
        double nh = h + mh;

        boolean ground = false;
        boolean hitCeiling = false;

        /*
         * Floor collision.
         */
        if (nh <= floorZ + EPSILON) {
            nh = floorZ;
            ground = true;

            if (mh < 0) {
                mh = 0;
            }
        }

        /*
         * Ceiling collision.
         *
         * Once the player's head reaches the ceiling, put the feet exactly
         * underneath it and report a vertical ceiling hit.
         */
        if (nh + height >= ceilZ - EPSILON) {
            nh = ceilZ - height;
            hitCeiling = mh > 0;

            /*
             * A ceiling hit consumes the upward velocity. The caller will
             * therefore not repeatedly attempt the same upward displacement.
             */
            if (nh < floorZ) {
                nh = floorZ;
                ground = true;
            }
        }

        /*
         * If the player is already standing on a floor and has no vertical
         * movement, keep them attached to it.
         */
        if (Math.abs(mh) <= EPSILON
            && h <= floorZ + EPSILON
            && h + height <= ceilZ + EPSILON) {

            nh = floorZ;
            ground = true;
        }

        return new Result(
            nx,
            ny,
            nh,
            blockedX,
            blockedY,
            slideX,
            slideY,
            ground,
            floorZ,
            ceilZ,
            hitCeiling
        );
    }

    private record Check(
        boolean ok,
        DoomMap.Line line,
        double floorZ,
        double ceilZ,
        boolean thingBlocked,
        boolean noSector
    ) {}

    /**
     * Destination collision test.
     *
     * <p>Two-sided lines are not automatically passable. Their opening must
     * actually be large enough for the player and low enough to be reached
     * without exceeding the DOOM 24-unit step limit.
     */
    private static Check check(
        DoomMap map,
        double px,
        double py,
        double h,
        ThingBlocker things,
        double height,
        double radius
    ) {
        final int sec = map.sectorAt(px, py);

        if (sec < 0) {
            return new Check(
                false,
                null,
                0,
                0,
                false,
                true
            );
        }

        double floorZ = map.floorNow(sec);
        double ceilZ = map.ceilNow(sec);

        for (DoomMap.Line l : map.lines) {
            if (
                Math.max(l.x1(), l.x2()) < px - radius
                || Math.min(l.x1(), l.x2()) > px + radius
                || Math.max(l.y1(), l.y2()) < py - radius
                || Math.min(l.y1(), l.y2()) > py + radius
            ) {
                continue;
            }

            if (!boxStraddlesLine(px, py, radius, l)) {
                continue;
            }

            /*
             * One-sided lines are always solid.
             */
            if (
                l.backSector() < 0
                || l.frontSector() < 0
                || (l.flags() & 0x1) != 0
            ) {
                return new Check(
                    false,
                    l,
                    floorZ,
                    ceilZ,
                    false,
                    false
                );
            }

            final double frontFloor =
                map.floorNow(l.frontSector());

            final double backFloor =
                map.floorNow(l.backSector());

            final double frontCeil =
                map.ceilNow(l.frontSector());

            final double backCeil =
                map.ceilNow(l.backSector());

            /*
             * The actual opening through this linedef.
             *
             * For a closed door, openTop will be at/below the player's
             * head and this test rejects the destination.
             */
            final double openBottom =
                Math.max(frontFloor, backFloor);

            final double openTop =
                Math.min(frontCeil, backCeil);

            /*
             * Not enough vertical room.
             */
            if (openTop - openBottom < height - EPSILON) {
                return new Check(
                    false,
                    l,
                    floorZ,
                    ceilZ,
                    false,
                    false
                );
            }

            /*
             * Too large a step.
             */
            if (openBottom - h > STEP_UP + EPSILON) {
                return new Check(
                    false,
                    l,
                    floorZ,
                    ceilZ,
                    false,
                    false
                );
            }

            floorZ = Math.max(floorZ, openBottom);
            ceilZ = Math.min(ceilZ, openTop);

            /*
             * The accumulated opening can become invalid when several
             * linedefs constrain the same destination box.
             */
            if (ceilZ - floorZ < height - EPSILON) {
                return new Check(
                    false,
                    l,
                    floorZ,
                    ceilZ,
                    false,
                    false
                );
            }
        }

        /*
         * Final body-space validation.
         *
         * This catches closed-door sectors even when there isn't a useful
         * linedef to return from the loop.
         */
        if (ceilZ - floorZ < height - EPSILON) {
            return new Check(
                false,
                null,
                floorZ,
                ceilZ,
                false,
                false
            );
        }

        /*
         * The player cannot have their feet below the floor or their head
         * above the ceiling.
         */
        if (h > ceilZ - height + EPSILON) {
            return new Check(
                false,
                null,
                floorZ,
                ceilZ,
                false,
                false
            );
        }

        if (floorZ - h > STEP_UP + EPSILON) {
            return new Check(
                false,
                null,
                floorZ,
                ceilZ,
                false,
                false
            );
        }

        if (things.blockedAt(px, py)) {
            return new Check(
                false,
                null,
                floorZ,
                ceilZ,
                true,
                false
            );
        }

        return new Check(
            true,
            null,
            floorZ,
            ceilZ,
            false,
            false
        );
    }

    private static boolean boxStraddlesLine(
        double px,
        double py,
        double radius,
        DoomMap.Line l
    ) {
        final double dx = l.x2() - l.x1();
        final double dy = l.y2() - l.y1();

        boolean neg = false;
        boolean pos = false;

        for (int c = 0; c < 4; c++) {
            final double cx =
                px + ((c & 1) == 0 ? -radius : radius);

            final double cy =
                py + ((c & 2) == 0 ? -radius : radius);

            final double cross =
                dx * (cy - l.y1())
                - dy * (cx - l.x1());

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
