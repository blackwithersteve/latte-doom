package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.LatteWorld;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

public final class ItemCollision {

    private static final double FLOOR_FRICTION = 0.60;
    private static final double STOP_EPSILON = 0.003;

    /*
     * Actual collision radius of the item in Doom map units.
     * its hard to get things right the first try
     * Increase this if items can still get their edges through walls.
     */
    private static final double ITEM_RADIUS = 4.3;

    /*
     * Small extra separation from walls.
     *
     * Prevents floating point errors from leaving the item embedded.
     */
    private static final double WALL_SKIN = 0.06;

    private static final double EPSILON = 0.000001;

    /*
     * Multiple collision iterations let an item:
     *
     *   wall -> corner -> another wall
     *
     * in the same tick without tunneling through the geometry.
     */
    private static final int MAX_WALL_ITERATIONS = 5;

    public static boolean tick(ItemEntity item) {
        DoomMap map = LatteWorld.map();

        if (map == null) {
            return false;
        }

        double worldX = item.getX();
        double worldY = item.getY();
        double worldZ = item.getZ();

        if (!LatteWorld.insideLevel(worldX, worldY, worldZ)) {
            return false;
        }

        double doomX = LatteWorld.worldToDoomX(worldX);
        double doomY = LatteWorld.worldToDoomY(worldZ);

        int sector = map.sectorAt(doomX, doomY);

        if (sector < 0) {
            return false;
        }

        double floorZ = map.floorNow(sector);
        double ceilZ = map.ceilNow(sector);

        double floorWorldY = LatteWorld.doomToWorldH(floorZ);
        double ceilWorldY = LatteWorld.doomToWorldH(ceilZ);

        Vec3 velocity = item.getDeltaMovement();

        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;

        /*
         * Vanilla ItemEntity has already applied gravity and performed
         * its normal movement before this collision pass.
         */

        /*
         * FLOOR
         */
        if (worldY <= floorWorldY + WALL_SKIN && vy <= 0.0) {
            worldY = floorWorldY;
            vy = 0.0;

            item.setOnGround(true);

            vx *= FLOOR_FRICTION;
            vz *= FLOOR_FRICTION;

            if (Math.abs(vx) < STOP_EPSILON) {
                vx = 0.0;
            }

            if (Math.abs(vz) < STOP_EPSILON) {
                vz = 0.0;
            }
        } else {
            item.setOnGround(false);
        }

        /*
         * CEILING
         */
        if (worldY >= ceilWorldY - WALL_SKIN && vy > 0.0) {
            worldY = ceilWorldY;
            vy = 0.0;
        }

        /*
         * WALL MOVEMENT
         *
         * Work entirely in Doom XY space for horizontal collision.
         */
        double currentX = LatteWorld.worldToDoomX(worldX);
        double currentY = LatteWorld.worldToDoomY(worldZ);

        double targetX =
            LatteWorld.worldToDoomX(worldX + vx);

        double targetY =
            LatteWorld.worldToDoomY(worldZ + vz);

        /*
         * Sweep the item's collision circle through the movement.
         *
         * We can hit multiple walls during one tick.
         */
        for (int iteration = 0; iteration < MAX_WALL_ITERATIONS; iteration++) {

            double moveX = targetX - currentX;
            double moveY = targetY - currentY;

            if (Math.hypot(moveX, moveY) < EPSILON) {
                break;
            }

            WallHit hit = findCircleSweepHit(
                map,
                currentX,
                currentY,
                targetX,
                targetY
            );

            if (hit == null) {
                currentX = targetX;
                currentY = targetY;
                break;
            }


//i dont know why the player cant pick up items as steve
//i based this code on doomCollision.java because i am still very new to making minecraft mods


            /*
             * Move to just before the collision.
             */
            double safeFraction =
                Math.max(0.0, hit.fraction - 0.0005);

            currentX += moveX * safeFraction;
            currentY += moveY * safeFraction;

            /*
             * Push the item away from the wall.
             *
             * This is important because simply stopping at the collision
             * point can leave the radius partially inside the wall.
             */
            currentX += hit.normalX * WALL_SKIN;
            currentY += hit.normalY * WALL_SKIN;

            /*
             * Remaining movement after the collision.
             */
            double remaining = 0.9 - safeFraction;

            if (remaining <= EPSILON) {
                targetX = currentX;
                targetY = currentY;
                break;
            }

            /*
             * Calculate the movement that remains.
             */
            double remainingX = moveX * remaining;
            double remainingY = moveY * remaining;

            /*
             * Remove the component pointing INTO the wall.
             *
             * Tangential velocity remains, producing natural sliding.
             */
            double intoWall =
                remainingX * hit.normalX
                + remainingY * hit.normalY;

            if (intoWall < 0.0) {
                remainingX -= hit.normalX * intoWall;
                remainingY -= hit.normalY * intoWall;
            }

            /*
             * Do the same thing to actual item velocity.
             *
             * This is what makes thrown items preserve their velocity
             * when they scrape along a wall.
             */
            double doomVelocityX =
                worldDeltaToDoomX(vx);

            double doomVelocityY =
                worldDeltaToDoomY(vz);

            double velocityIntoWall =
                doomVelocityX * hit.normalX
                + doomVelocityY * hit.normalY;

            if (velocityIntoWall < 0.0) {
                doomVelocityX -=
                    hit.normalX * velocityIntoWall;

                doomVelocityY -=
                    hit.normalY * velocityIntoWall;
            }

            vx = doomDeltaToWorldX(doomVelocityX);
            vz = doomDeltaToWorldZ(doomVelocityY);

            /*
             * Continue the remaining movement.
             */
            targetX = currentX + remainingX;
            targetY = currentY + remainingY;
        }

        worldX = doomToWorldX(currentX);
        worldZ = doomToWorldZ(currentY);

        /*
         * Final sector correction.
         *
         * The item may have crossed a sector boundary during a wall slide.
         */
        double finalDoomX =
            LatteWorld.worldToDoomX(worldX);

        double finalDoomY =
            LatteWorld.worldToDoomY(worldZ);

        int finalSector =
            map.sectorAt(finalDoomX, finalDoomY);

        if (finalSector >= 0) {

            double finalFloor =
                LatteWorld.doomToWorldH(
                    map.floorNow(finalSector)
                );

            double finalCeiling =
                LatteWorld.doomToWorldH(
                    map.ceilNow(finalSector)
                );

            if (worldY <= finalFloor + WALL_SKIN && vy <= 0.0) {
                worldY = finalFloor;
                vy = 0.0;

                item.setOnGround(true);
            }

            if (worldY >= finalCeiling - WALL_SKIN && vy > 0.0) {
                worldY = finalCeiling;
                vy = 0.0;
            }
        }

        item.setPos(
            worldX,
            worldY,
            worldZ
        );

        item.setDeltaMovement(
            vx,
            vy,
            vz
        );

        return true;
    }

    /*
     * Sweeps a circle against every solid Doom wall.
     *
     * Unlike the old implementation, this detects:
     *
     *   - direct wall hits
     *   - angled hits
     *   - glancing hits
     *   - corner hits
     *   - endpoint hits
     *   - very short movement segments
     *   - tunneling caused by high throw velocity
     */
    private static WallHit findCircleSweepHit(
            DoomMap map,
            double x1,
            double y1,
            double x2,
            double y2) {

        WallHit closest = null;

        for (DoomMap.Line line : map.lines) {

            if (!isSolid(line)) {
                continue;
            }

            WallHit hit = sweepCircleAgainstLine(
                x1,
                y1,
                x2,
                y2,
                line
            );

            if (hit == null) {
                continue;
            }

            if (closest == null
                || hit.fraction < closest.fraction) {

                closest = hit;
            }
        }

        return closest;
    }

    private static boolean isSolid(DoomMap.Line line) {

        /*
         * One-sided linedefs are solid.
         */
        if (line.frontSector() < 0
            || line.backSector() < 0) {

            return true;
        }

        /*
         * Doom BLOCKING flag.
         */
        return (line.flags() & 0x1) != 0;
    }

    /*
     * Sweeps a circle against a line segment.
     *
     * The collision is found by checking:
     *
     * 1. The infinite line around the wall.
     * 2. The finite wall segment.
     * 3. Both wall endpoints as circles.
     *
     * This is substantially more robust than a simple
     * segment/segment intersection.
     */
    private static WallHit sweepCircleAgainstLine(
            double x1,
            double y1,
            double x2,
            double y2,
            DoomMap.Line line) {

        double ax = line.x1();
        double ay = line.y1();
        double bx = line.x2();
        double by = line.y2();

        double wallX = bx - ax;
        double wallY = by - ay;

        double wallLength =
            Math.hypot(wallX, wallY);

        if (wallLength < EPSILON) {
            return sweepCircleAgainstPoint(
                x1,
                y1,
                x2,
                y2,
                ax,
                ay
            );
        }

        double nx = -wallY / wallLength;
        double ny = wallX / wallLength;

        double moveX = x2 - x1;
        double moveY = y2 - y1;

        WallHit best = null;

        /*
         * Determine which side of the wall the item starts on.
         *
         * This prevents the sweep from immediately detecting the
         * "back side" of the expanded wall.
         */
        double startDistance =
            (x1 - ax) * nx
            + (y1 - ay) * ny;

        double direction =
            moveX * nx
            + moveY * ny;

        /*
         * Test both offset surfaces of the wall.
         */
        double[] offsets = {
            ITEM_RADIUS,
            -ITEM_RADIUS
        };

        for (double offset : offsets) {

            /*
             * If we're already inside this expanded surface,
             * don't create a fake collision at t=0.
             */
            double distance =
                startDistance - offset;

            if (Math.abs(direction) < EPSILON) {
                continue;
            }

            double t =
                -distance / direction;

            if (t < -EPSILON || t > 1.0 + EPSILON) {
                continue;
            }

            t = clamp01(t);

            double hitX =
                x1 + moveX * t;

            double hitY =
                y1 + moveY * t;

            /*
             * Project collision point onto the wall.
             */
            double along =
                (hitX - ax) * (wallX / wallLength)
                + (hitY - ay) * (wallY / wallLength);

            if (along < -ITEM_RADIUS
                || along > wallLength + ITEM_RADIUS) {

                continue;
            }

            double normalSign =
                offset >= 0.0 ? 1.0 : -1.0;

            double hitNormalX =
                nx * normalSign;

            double hitNormalY =
                ny * normalSign;

            /*
             * Only collide if movement is entering the wall.
             */
            double approach =
                moveX * hitNormalX
                + moveY * hitNormalY;

            if (approach >= 0.0) {
                continue;
            }

            WallHit candidate =
                new WallHit(
                    line,
                    t,
                    hitNormalX,
                    hitNormalY
                );

            if (best == null
                || candidate.fraction < best.fraction) {

                best = candidate;
            }
        }

        /*
         * Endpoint tests are extremely important.
         *
         * Without these, the item's radius can cut around the end
         * of a wall even though the actual circle hits the corner.
         */
        WallHit startPointHit =
            sweepCircleAgainstPoint(
                x1,
                y1,
                x2,
                y2,
                ax,
                ay
            );

        if (startPointHit != null
            && (best == null
                || startPointHit.fraction < best.fraction)) {

            best = startPointHit;
        }

        WallHit endPointHit =
            sweepCircleAgainstPoint(
                x1,
                y1,
                x2,
                y2,
                bx,
                by
            );

        if (endPointHit != null
            && (best == null
                || endPointHit.fraction < best.fraction)) {

            best = endPointHit;
        }

        return best;
    }

    /*
     * Sweeps the item's circle against a single point.
     *
     * This is effectively a ray vs circle test.
     */
    private static WallHit sweepCircleAgainstPoint(
            double x1,
            double y1,
            double x2,
            double y2,
            double px,
            double py) {

        double dx = x2 - x1;
        double dy = y2 - y1;

        double fx = x1 - px;
        double fy = y1 - py;

        double a =
            dx * dx
            + dy * dy;

        if (a < EPSILON) {
            return null;
        }

        double b =
            2.0 * (fx * dx + fy * dy);

        double c =
            fx * fx
            + fy * fy
            - ITEM_RADIUS * ITEM_RADIUS;

        double discriminant =
            b * b
            - 4.0 * a * c;

        if (discriminant < 0.0) {
            return null;
        }

        double sqrt =
            Math.sqrt(discriminant);

        double t1 =
            (-b - sqrt) / (2.0 * a);

        double t2 =
            (-b + sqrt) / (2.0 * a);

        double t = Double.POSITIVE_INFINITY;

        if (t1 >= -EPSILON && t1 <= 1.0 + EPSILON) {
            t = t1;
        }

        if (t2 >= -EPSILON
            && t2 <= 1.0 + EPSILON
            && t2 < t) {

            t = t2;
        }

        if (!Double.isFinite(t)) {
            return null;
        }

        t = clamp01(t);

        double hitX =
            x1 + dx * t;

        double hitY =
            y1 + dy * t;

        double normalX =
            hitX - px;

        double normalY =
            hitY - py;

        double length =
            Math.hypot(normalX, normalY);

        if (length < EPSILON) {
            /*
             * The center is exactly on the endpoint.
             *
             * Use the opposite movement direction as the normal.
             */
            length =
                Math.hypot(dx, dy);

            if (length < EPSILON) {
                return null;
            }

            normalX = -dx / length;
            normalY = -dy / length;
        } else {
            normalX /= length;
            normalY /= length;
        }

        /*
         * Only report an actual approaching collision.
         */
        double approach =
            dx * normalX
            + dy * normalY;

        if (approach >= 0.0) {
            return null;
        }

        return new WallHit(
            null,
            t,
            normalX,
            normalY
        );
    }

    private static double clamp01(double value) {
        return Math.max(
            0.0,
            Math.min(1.0, value)
        );
    }

    /*
     * Convert a world-space X movement into Doom-space X movement.
     */
    private static double worldDeltaToDoomX(double worldDelta) {
        double a =
            LatteWorld.worldToDoomX(0.0);

        double b =
            LatteWorld.worldToDoomX(1.0);

        return worldDelta * (b - a);
    }

    /*
     * Convert a world-space Z movement into Doom-space Y movement.
     */
    private static double worldDeltaToDoomY(double worldDelta) {
        double a =
            LatteWorld.worldToDoomY(0.0);

        double b =
            LatteWorld.worldToDoomY(1.0);

        return worldDelta * (b - a);
    }

    private static double doomDeltaToWorldX(double doomDelta) {
        double a =
            LatteWorld.worldToDoomX(0.0);

        double b =
            LatteWorld.worldToDoomX(1.0);

        double scale = b - a;

        if (Math.abs(scale) < EPSILON) {
            return 0.0;
        }

        return doomDelta / scale;
    }

    private static double doomDeltaToWorldZ(double doomDelta) {
        double a =
            LatteWorld.worldToDoomY(0.0);

        double b =
            LatteWorld.worldToDoomY(1.0);

        double scale = b - a;

        if (Math.abs(scale) < EPSILON) {
            return 0.0;
        }

        return doomDelta / scale;
    }

    private static double doomToWorldX(double doomX) {
        double a =
            LatteWorld.worldToDoomX(0.0);

        double b =
            LatteWorld.worldToDoomX(1.0);

        if (Math.abs(b - a) < EPSILON) {
            return 0.0;
        }

        return (doomX - a) / (b - a);
    }

    private static double doomToWorldZ(double doomY) {
        double a =
            LatteWorld.worldToDoomY(0.0);

        double b =
            LatteWorld.worldToDoomY(1.0);

        if (Math.abs(b - a) < EPSILON) {
            return 0.0;
        }

        return (doomY - a) / (b - a);
    }

    private record WallHit(
        DoomMap.Line line,
        double fraction,
        double normalX,
        double normalY
    ) {}

    private ItemCollision() {}
}
