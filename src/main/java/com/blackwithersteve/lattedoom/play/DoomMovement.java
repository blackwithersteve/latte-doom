package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Player movement inside a level, in two modes.
 */
public final class DoomMovement {

    private static double preX, preY, preZ;
    private static float preMoveDist, preFlyDist;
    private static boolean active;

    private static final double TICS_PER_MC_TICK = 35.0 / 20.0;
    private static final double FRICTION = 0.90625;
    private static final double STOPSPEED = 0.0625;
    private static final double GRAVITY = 1.0;
    private static final double MAXMOVE = 30.0;

    private static final double THRUST_WALK = 25 * 2048 / 65536.0;
    private static final double THRUST_RUN = 50 * 2048 / 65536.0;
    private static final double SIDE_WALK = 24 * 2048 / 65536.0;
    private static final double SIDE_RUN = 40 * 2048 / 65536.0;

    private static double momX, momY, momH;
    private static double ticAccum;
    private static boolean marineWas;
    private static int seedEpoch = -1;

    private static boolean runHeld;
    private static boolean autorun;

    public static void setRunHeld(boolean held) {
        runHeld = held;
    }

    public static boolean toggleAutorun() {
        autorun = !autorun;
        return autorun;
    }

    public static void damageThrust(double mxU, double myU) {
        momX += mxU;
        momY += myU;
    }

    public static double viewBobOffsetBlocks(double tics) {
        return viewBob(tics, momX * momX + momY * momY);
    }

    public static double viewBobForSpeed(double tics, double blocksPerTick) {
        final double u =
            blocksPerTick * LatteWorld.UNITS / TICS_PER_MC_TICK;

        return viewBob(tics, u * u);
    }

    private static double viewBob(double tics, double momSq) {
        final double bob = Math.min(momSq / 4.0, 16.0);
        final double ang =
            (tics % 20.0) / 20.0 * (Math.PI * 2.0);

        return bob / 2.0 * Math.sin(ang) / LatteWorld.UNITS;
    }

    private static double curX, curY, curH;
    private static double prvX, prvY, prvH;

    private static boolean curGround;
    private static boolean blockGrounded;

    private static double placedWx, placedWy, placedWz;

    public static boolean marineTravel(LocalPlayer p, Vec3 input) {
        if (
            !LatteWorld.marineForm()
            || p.getAbilities().flying
            || p.isFallFlying()
            || p.isSwimming()
            || p.isInWater()
            || p.isInLava()
            || p.isPassenger()
            || p.onClimbable()
        ) {
            marineWas = false;
            restoreStep(p);
            return false;
        }

        active = false;

        final DoomMap map = LatteWorld.map();

        final boolean inLevel =
            map != null
            && LatteWorld.insideLevel(
                p.getX(),
                p.getY(),
                p.getZ()
            );

        final boolean moved =
            Math.abs(p.getX() - placedWx) > 0.5
            || Math.abs(p.getY() - placedWy) > 0.5
            || Math.abs(p.getZ() - placedWz) > 0.5;

        final int epoch = LatteWorld.originEpoch();

        if (epoch != seedEpoch) {
            seedEpoch = epoch;
            marineWas = false;
        }

        if (!marineWas || moved) {
            curX = prvX =
                LatteWorld.worldToDoomX(p.getX());

            curY = prvY =
                LatteWorld.worldToDoomY(p.getZ());

            curH = prvH =
                LatteWorld.worldToDoomH(p.getY());

            final Vec3 dm = p.getDeltaMovement();

            momX =
                dm.x * LatteWorld.UNITS
                / TICS_PER_MC_TICK;

            momY =
                -dm.z * LatteWorld.UNITS
                / TICS_PER_MC_TICK;

            momH =
                dm.y * LatteWorld.UNITS
                / TICS_PER_MC_TICK;

            ticAccum = 0;
            curGround = false;
            marineWas = true;
        }

        p.setSprinting(false);

        final double ang =
            Math.toRadians(-p.getYRot() - 90.0);

        final double fx = Math.cos(ang);
        final double fy = Math.sin(ang);

        final double rx = fy;
        final double ry = -fx;

        final boolean run =
            runHeld || autorun;

        final double speedScale =
            speedScale(p);

        final double fwd =
            Math.signum(input.z)
            * (run ? THRUST_RUN : THRUST_WALK)
            * speedScale;

        final double side =
            -Math.signum(input.x)
            * (run ? SIDE_RUN : SIDE_WALK)
            * speedScale;

        final double startWx = p.getX();
        final double startWy = p.getY();
        final double startWz = p.getZ();

        ticAccum += TICS_PER_MC_TICK;

        if (inLevel) {
            setStep(p, 0.75);

            final WorldSnapshot snap =
                LatteWorld.worldSnap();

            final boolean boomFriction =
                snap != null
                && snap.secFriction != null;

            final boolean boomPush =
                snap != null
                && (
                    snap.playerPushX != 0
                    || snap.playerPushY != 0
                );

            while (ticAccum >= 1.0) {
                ticAccum -= 1.0;

                prvX = curX;
                prvY = curY;
                prvH = curH;

                glue(map);

                final DoomCollision.ThingBlocker things =
                    thingBlocker(curX, curY);

                final DoomCollision.Result here =
                    DoomCollision.move(
                        map,
                        curX,
                        curY,
                        curH,
                        0,
                        0,
                        0,
                        things
                    );

                final boolean ground =
                    here.onGround()
                    || blockGrounded;

                curH = here.h();

                double friction = FRICTION;
                double scale = 1.0;

                if (boomFriction && ground) {
                    final int sec =
                        map.sectorAt(curX, curY);

                    if (
                        sec >= 0
                        && sec < snap.secFriction.length
                        && snap.secFriction[sec]
                            != ORIG_FRICTION_D
                        && Math.abs(
                            curH - map.floorNow(sec)
                        ) < 1.0
                    ) {
                        friction =
                            snap.secFriction[sec];

                        scale =
                            thrustScale(
                                snap.secFriction,
                                snap.secMoveFactor,
                                sec
                            );
                    }
                }

                thrustAndFriction(
                    input,
                    ground,
                    fx,
                    fy,
                    rx,
                    ry,
                    fwd * scale,
                    side * scale,
                    friction
                );

                if (boomPush) {
                    momX += snap.playerPushX;
                    momY += snap.playerPushY;
                }

                final DoomCollision.Result r =
                    DoomCollision.move(
                        map,
                        curX,
                        curY,
                        curH,
                        momX,
                        momY,
                        ground ? 0 : momH,
                        things
                    );

                curX = r.x();
                curY = r.y();
                curH = r.h();

                /*
                 * CEILING FIX
                 *
                 * The collision system explicitly tells us that the head
                 * touched the ceiling. Kill only upward velocity.
                 *
                 * Horizontal momentum is completely untouched.
                 */
                if (r.hitCeiling() && momH > 0) {
                    momH = 0;
                }

                if (r.slid()) {
                    final double t =
                        momX * r.slideDirX()
                        + momY * r.slideDirY();

                    momX =
                        t * r.slideDirX();

                    momY =
                        t * r.slideDirY();
                } else {
                    if (r.blockedX()) {
                        momX = 0;
                    }

                    if (r.blockedY()) {
                        momY = 0;
                    }
                }

                if (!ground && r.onGround()) {
                    momH = 0;
                }

                curGround =
                    r.onGround()
                    || blockGrounded;

                rememberGlue(
                    map,
                    r.onGround()
                );
            }

            final double a = ticAccum;

            final double wx =
                LatteWorld.doomToWorldX(
                    prvX + (curX - prvX) * a
                );

            double wy =
                LatteWorld.doomToWorldH(
                    prvH + (curH - prvH) * a
                );

            final double wz =
                LatteWorld.doomToWorldZ(
                    prvY + (curY - prvY) * a
                );

            final double rideY =
                LatteWorld.riddenSurfaceWorldY();

            if (!Double.isNaN(rideY)) {
                wy = rideY;
            }

            if (
                p.level()
                    .dimension()
                    .equals(
                        com.blackwithersteve.lattedoom.net.LatteNet.DOOM_LEVEL_DIM
                    )
            ) {
                final float flyBefore = p.flyDist;

                p.move(
                    net.minecraft.world.entity.MoverType.SELF,
                    new Vec3(
                        wx - startWx,
                        wy - startWy,
                        wz - startWz
                    )
                );

                p.flyDist = flyBefore;

                final double cdx =
                    p.getX() - wx;

                final double cdy =
                    p.getY() - wy;

                final double cdz =
                    p.getZ() - wz;

                if (
                    cdx != 0
                    || cdy != 0
                    || cdz != 0
                ) {
                    curX +=
                        cdx * LatteWorld.UNITS;

                    prvX +=
                        cdx * LatteWorld.UNITS;

                    curY +=
                        -cdz * LatteWorld.UNITS;

                    prvY +=
                        -cdz * LatteWorld.UNITS;

                    curH +=
                        cdy * LatteWorld.UNITS;

                    prvH +=
                        cdy * LatteWorld.UNITS;

                    if (Math.abs(cdx) > 1.0e-5) {
                        momX = 0;
                    }

                    if (Math.abs(cdz) > 1.0e-5) {
                        momY = 0;
                    }

                    /*
                     * Minecraft block collision may also catch a ceiling.
                     * Kill upward movement only.
                     */
                    if (
                        Math.abs(cdy) > 1.0e-5
                        && cdy < 0
                        && momH > 0
                    ) {
                        momH = 0;
                    }
                }

                blockGrounded =
                    blockUnderfoot(p);

                placedWx = p.getX();
                placedWy = p.getY();
                placedWz = p.getZ();

                p.setDeltaMovement(
                    p.getX() - startWx,
                    p.getY() - startWy,
                    p.getZ() - startWz
                );

                p.setOnGroundWithMovement(
                    curGround || blockGrounded,
                    new Vec3(
                        p.getX() - startWx,
                        p.getY() - startWy,
                        p.getZ() - startWz
                    )
                );
            } else {
                p.setPos(wx, wy, wz);

                blockGrounded = false;

                placedWx = wx;
                placedWy = wy;
                placedWz = wz;

                p.setDeltaMovement(
                    wx - startWx,
                    wy - startWy,
                    wz - startWz
                );

                p.setOnGround(curGround);

                p.moveDist +=
                    (float)
                    Math.hypot(
                        wx - startWx,
                        wz - startWz
                    ) * 0.6f;
            }
        } else {
            setStep(p, 1.0);

            glueSector = -1;

            final boolean groundNow =
                p.onGround();

            while (ticAccum >= 1.0) {
                ticAccum -= 1.0;

                prvX = curX;
                prvY = curY;
                prvH = curH;

                thrustAndFriction(
                    input,
                    groundNow,
                    fx,
                    fy,
                    rx,
                    ry,
                    fwd,
                    side,
                    FRICTION
                );

                curX += momX;
                curY += momY;

                if (!groundNow) {
                    curH += momH;
                }
            }

            final double a = ticAccum;

            final double ix =
                LatteWorld.doomToWorldX(
                    prvX + (curX - prvX) * a
                );

            final double iy =
                LatteWorld.doomToWorldH(
                    prvH + (curH - prvH) * a
                );

            final double iz =
                LatteWorld.doomToWorldZ(
                    prvY + (curY - prvY) * a
                );

            if (
                Math.abs(ix - startWx)
                + Math.abs(iy - startWy)
                + Math.abs(iz - startWz)
                > 8.0
            ) {
                marineWas = false;
                return true;
            }

            p.move(
                net.minecraft.world.entity.MoverType.SELF,
                new Vec3(
                    ix - startWx,
                    iy - startWy,
                    iz - startWz
                )
            );

            final double cdx =
                p.getX() - ix;

            final double cdy =
                p.getY() - iy;

            final double cdz =
                p.getZ() - iz;

            curX +=
                cdx * LatteWorld.UNITS;

            prvX +=
                cdx * LatteWorld.UNITS;

            curY +=
                -cdz * LatteWorld.UNITS;

            prvY +=
                -cdz * LatteWorld.UNITS;

            curH +=
                cdy * LatteWorld.UNITS;

            prvH +=
                cdy * LatteWorld.UNITS;

            if (Math.abs(cdx) > 1.0e-5) {
                momX = 0;
            }

            if (Math.abs(cdz) > 1.0e-5) {
                momY = 0;
            }

            if (
                Math.abs(cdy) > 1.0e-5
                && cdy < 0
                && momH > 0
            ) {
                momH = 0;
            }

            placedWx = p.getX();
            placedWy = p.getY();
            placedWz = p.getZ();

            p.setDeltaMovement(
                p.getX() - startWx,
                p.getY() - startWy,
                p.getZ() - startWz
            );
        }

        p.resetFallDistance();
        return true;
    }

    private static int glueSector = -1;
    private static double glueFloor;

    private static void glue(DoomMap map) {
        if (glueSector < 0 || !curGround) {
            return;
        }

        final int here =
            map.sectorAt(curX, curY);

        if (here != glueSector) {
            return;
        }

        final double nowFloor =
            map.floorNow(glueSector);

        if (
            nowFloor != glueFloor
            && Math.abs(curH - glueFloor) < 1.0
        ) {
            curH = nowFloor;
            momH = 0;
        }
    }

    private static void rememberGlue(
        DoomMap map,
        boolean grounded
    ) {
        if (grounded) {
            final int sec =
                map.sectorAt(curX, curY);

            if (
                sec >= 0
                && Math.abs(
                    curH - map.floorNow(sec)
                ) < 1.0
            ) {
                glueSector = sec;
                glueFloor =
                    map.floorNow(sec);
            } else {
                glueSector = -1;
            }
        } else {
            glueSector = -1;
        }
    }

    private static boolean blockUnderfoot(
        LocalPlayer p
    ) {
        final net.minecraft.world.phys.AABB bb =
            p.getBoundingBox();

        final net.minecraft.world.phys.AABB probe =
            new net.minecraft.world.phys.AABB(
                bb.minX,
                bb.minY - 0.08,
                bb.minZ,
                bb.maxX,
                bb.minY + 0.001,
                bb.maxZ
            );

        for (
            net.minecraft.world.phys.shapes.VoxelShape s
                : p.level().getBlockCollisions(p, probe)
        ) {
            if (!s.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private static DoomCollision.ThingBlocker thingBlocker(
        double fromX,
        double fromY
    ) {
        return (toX, toY) ->
            LatteWorld.increasesThingOverlap(
                fromX,
                fromY,
                toX,
                toY
            );
    }

    public static void launch(
        double momzUnitsPerTic
    ) {
        if (marineWas) {
            pendingLaunch =
                Math.max(
                    pendingLaunch,
                    momzUnitsPerTic
                );
        }
    }

    private static double pendingLaunch;

    public static void releaseGlue() {
        glueSector = -1;
    }

    public static int gluedSector() {
        return glueSector;
    }

    public static void resetSession() {
        marineWas = false;
        glueSector = -1;

        momX = 0;
        momY = 0;
        momH = 0;

        ticAccum = 0;

        placedWx = 0;
        placedWy = 0;
        placedWz = 0;

        curGround = false;
        blockGrounded = false;
        pendingLaunch = 0;

        lastStep = -1;
    }

    public static void forceReseed() {
        marineWas = false;
        glueSector = -1;

        momX = 0;
        momY = 0;
        momH = 0;

        blockGrounded = false;
        pendingLaunch = 0;
    }

    public static void rideFloor(
        double deltaU
    ) {
        if (deltaU == 0) {
            return;
        }

        curH += deltaU;
        prvH += deltaU;
        glueFloor += deltaU;
    }

    public static void syncRiddenY(
        double worldY
    ) {
        placedWy = worldY;
    }

    private static double speedScale(
        LocalPlayer p
    ) {
        final var attr =
            p.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED
            );

        if (attr == null) {
            return 1.0;
        }

        final double base =
            attr.getBaseValue();

        if (base <= 0.0) {
            return 1.0;
        }

        return Math.max(
            0.2,
            Math.min(
                4.0,
                attr.getValue() / base
            )
        );
    }

    private static void thrustAndFriction(
        Vec3 input,
        boolean ground,
        double fx,
        double fy,
        double rx,
        double ry,
        double fwd,
        double side,
        double friction
    ) {
        if (ground) {
            if (input.z != 0) {
                momX += fx * fwd;
                momY += fy * fwd;
            }

            if (input.x != 0) {
                momX += rx * side;
                momY += ry * side;
            }

            momX *= friction;
            momY *= friction;

            if (
                input.z == 0
                && input.x == 0
                && Math.hypot(momX, momY) < STOPSPEED
            ) {
                momX = 0;
                momY = 0;
            }

            momH = 0;
        } else {
            momH -= GRAVITY;
        }

        if (pendingLaunch > 0) {
            momH = pendingLaunch;
            pendingLaunch = 0;
            blockGrounded = false;
        }

        momX =
            Math.max(
                -MAXMOVE,
                Math.min(MAXMOVE, momX)
            );

        momY =
            Math.max(
                -MAXMOVE,
                Math.min(MAXMOVE, momY)
            );
    }

    private static final double ORIG_FRICTION_D =
        0xE800 / 65536.0;

    private static final double MUD_MOMENTUM =
        15000 / 65536.0;

    private static double thrustScale(
        double[] secFriction,
        int[] secMoveFactor,
        int sec
    ) {
        int mf =
            secMoveFactor[sec];

        if (
            secFriction[sec]
            < ORIG_FRICTION_D
        ) {
            final double momentum =
                aproxDist(momX, momY);

            if (
                momentum
                > MUD_MOMENTUM * 4
            ) {
                mf <<= 3;
            } else if (
                momentum
                > MUD_MOMENTUM * 2
            ) {
                mf <<= 2;
            } else if (
                momentum
                > MUD_MOMENTUM
            ) {
                mf <<= 1;
            }
        }

        return mf / 2048.0;
    }

    private static double aproxDist(
        double dx,
        double dy
    ) {
        final double ax =
            Math.abs(dx);

        final double ay =
            Math.abs(dy);

        return ax
            + ay
            - Math.min(ax, ay) / 2.0;
    }

    private static double lastStep = -1;

    private static void setStep(
        LocalPlayer p,
        double v
    ) {
        if (lastStep == v) {
            return;
        }

        final var attr =
            p.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT
            );

        if (attr != null) {
            attr.setBaseValue(v);
            lastStep = v;
        }
    }

    private static void restoreStep(
        LocalPlayer p
    ) {
        setStep(p, 0.6);
    }

    public static void beforeTravel(
        LocalPlayer p
    ) {
        active =
            !p.getAbilities().flying
            && !p.isFallFlying()
            && !p.isSwimming()
            && !p.isInWater()
            && !p.isInLava()
            && !p.isPassenger()
            && !p.onClimbable()
            && LatteWorld.map() != null
            && LatteWorld.insideLevel(
                p.getX(),
                p.getY(),
                p.getZ()
            );

        if (active) {
            preX = p.getX();
            preY = p.getY();
            preZ = p.getZ();

            preMoveDist = p.moveDist;
            preFlyDist = p.flyDist;
        }
    }

    public static void afterTravel(
        LocalPlayer p
    ) {
        if (!active) {
            return;
        }

        final DoomMap map =
            LatteWorld.map();

        if (map == null) {
            return;
        }

        final double dx =
            p.getX() - preX;

        final double dy =
            p.getY() - preY;

        final double dz =
            p.getZ() - preZ;

        final double x =
            LatteWorld.worldToDoomX(preX);

        final double yD =
            LatteWorld.worldToDoomY(preZ);

        double h =
            LatteWorld.worldToDoomH(preY);

        if (
            glueSector >= 0
            && map.sectorAt(x, yD)
                == glueSector
        ) {
            final double nowFloor =
                map.floorNow(glueSector);

            final double rideW =
                LatteWorld.riddenSurfaceWorldY();

            final boolean onDrawn =
                !Double.isNaN(rideW)
                && Math.abs(preY - rideW) < 1.0;

            if (
                nowFloor != glueFloor
                && (
                    Math.abs(h - glueFloor) < 1.0
                    || onDrawn
                )
            ) {
                h = nowFloor;
            }
        }

        final DoomCollision.ThingBlocker things =
            thingBlocker(x, yD);

        final DoomCollision.Result r =
            DoomCollision.move(
                map,
                x,
                yD,
                h,
                dx * LatteWorld.UNITS,
                -dz * LatteWorld.UNITS,
                dy * LatteWorld.UNITS,
                things,
                p.getBbHeight()
                    * LatteWorld.UNITS,
                p.getBbWidth()
                    * 0.5
                    * LatteWorld.UNITS
            );

        if (r.onGround()) {
            final int sec =
                map.sectorAt(
                    r.x(),
                    r.y()
                );

            if (
                sec >= 0
                && Math.abs(
                    r.h()
                    - map.floorNow(sec)
                ) < 1.0
            ) {
                glueSector = sec;
                glueFloor =
                    map.floorNow(sec);
            } else {
                glueSector = -1;
            }
        } else {
            glueSector = -1;
        }

        final double tx =
            LatteWorld.doomToWorldX(r.x());

        final double tz =
            LatteWorld.doomToWorldZ(r.y());

        double ridY =
            LatteWorld.doomToWorldH(
                r.h()
            );

        final double rideSurf =
            LatteWorld.riddenSurfaceWorldY();

        if (!Double.isNaN(rideSurf)) {
            ridY = rideSurf;
        }

        final boolean inLevelDim =
            p.level()
                .dimension()
                .equals(
                    com.blackwithersteve.lattedoom.net.LatteNet.DOOM_LEVEL_DIM
                );

        boolean grounded =
            r.onGround();

        if (inLevelDim) {
            p.setPos(
                preX,
                preY,
                preZ
            );

            p.moveDist =
                preMoveDist;

            p.move(
                net.minecraft.world.entity.MoverType.SELF,
                new Vec3(
                    tx - preX,
                    ridY - preY,
                    tz - preZ
                )
            );

            p.flyDist =
                preFlyDist;

            grounded =
                r.onGround()
                || blockUnderfoot(p);

            p.setOnGroundWithMovement(
                grounded,
                new Vec3(
                    p.getX() - preX,
                    p.getY() - preY,
                    p.getZ() - preZ
                )
            );
        } else {
            p.setPos(
                tx,
                ridY,
                tz
            );

            p.moveDist =
                preMoveDist
                + (float)
                    Math.hypot(
                        tx - preX,
                        tz - preZ
                    ) * 0.6f;

            p.flyDist =
                preFlyDist;
        }

        final Vec3 dm =
            p.getDeltaMovement();

        double ndx = dm.x;
        double ndy = dm.y;
        double ndz = dm.z;

        if (r.slid()) {
            final double vx = dm.x;
            final double vy = -dm.z;

            final double t =
                vx * r.slideDirX()
                + vy * r.slideDirY();

            ndx =
                t * r.slideDirX();

            ndz =
                -(t * r.slideDirY());
        } else {
            if (r.blockedX()) {
                ndx = 0;
            }

            if (r.blockedY()) {
                ndz = 0;
            }
        }

        if (grounded && ndy < 0) {
            ndy = 0;
        }

        /*
         * Do not turn a ceiling collision into horizontal blocking.
         * The Doom collision result already killed the vertical motion
         * conceptually; this keeps Minecraft's velocity consistent too.
         */
        if (r.hitCeiling() && ndy > 0) {
            ndy = 0;
        }

        final WorldSnapshot snap =
            LatteWorld.worldSnap();

        if (
            snap != null
            && (
                snap.playerPushX != 0
                || snap.playerPushY != 0
            )
        ) {
            final double k =
                TICS_PER_MC_TICK
                * TICS_PER_MC_TICK
                / LatteWorld.UNITS;

            ndx +=
                snap.playerPushX * k;

            ndz +=
                -snap.playerPushY * k;
        }

        p.setDeltaMovement(
            ndx,
            ndy,
            ndz
        );

        if (!inLevelDim) {
            p.setOnGround(grounded);
        }

        p.resetFallDistance();
    }

    private DoomMovement() {}
}
