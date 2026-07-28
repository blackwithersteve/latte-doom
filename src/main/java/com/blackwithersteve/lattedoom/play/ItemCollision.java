package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.LatteWorld;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

public final class ItemCollision {

    private static final double FLOOR_FRICTION = 0.60;
    private static final double STOP_EPSILON = 0.003;

    private static final double ITEM_RADIUS = 4.3;
    private static final double WALL_SKIN = 0.06;

    /*
     * Small vertical offset so the ItemEntity's actual Minecraft
     * bounding box sits on the Doom floor instead of being centered
     * directly inside it.
     *
     * If pickup is still unreliable, try 0.10, 0.15, or 0.20.
     */
    private static final double ITEM_FLOOR_OFFSET = 0.12;

    private static final double EPSILON = 0.000001;

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

        double doomX =
            LatteWorld.worldToDoomX(worldX);

        double doomY =
            LatteWorld.worldToDoomY(worldZ);

        int sector =
            map.sectorAt(
                doomX,
                doomY
            );

        if (sector < 0) {
            return false;
        }

        double floorZ =
            map.floorNow(sector);

        double ceilZ =
            map.ceilNow(sector);

        double floorWorldY =
            LatteWorld.doomToWorldH(floorZ);

        double ceilWorldY =
            LatteWorld.doomToWorldH(ceilZ);

        Vec3 velocity =
            item.getDeltaMovement();

        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;

        /*
         * FLOOR
         */
        if (worldY <= floorWorldY + ITEM_FLOOR_OFFSET + WALL_SKIN
            && vy <= 0.0) {

            worldY =
                floorWorldY + ITEM_FLOOR_OFFSET;

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
        if (worldY >= ceilWorldY - WALL_SKIN
            && vy > 0.0) {

            worldY =
                ceilWorldY - WALL_SKIN;

            vy = 0.0;
        }

        /*
         * Convert current position into Doom XY.
         */
        double currentX =
            LatteWorld.worldToDoomX(worldX);

        double currentY =
            LatteWorld.worldToDoomY(worldZ);

        /*
         * Calculate horizontal movement.
         */
        double targetX =
            LatteWorld.worldToDoomX(
                worldX + vx
            );

        double targetY =
            LatteWorld.worldToDoomY(
                worldZ + vz
            );

        /*
         * Resolve wall collisions.
         */
        for (int iteration = 0;
             iteration < MAX_WALL_ITERATIONS;
             iteration++) {

            double moveX =
                targetX - currentX;

            double moveY =
                targetY - currentY;

            if (Math.hypot(moveX, moveY) < EPSILON) {
                break;
            }

            WallHit hit =
                findCircleSweepHit(
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

            /*
             * Move up to the collision.
             */
            double safeFraction =
                Math.max(
                    0.0,
                    hit.fraction - 0.0005
                );

            currentX +=
                moveX * safeFraction;

            currentY +=
                moveY * safeFraction;

            /*
             * Separate from wall.
             */
            currentX +=
                hit.normalX * WALL_SKIN;

            currentY +=
                hit.normalY * WALL_SKIN;

            /*
             * Remaining movement.
             */
            double remaining =
                1.0 - safeFraction;

            if (remaining <= EPSILON) {
                targetX = currentX;
                targetY = currentY;
                break;
            }

            double remainingX =
                moveX * remaining;

            double remainingY =
                moveY * remaining;

            /*
             * Remove movement going into the wall.
             */
            double intoWall =
                remainingX * hit.normalX
                + remainingY * hit.normalY;

            if (intoWall < 0.0) {

                remainingX -=
                    hit.normalX * intoWall;

                remainingY -=
                    hit.normalY * intoWall;
            }

            /*
             * Apply the same sliding behavior to velocity.
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

            vx =
                doomDeltaToWorldX(
                    doomVelocityX
                );

            vz =
                doomDeltaToWorldZ(
                    doomVelocityY
                );

            targetX =
                currentX + remainingX;

            targetY =
                currentY + remainingY;
        }

        /*
         * Convert Doom coordinates back to Minecraft.
         */
        worldX =
            doomToWorldX(currentX);

        worldZ =
            doomToWorldZ(currentY);

        /*
         * Recalculate the sector after horizontal movement.
         */
        double finalDoomX =
            LatteWorld.worldToDoomX(worldX);

        double finalDoomY =
            LatteWorld.worldToDoomY(worldZ);

        int finalSector =
            map.sectorAt(
                finalDoomX,
                finalDoomY
            );

        if (finalSector >= 0) {

            double finalFloor =
                LatteWorld.doomToWorldH(
                    map.floorNow(finalSector)
                );

            double finalCeiling =
                LatteWorld.doomToWorldH(
                    map.ceilNow(finalSector)
                );

            /*
             * Final floor correction.
             */
            if (worldY <= finalFloor + ITEM_FLOOR_OFFSET + WALL_SKIN
                && vy <= 0.0) {

                worldY =
                    finalFloor + ITEM_FLOOR_OFFSET;

                vy = 0.0;

                item.setOnGround(true);
            }

            /*
             * Final ceiling correction.
             */
            if (worldY >= finalCeiling - WALL_SKIN
                && vy > 0.0) {

                worldY =
                    finalCeiling - WALL_SKIN;

                vy = 0.0;
            }
        }

        /*
         * Update the actual Minecraft entity position.
         *
         * setPos() also updates the entity bounding box.
         */
        item.setPos(
            worldX,
            worldY,
            worldZ
        );

        /*
         * Keep vanilla's velocity synchronized.
         */
        item.setDeltaMovement(
            vx,
            vy,
            vz
        );

        return true;
    }

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

            WallHit hit =
                sweepCircleAgainstLine(
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

    private static boolean isSolid(
            DoomMap.Line line) {

        if (line.frontSector() < 0
            || line.backSector() < 0) {

            return true;
        }

        return (line.flags() & 0x1) != 0;
    }

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

        double wallX =
            bx - ax;

        double wallY =
            by - ay;

        double wallLength =
            Math.hypot(
                wallX,
                wallY
            );

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

        double nx =
            -wallY / wallLength;

        double ny =
            wallX / wallLength;

        double moveX =
            x2 - x1;

        double moveY =
            y2 - y1;

        WallHit best = null;

        double startDistance =
            (x1 - ax) * nx
            + (y1 - ay) * ny;

        double direction =
            moveX * nx
            + moveY * ny;

        double[] offsets = {
            ITEM_RADIUS,
            -ITEM_RADIUS
        };

        for (double offset : offsets) {

            double distance =
                startDistance - offset;

            if (Math.abs(direction) < EPSILON) {
                continue;
            }

            double t =
                -distance / direction;

            if (t < -EPSILON
                || t > 1.0 + EPSILON) {

                continue;
            }

            t = clamp01(t);

            double hitX =
                x1 + moveX * t;

            double hitY =
                y1 + moveY * t;

            double along =
                (hitX - ax)
                    * (wallX / wallLength)
                + (hitY - ay)
                    * (wallY / wallLength);

            if (along < -ITEM_RADIUS
                || along > wallLength + ITEM_RADIUS) {

                continue;
            }

            double normalSign =
                offset >= 0.0
                    ? 1.0
                    : -1.0;

            double hitNormalX =
                nx * normalSign;

            double hitNormalY =
                ny * normalSign;

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
                || candidate.fraction
                    < best.fraction) {

                best = candidate;
            }
        }

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
                || startPointHit.fraction
                    < best.fraction)) {

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
                || endPointHit.fraction
                    < best.fraction)) {

            best = endPointHit;
        }

        return best;
    }

    private static WallHit sweepCircleAgainstPoint(
            double x1,
            double y1,
            double x2,
            double y2,
            double px,
            double py) {

        double dx =
            x2 - x1;

        double dy =
            y2 - y1;

        double fx =
            x1 - px;

        double fy =
            y1 - py;

        double a =
            dx * dx
            + dy * dy;

        if (a < EPSILON) {
            return null;
        }

        double b =
            2.0 * (
                fx * dx
                + fy * dy
            );

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
            (-b - sqrt)
                / (2.0 * a);

        double t2 =
            (-b + sqrt)
                / (2.0 * a);

        double t =
            Double.POSITIVE_INFINITY;

        if (t1 >= -EPSILON
            && t1 <= 1.0 + EPSILON) {

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
            Math.hypot(
                normalX,
                normalY
            );

        if (length < EPSILON) {

            length =
                Math.hypot(
                    dx,
                    dy
                );

            if (length < EPSILON) {
                return null;
            }

            normalX =
                -dx / length;

            normalY =
                -dy / length;

        } else {

            normalX /= length;
            normalY /= length;
        }

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

    private static double clamp01(
            double value) {

        return Math.max(
            0.0,
            Math.min(
                1.0,
                value
            )
        );
    }

    private static double worldDeltaToDoomX(
            double worldDelta) {

        double a =
            LatteWorld.worldToDoomX(0.0);

        double b =
            LatteWorld.worldToDoomX(1.0);

        return worldDelta * (b - a);
    }

    private static double worldDeltaToDoomY(
            double worldDelta) {

        double a =
            LatteWorld.worldToDoomY(0.0);

        double b =
            LatteWorld.worldToDoomY(1.0);

        return worldDelta * (b - a);
    }

    private static double doomDeltaToWorldX(
            double doomDelta) {

        double a =
            LatteWorld.worldToDoomX(0.0);

        double b =
            LatteWorld.worldToDoomX(1.0);

        double scale =
            b - a;

        if (Math.abs(scale) < EPSILON) {
            return 0.0;
        }

        return doomDelta / scale;
    }

    private static double doomDeltaToWorldZ(
            double doomDelta) {

        double a =
            LatteWorld.worldToDoomY(0.0);

        double b =
            LatteWorld.worldToDoomY(1.0);

        double scale =
            b - a;

        if (Math.abs(scale) < EPSILON) {
            return 0.0;
        }

        return doomDelta / scale;
    }

    private static double doomToWorldX(
            double doomX) {

        double a =
            LatteWorld.worldToDoomX(0.0);

        double b =
            LatteWorld.worldToDoomX(1.0);

        double scale =
            b - a;

        if (Math.abs(scale) < EPSILON) {
            return 0.0;
        }

        return (doomX - a) / scale;
    }

    private static double doomToWorldZ(
            double doomY) {

        double a =
            LatteWorld.worldToDoomY(0.0);

        double b =
            LatteWorld.worldToDoomY(1.0);

        double scale =
            b - a;

        if (Math.abs(scale) < EPSILON) {
            return 0.0;
        }

        return (doomY - a) / scale;
    }

    private record WallHit(
        DoomMap.Line line,
        double fraction,
        double normalX,
        double normalY
    ) {}

    private ItemCollision() {}
}
