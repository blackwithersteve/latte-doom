package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import com.blackwithersteve.lattedoom.render.DoomMap;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Player movement inside a level, in two modes.
 *
 * <p>An <b>untransformed player</b> keeps Minecraft's movement: vanilla {@code travel()}
 * runs untouched and the displacement it produced is re-applied under the level's collision
 * through {@link DoomCollision}, so walls, floors, steps and moving sectors all apply while
 * the acceleration curve remains Minecraft's.
 *
 * <p>A <b>transformed player</b> skips vanilla movement for the engine's own physics:
 * thrust and friction in 35 Hz substeps, a run key, no air control and no jump.
 *
 * <p>Boom friction floors and pushers reach both modes through the snapshot. Levels that
 * define neither publish nothing, and both paths use the standard constants.
 *
 * <p>Creative flight is exempt, and walking beyond a level's bounds returns the player to
 * Minecraft's collision mid-stride.
 */
public final class DoomMovement {

    private static double preX, preY, preZ;
    private static float preMoveDist, preFlyDist;
    private static boolean active;

    // ---- Transformed player: the engine's own physics, with its thrust and friction
    // integrated in 35 Hz substeps, no air control and no jump. An untransformed player
    // keeps Minecraft's movement, handled further below. ----
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
    // The run key follows the original: hold Shift, or use Caps Lock to toggle always-run.
    // Minecraft's own sprint has no effect while transformed.
    private static boolean runHeld;
    private static boolean autorun;

    public static void setRunHeld(boolean held) {
        runHeld = held;
    }

    /** Caps Lock pressed: flip always-run. Returns the new state for the chat notice. */
    public static boolean toggleAutorun() {
        autorun = !autorun;
        return autorun;
    }

    /** P_DamageMobj's push, applied to the player's own momentum in map units per tic:
     * the engine computed the vector, so being shot pushes the player exactly as it
     * does in the original. */
    public static void damageThrust(double mxU, double myU) {
        momX += mxU;
        momY += myU;
    }

    /** P_CalcHeight's view bob as an eye offset in blocks, with the amplitude taken from
     * own momentum, as {@code (momx² + momy²) / 4} capped at 16 units, completing one
     * sine every 20 tics. This replaces Minecraft's head bob inside a level. */
    public static double viewBobOffsetBlocks(double tics) {
        return viewBob(tics, momX * momX + momY * momY);
    }

    /** The same view bob for an untransformed player, with the amplitude taken from their
     * Minecraft velocity, in horizontal blocks per tick, converted to the engine's
     * momentum units, so the bob can never outpace the movement it belongs to. */
    public static double viewBobForSpeed(double tics, double blocksPerTick) {
        final double u = blocksPerTick * LatteWorld.UNITS / TICS_PER_MC_TICK;
        return viewBob(tics, u * u);
    }

    private static double viewBob(double tics, double momSq) {
        final double bob = Math.min(momSq / 4.0, 16.0);
        final double ang = (tics % 20.0) / 20.0 * (Math.PI * 2.0);
        return bob / 2.0 * Math.sin(ang) / LatteWorld.UNITS;
    }
    // Physics keyframes in map units: the state at the last completed tic and the one
    // before it. The player's rendered position is the interpolation between them.
    private static double curX, curY, curH, prvX, prvY, prvH;
    private static boolean curGround;
    /** Whether the post-move probe found a placed block underfoot. Feeds the next tic's
     * ground state, so a block grants walking control and suppresses gravity exactly as a
     * sector floor does. */
    private static boolean blockGrounded;
    // The position this class last placed the player at. A mismatch means something else
    // moved them, such as a teleporter follow or a server resynchronisation, and the
    // physics state must be re-seeded there.
    private static double placedWx, placedWy, placedWz;

    /**
     * Runs the engine's physics for a transformed player. Returns true when vanilla
     * movement should be skipped for this tick.
     *
     * <p>The integrator advances exact 35 Hz tics from an accumulator. Since 35 does not
     * divide evenly into Minecraft's 20 Hz, stepping naively alternates one and two tics per
     * tick and the apparent speed visibly flutters. The rendered position is therefore the
     * interpolation between the last two tic states: a constant 1.75 tics of motion per
     * tick, one tic of latency, physics untouched.
     */
    public static boolean marineTravel(LocalPlayer p, Vec3 input) {
        if (!LatteWorld.marineForm()
            || p.getAbilities().flying || p.isFallFlying() || p.isSwimming()
            || p.isInWater() || p.isInLava() || p.isPassenger() || p.onClimbable()) {
            marineWas = false;
            restoreStep(p);
            return false;
        }
        active = false; // this path owns the tick; the tail clip must not run on stale state
        final DoomMap map = LatteWorld.map();
        // Inside a level the linedefs clip movement. Outside one, the same physics run but
        // Minecraft's blocks do the clipping, so the transformed movement works anywhere.
        final boolean inLevel = map != null
            && LatteWorld.insideLevel(p.getX(), p.getY(), p.getZ());
        final boolean moved = Math.abs(p.getX() - placedWx) > 0.5
            || Math.abs(p.getY() - placedWy) > 0.5 || Math.abs(p.getZ() - placedWz) > 0.5;
        // All keyframes are relative to the level origin, so a new origin, from loading or
        // reloading a level, invalidates them. Re-seed at the player's current position;
        // without this the stale keyframes are interpreted against the new origin and
        // displace the player by the difference between the two.
        final int epoch = LatteWorld.originEpoch();
        if (epoch != seedEpoch) {
            seedEpoch = epoch;
            marineWas = false;
        }
        if (!marineWas || moved) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("seed", String.format(
                "reseed at (%.2f, %.2f, %.2f) marineWas=%s moved=%s",
                p.getX(), p.getY(), p.getZ(), marineWas, moved));
            curX = prvX = LatteWorld.worldToDoomX(p.getX());
            curY = prvY = LatteWorld.worldToDoomY(p.getZ());
            curH = prvH = LatteWorld.worldToDoomH(p.getY());
            final Vec3 dm = p.getDeltaMovement();
            momX = dm.x * LatteWorld.UNITS / TICS_PER_MC_TICK;
            momY = -dm.z * LatteWorld.UNITS / TICS_PER_MC_TICK;
            momH = dm.y * LatteWorld.UNITS / TICS_PER_MC_TICK;
            ticAccum = 0;
            curGround = false;
            marineWas = true;
        }
        p.setSprinting(false); // no MC sprint state (double-tap W): DOOM run is shift/caps
        final double ang = Math.toRadians(-p.getYRot() - 90.0);
        final double fx = Math.cos(ang), fy = Math.sin(ang);
        final double rx = fy, ry = -fx;
        final boolean run = runHeld || autorun;
        // Minecraft's movement-speed attribute scales the engine's thrust, so speed and
        // slowness effects reach a transformed player. An unaffected player sits at the
        // default attribute value and the ratio is 1, which leaves DOOM's constants exact.
        final double speedScale = speedScale(p);
        final double fwd = Math.signum(input.z) * (run ? THRUST_RUN : THRUST_WALK) * speedScale;
        final double side = -Math.signum(input.x) * (run ? SIDE_RUN : SIDE_WALK) * speedScale;

        final double startWx = p.getX(), startWy = p.getY(), startWz = p.getZ();
        ticAccum += TICS_PER_MC_TICK;
        if (inLevel) {
            setStep(p, 0.75); // 24-unit step: a slab is climbable, a full block is not
            // Boom friction and pushers arrive in the snapshot. A snapshot received over
            // the network never carries them, so a spectator uses the standard constants.
            final WorldSnapshot snap = LatteWorld.worldSnap();
            final boolean boomFriction = snap != null && snap.secFriction != null;
            final boolean boomPush = snap != null
                && (snap.playerPushX != 0 || snap.playerPushY != 0);
            while (ticAccum >= 1.0) {
                ticAccum -= 1.0;
                prvX = curX;
                prvY = curY;
                prvH = curH;
                glue(map);
                // Only a deep overlap allows movement out of another body, so merely
                // touching a monster still blocks, while being teleported inside one does
                // not trap the player permanently.
                final DoomCollision.ThingBlocker things = thingBlocker(curX, curY);
                final DoomCollision.Result here = DoomCollision.move(map, curX, curY, curH,
                    0, 0, 0, things);
                // A placed block underfoot counts as ground, granting walking control and
                // suppressing gravity exactly as a sector floor does. The flag comes from
                // the post-move probe.
                final boolean ground = here.onGround() || blockGrounded;
                curH = here.h();
                // On a friction floor the sector's own friction and move factor replace
                // the constants, but only when the feet are genuinely on that sector's
                // floor, using the same tolerance the floor-follow logic uses.
                double friction = FRICTION;
                double scale = 1.0;
                if (boomFriction && ground) {
                    final int sec = map.sectorAt(curX, curY);
                    if (sec >= 0 && sec < snap.secFriction.length
                        && snap.secFriction[sec] != ORIG_FRICTION_D
                        && Math.abs(curH - map.floorNow(sec)) < 1.0) {
                        friction = snap.secFriction[sec];
                        scale = thrustScale(snap.secFriction, snap.secMoveFactor, sec);
                    }
                }
                thrustAndFriction(input, ground, fx, fy, rx, ry,
                    fwd * scale, side * scale, friction);
                // The pusher force is applied after thrust and before the move. The engine
                // adds it after the object's move, so it takes effect on the following tic
                // and is then subject to friction; the composition here matches. Wind also
                // carries an airborne player.
                if (boomPush) {
                    momX += snap.playerPushX;
                    momY += snap.playerPushY;
                }
                final DoomCollision.Result r = DoomCollision.move(map, curX, curY, curH,
                    momX, momY, ground ? 0 : momH, things);
                curX = r.x();
                curY = r.y();
                curH = r.h();
                if (r.slid()) {
                    final double t = momX * r.slideDirX() + momY * r.slideDirY();
                    momX = t * r.slideDirX();
                    momY = t * r.slideDirY();
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
                curGround = r.onGround() || blockGrounded;
                rememberGlue(map, r.onGround()); // sector glue: doom floors only
                com.blackwithersteve.lattedoom.diag.DoomDiag.rec("phys", String.format(
                    "curH=%.2f ground=%s gs=%d momH=%.2f", curH, curGround, glueSector, momH));
            }
            final double a = ticAccum; // partial-tic phase: render between the keyframes
            final double wx = LatteWorld.doomToWorldX(prvX + (curX - prvX) * a);
            double wy = LatteWorld.doomToWorldH(prvH + (curH - prvH) * a);
            final double wz = LatteWorld.doomToWorldZ(prvY + (curY - prvY) * a);
            // While riding a moving floor, take the height the floor mesh is drawn at, which
            // the per-frame rider update also uses. Using the physics height instead lifts the
            // player to the newest floor while the rider update pulls them back, which reads
            // as jitter. Collision still uses the physics height; only the visible position
            // comes from the mesh.
            final double rideY = LatteWorld.riddenSurfaceWorldY();
            if (!Double.isNaN(rideY)) {
                wy = rideY;
            }
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("place", String.format(
                "wy=%.3f (%s) playerY=%.3f", wy,
                Double.isNaN(rideY) ? "physics" : "rideY", p.getY()));
            if (p.level().dimension().equals(com.blackwithersteve.lattedoom.net.LatteNet.DOOM_LEVEL_DIM)) {
                // Apply the target through Minecraft's own move so placed blocks clip it;
                // the level dimension is otherwise empty. Setting the position directly puts
                // the player inside block interiors, where the suffocation push-out fights
                // the integrator every frame. The clip is reconciled into the keyframes so
                // interpolation stays continuous and blocked axes stop cleanly.
                final float flyBefore = p.flyDist;
                p.move(net.minecraft.world.entity.MoverType.SELF,
                    new Vec3(wx - startWx, wy - startWy, wz - startWz));
                // Move() adds the full three-dimensional distance to flyDist, which drives
                // Minecraft's view bob far faster than the actual travel warrants at these
                // speeds, so the previous value is restored. The horizontal contribution to
                // moveDist matches what this class fed manually before and is kept.
                p.flyDist = flyBefore;
                final double cdx = p.getX() - wx, cdy = p.getY() - wy, cdz = p.getZ() - wz;
                if (cdx != 0 || cdy != 0 || cdz != 0) {
                    curX += cdx * LatteWorld.UNITS;
                    prvX += cdx * LatteWorld.UNITS;
                    curY += -cdz * LatteWorld.UNITS;
                    prvY += -cdz * LatteWorld.UNITS;
                    curH += cdy * LatteWorld.UNITS;
                    prvH += cdy * LatteWorld.UNITS;
                    if (Math.abs(cdx) > 1.0e-5) {
                        momX = 0;
                    }
                    if (Math.abs(cdz) > 1.0e-5) {
                        momY = 0;
                    }
                    if (Math.abs(cdy) > 1.0e-5) {
                        momH = 0; // landed on or struck a block: vertical momentum spent
                    }
                }
                blockGrounded = blockUnderfoot(p);
                placedWx = p.getX();
                placedWy = p.getY();
                placedWz = p.getZ();
                p.setDeltaMovement(p.getX() - startWx, p.getY() - startWy, p.getZ() - startWz);
                // Use the full setter rather than the bare flag: Minecraft pairs the
                // grounded flag with a supporting block position, and a grounded flag
                // without one is not consistently honoured.
                p.setOnGroundWithMovement(curGround || blockGrounded,
                    new Vec3(p.getX() - startWx, p.getY() - startWy, p.getZ() - startWz));
                // Move() has already added the real distance covered to moveDist.
            } else {
                // Fallback when the level is not in its own dimension: the surrounding
                // terrain intersects the level, so blocks must not clip here, or every step
                // collides with terrain the player cannot see.
                p.setPos(wx, wy, wz);
                blockGrounded = false;
                placedWx = wx;
                placedWy = wy;
                placedWz = wz;
                p.setDeltaMovement(wx - startWx, wy - startWy, wz - startWz);
                p.setOnGround(curGround);
                p.moveDist += (float) Math.hypot(wx - startWx, wz - startWz) * 0.6f;
            }
        } else {
            // Transformed player outside a level: momentum integrates in free space and
            // Minecraft's own collision clips the per-tick displacement against blocks. The
            // step height is raised to a full block, since the engine's 24-unit step scales
            // to terrain whose stairs are whole blocks, and there is still no jump.
            setStep(p, 1.0); // full-block DOOM stairs in block terrain
            glueSector = -1; // no sector to ride outside a level
            final boolean groundNow = p.onGround();
            while (ticAccum >= 1.0) {
                ticAccum -= 1.0;
                prvX = curX;
                prvY = curY;
                prvH = curH;
                // Terrain outside a level is never a Boom sector: constants, no pushers.
                thrustAndFriction(input, groundNow, fx, fy, rx, ry, fwd, side, FRICTION);
                curX += momX;
                curY += momY;
                if (!groundNow) {
                    curH += momH;
                }
            }
            final double a = ticAccum;
            final double ix = LatteWorld.doomToWorldX(prvX + (curX - prvX) * a);
            final double iy = LatteWorld.doomToWorldH(prvH + (curH - prvH) * a);
            final double iz = LatteWorld.doomToWorldZ(prvY + (curY - prvY) * a);
            // A legitimate tick never covers more than about 8 blocks, so anything larger
            // indicates inconsistent coordinate state; re-seed rather than moving the
            // player by that amount.
            if (Math.abs(ix - startWx) + Math.abs(iy - startWy) + Math.abs(iz - startWz) > 8.0) {
                com.blackwithersteve.lattedoom.diag.DoomDiag.rec("seed",
                    "displacement insane -> re-seed instead of launch");
                marineWas = false;
                return true;
            }
            p.move(net.minecraft.world.entity.MoverType.SELF,
                new Vec3(ix - startWx, iy - startWy, iz - startWz));
            // Reconcile with what the blocks allowed: shift the keyframes by the clip so
            // interpolation stays continuous, and clear momentum on blocked axes.
            final double cdx = p.getX() - ix, cdy = p.getY() - iy, cdz = p.getZ() - iz;
            curX += cdx * LatteWorld.UNITS;
            prvX += cdx * LatteWorld.UNITS;
            curY += -cdz * LatteWorld.UNITS;
            prvY += -cdz * LatteWorld.UNITS;
            curH += cdy * LatteWorld.UNITS;
            prvH += cdy * LatteWorld.UNITS;
            if (Math.abs(cdx) > 1.0e-5) {
                momX = 0;
            }
            if (Math.abs(cdz) > 1.0e-5) {
                momY = 0;
            }
            if (Math.abs(cdy) > 1.0e-5) {
                momH = 0; // landed or struck a block: vertical momentum spent
            }
            placedWx = p.getX();
            placedWy = p.getY();
            placedWz = p.getZ();
            p.setDeltaMovement(p.getX() - startWx, p.getY() - startWy, p.getZ() - startWz);
            // Move() has already updated moveDist and the grounded flag from the clip.
        }
        p.resetFallDistance(); // DOOM has no fall damage
        return true;
    }

    // ---- P_ThingHeightClip: an object standing on a floor stays attached to it while the
    // floor moves, either direction, any speed, with no fall in between. The sector stood on
    // and its height are tracked, and the player follows when that floor changes. Walking off
    // a ledge changes the sector underfoot instead, and still falls. ----
    private static int glueSector = -1;
    private static double glueFloor;

    private static void glue(DoomMap map) {
        if (glueSector < 0 || !curGround) {
            return;
        }
        final int here = map.sectorAt(curX, curY);
        if (here != glueSector) {
            return; // stepped onto different ground: no ride
        }
        final double nowFloor = map.floorNow(glueSector);
        // The tolerance is deliberately tight: the carry moves both heights together, so a
        // real rider differs by about zero, while a player on a ledge above a lowered
        // platform differs by the ledge height. Widening it drags that player down.
        if (nowFloor != glueFloor && Math.abs(curH - glueFloor) < 1.0) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("glue", String.format(
                "ride sector=%d curH %.2f -> %.2f", glueSector, curH, nowFloor));
            curH = nowFloor; // ride the mover; the tic keyframes make the glide smooth
            momH = 0;
        } else if (nowFloor != glueFloor) {
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("glue", String.format(
                "REFUSED sector=%d curH=%.2f glueFloor=%.2f nowFloor=%.2f (gap %.2f)",
                glueSector, curH, glueFloor, nowFloor, Math.abs(curH - glueFloor)));
        }
    }

    private static void rememberGlue(DoomMap map, boolean grounded) {
        if (grounded) {
            final int sec = map.sectorAt(curX, curY);
            // Only genuine floor contact counts as a ride. A player on a ledge whose centre
            // already lies over a lowered platform is not riding it; without this check the
            // rider update drops them the whole gap. Gravity brings them down first.
            if (sec >= 0 && Math.abs(curH - map.floorNow(sec)) < 1.0) {
                glueSector = sec;
                glueFloor = map.floorNow(sec);
            } else {
                glueSector = -1;
            }
        } else {
            glueSector = -1;
        }
    }

    /** Post-move probe for a placed block's collision surface directly under the feet,
     * testing a thin slice just below the bounding box. The level dimension is otherwise
     * empty, so any hit is player-built geometry. Feeds the next tic's ground state. */
    private static boolean blockUnderfoot(LocalPlayer p) {
        final net.minecraft.world.phys.AABB bb = p.getBoundingBox();
        final net.minecraft.world.phys.AABB probe = new net.minecraft.world.phys.AABB(
            bb.minX, bb.minY - 0.08, bb.minZ, bb.maxX, bb.minY + 0.001, bb.maxZ);
        for (net.minecraft.world.phys.shapes.VoxelShape s
            : p.level().getBlockCollisions(p, probe)) {
            if (!s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---- Collision against objects. Monsters and barrels block the player without ever
    // trapping them: a step is blocked only when it moves deeper into the object, while
    // sliding along it or stepping out is always allowed. The step's starting point is
    // captured here for the collision code to compare each candidate against. ----
    private static DoomCollision.ThingBlocker thingBlocker(double fromX, double fromY) {
        return (toX, toY) -> LatteWorld.increasesThingOverlap(fromX, fromY, toX, toY);
    }

    /** An engine attack threw the player upward. A transformed player runs the engine's own
     * integrator, so the impulse goes into its vertical momentum rather than into Minecraft
     * velocity; the units are already map units per tic. */
    public static void launch(double momzUnitsPerTic) {
        if (marineWas) {
            pendingLaunch = Math.max(pendingLaunch, momzUnitsPerTic);
        }
    }

    /** An upward impulse waiting to be applied at the next tic. It cannot be written into
     * momH directly: the integrator zeroes the vertical whenever the player is grounded, so
     * an impulse delivered between tics is erased before it is ever integrated. */
    private static double pendingLaunch;

    /** Drops the floor the player was riding, so nothing follows a sector they have left. */
    public static void releaseGlue() {
        glueSector = -1;
    }

    /** The sector the local player is standing on (-1 airborne/outside). */
    public static int gluedSector() {
        return glueSector;
    }

    /** World-change sweep: no physics state may survive into another world/session. */
    public static void resetSession() {
        marineWas = false;
        glueSector = -1;
        momX = momY = momH = 0;
        ticAccum = 0;
        placedWx = placedWy = placedWz = 0;
        curGround = false;
        blockGrounded = false;
        lastStep = -1;
    }

    /** The player was moved by something authoritative, such as a teleport follow or a
     * start delivery. The origin-relative keyframes no longer describe them, so they are
     * re-seeded at the current position on the next tick, at a standstill as a teleport
     * leaves the player in the original. */
    public static void forceReseed() {
        marineWas = false;
        glueSector = -1;
        momX = momY = momH = 0;
        blockGrounded = false;
    }

    /**
     * The equivalent of {@code P_ChangeSector}'s carry, called as soon as a floor height
     * is applied: the physics state, meaning the keyframes and the floor reference, moves
     * with the plane. The visible carry happens per frame in the rider update; moving the
     * player here, at keyframe boundaries, makes the ride visibly step.
     */
    public static void rideFloor(double deltaU) {
        if (deltaU == 0) {
            return;
        }
        curH += deltaU;
        prvH += deltaU;
        glueFloor += deltaU;
    }

    /** Called after the rider update moves the player along the rendered platform, to keep
     * the integrator's recorded placement position in step so the ride is not mistaken for
     * an external teleport. */
    public static void syncRiddenY(double worldY) {
        placedWy = worldY;
    }

    /** The player's movement-speed attribute as a ratio of the unmodified default, clamped
     * so an extreme effect cannot push the integrator past its own move cap. */
    private static double speedScale(LocalPlayer p) {
        final var attr = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (attr == null) {
            return 1.0;
        }
        // The entity's own base value, not the attribute's registry default. Those differ:
        // movement speed is registered with a default of 0.7 while a player's base is 0.1,
        // so dividing by the registry default leaves an unaffected player at a fifth speed.
        final double base = attr.getBaseValue();
        if (base <= 0.0) {
            return 1.0;
        }
        return Math.max(0.2, Math.min(4.0, attr.getValue() / base));
    }

    /** One DOOM tic of P_MovePlayer accel + P_XYMovement friction, shared by both worlds.
     * {@code friction} is the per-tic momentum factor: the standard constant everywhere
     * except a Boom friction sector, where the snapshot's per-sector value applies. */
    private static void thrustAndFriction(Vec3 input, boolean ground,
                                          double fx, double fy, double rx, double ry,
                                          double fwd, double side, double friction) {
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
            if (input.z == 0 && input.x == 0 && Math.hypot(momX, momY) < STOPSPEED) {
                momX = momY = 0;
            }
            momH = 0;
        } else {
            momH -= GRAVITY; // no air control and no jump: momentum alone carries the player
        }
        if (pendingLaunch > 0) {
            // Applied after the grounded branch, which would otherwise zero it, and it lifts
            // the player off the floor so the next tic integrates the rise.
            momH = pendingLaunch;
            pendingLaunch = 0;
            blockGrounded = false;
        }
        momX = Math.max(-MAXMOVE, Math.min(MAXMOVE, momX));
        momY = Math.max(-MAXMOVE, Math.min(MAXMOVE, momY));
    }

    // ---- Boom friction and pushers, the Minecraft-side half of that support. A
    // transformed player never runs through the engine's own movement code, because this
    // integrator is their physics, so the snapshot reports what the engine would have
    // computed: the per-sector friction and move factor for the floor beneath them, and the
    // summed pusher force at their position. Levels that define neither publish empty
    // arrays and zero force, and every branch below then short-circuits. ----

    /** ORIG_FRICTION/65536: a snapshot sector holding exactly this is a vanilla floor. */
    private static final double ORIG_FRICTION_D = 0xE800 / 65536.0;
    /** Boom MORE_FRICTION_MOMENTUM (15000 fixed) in map units/tic. */
    private static final double MUD_MOMENTUM = 15000 / 65536.0;

    /**
     * Boom's {@code P_GetMoveFactor} as a scale on the standard thrust: low-friction
     * floors give poor footing and high-friction floors slow the player down, easing as
     * momentum builds. The integer shifts of the original are reproduced exactly.
     */
    private static double thrustScale(double[] secFriction, int[] secMoveFactor, int sec) {
        int mf = secMoveFactor[sec];
        if (secFriction[sec] < ORIG_FRICTION_D) { // below standard: scale up with momentum
            final double momentum = aproxDist(momX, momY);
            if (momentum > MUD_MOMENTUM * 4) {
                mf <<= 3;
            } else if (momentum > MUD_MOMENTUM * 2) {
                mf <<= 2;
            } else if (momentum > MUD_MOMENTUM) {
                mf <<= 1;
            }
        }
        return mf / 2048.0;
    }

    /** P_AproxDistance's estimate (|dx| + |dy| - min/2), doubles: same branch points
     * as the engine uses for its momentum thresholds. */
    private static double aproxDist(double dx, double dy) {
        final double ax = Math.abs(dx), ay = Math.abs(dy);
        return ax + ay - Math.min(ax, ay) / 2.0;
    }

    // ---- Step height by context. An untransformed player keeps Minecraft's 0.6. A
    // transformed player inside a level uses the engine's own 24-unit step, which is 0.75
    // blocks, so a slab is a legal step while a full block is too tall to climb and there
    // is no jump. Outside a level the step is a full block, since terrain stairs are whole
    // blocks. ----
    private static double lastStep = -1;

    private static void setStep(LocalPlayer p, double v) {
        if (lastStep == v) {
            return;
        }
        final var attr = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (attr != null) {
            attr.setBaseValue(v);
            lastStep = v;
        }
    }

    private static void restoreStep(LocalPlayer p) {
        setStep(p, 0.6);
    }

    /**
     * Called at the start of {@code travel()} to decide whether the level's rules apply
     * this tick. Any player may walk a level without transforming, so this is gated on
     * being inside the level rather than on the player's form.
     */
    public static void beforeTravel(LocalPlayer p) {
        active = !p.getAbilities().flying && !p.isFallFlying() && !p.isSwimming()
            && !p.isInWater() && !p.isInLava() && !p.isPassenger() && !p.onClimbable()
            && LatteWorld.map() != null
            && LatteWorld.insideLevel(p.getX(), p.getY(), p.getZ());
        if (active) {
            preX = p.getX();
            preY = p.getY();
            preZ = p.getZ();
            preMoveDist = p.moveDist;
            preFlyDist = p.flyDist;
        }
    }

    /**
     * Called at the end of {@code travel()}. Vanilla has just moved the player through
     * empty space, since the level contains no blocks, so the movement is rewound and its
     * displacement re-applied through the level's collision instead.
     */
    public static void afterTravel(LocalPlayer p) {
        if (!active) {
            return;
        }
        final DoomMap map = LatteWorld.map();
        if (map == null) {
            return;
        }
        final double dx = p.getX() - preX;
        final double dy = p.getY() - preY;
        final double dz = p.getZ() - preZ;

        final double x = LatteWorld.worldToDoomX(preX);
        final double yD = LatteWorld.worldToDoomY(preZ);
        double h = LatteWorld.worldToDoomH(preY);
        // The same floor-follow applies here, but this player's position is the drawn
        // height, up to a tic ahead of the delayed collision floor, so standing on the drawn
        // surface counts as riding too. Otherwise the check reads them as airborne and the
        // ride flickers at the tick rate. The ledge rule still applies.
        if (glueSector >= 0 && map.sectorAt(x, yD) == glueSector) {
            final double nowFloor = map.floorNow(glueSector);
            final double rideW = LatteWorld.riddenSurfaceWorldY(); // drawn surface, world Y
            final boolean onDrawn = !Double.isNaN(rideW) && Math.abs(preY - rideW) < 1.0;
            if (nowFloor != glueFloor && (Math.abs(h - glueFloor) < 1.0 || onDrawn)) {
                h = nowFloor;
            }
        }
        // World axes to map axes: x maps to x, map y is the negated world z, and world y
        // is the vertical. Movement out of an object is allowed only from deep inside one;
        // merely touching a monster still blocks.
        final DoomCollision.ThingBlocker things = thingBlocker(x, yD);
        // Clip an untransformed player at their own Minecraft box rather than a transformed
        // player's 56 units. At 1.8 blocks they are 50.4, and clipping them at 56 refuses
        // openings they visibly fit through and stops them on a step up into a low ceiling.
        final DoomCollision.Result r = DoomCollision.move(map, x, yD, h,
            dx * LatteWorld.UNITS, -dz * LatteWorld.UNITS, dy * LatteWorld.UNITS,
            things, p.getBbHeight() * LatteWorld.UNITS,
            p.getBbWidth() * 0.5 * LatteWorld.UNITS);
        if (r.onGround()) {
            final int sec = map.sectorAt(r.x(), r.y());
            // The same rule as for a transformed player: only genuine floor contact counts
            // as a ride, so a player on a ledge above a lowered platform falls rather than
            // being moved down to it.
            if (sec >= 0 && Math.abs(r.h() - map.floorNow(sec)) < 1.0) {
                glueSector = sec;
                glueFloor = map.floorNow(sec);
            } else {
                glueSector = -1;
            }
        } else {
            glueSector = -1;
        }

        final double tx = LatteWorld.doomToWorldX(r.x());
        final double tz = LatteWorld.doomToWorldZ(r.y());
        // When riding a moving floor, use the height the floor mesh is drawn at rather than
        // the freshly computed collision height, so this per-tick placement agrees with the
        // per-frame rider update and the ride stays smooth.
        double ridY = LatteWorld.doomToWorldH(r.h());
        final double rideSurf = LatteWorld.riddenSurfaceWorldY();
        if (!Double.isNaN(rideSurf)) {
            ridY = rideSurf;
        }
        // One grounded value for the whole tail: the level's collision result, widened
        // inside the level dimension by a placed block under the feet.
        boolean grounded = r.onGround();
        final boolean inLevelDim =
            p.level().dimension().equals(com.blackwithersteve.lattedoom.net.LatteNet.DOOM_LEVEL_DIM);
        if (inLevelDim) {
            // Rewind, then apply the level-clipped displacement through Minecraft's own
            // move, so a slide along a wall cannot redirect the player into a placed block:
            // Vanilla clipped the input displacement, but not the vector the slide produced.
            p.setPos(preX, preY, preZ);
            p.moveDist = preMoveDist;
            p.move(net.minecraft.world.entity.MoverType.SELF,
                new Vec3(tx - preX, ridY - preY, tz - preZ));
            // Restored after the move, which adds the three-dimensional distance to flyDist
            // and drives the view bob faster than the travel warrants. Restoring beforehand
            // would let the move add it again.
            p.flyDist = preFlyDist;
            // The level's collision owns the grounded flag: the move above ran with an
            // already-clipped vertical delta and can never conclude that the player landed,
            // which would leave them airborne to vanilla (air acceleration, no ground
            // friction on blocks, jumps refused). Use setOnGroundWithMovement rather than the
            // bare flag, because Minecraft pairs the flag with a supporting block position
            // and does not honour one without the other.
            grounded = r.onGround() || blockUnderfoot(p);
            p.setOnGroundWithMovement(grounded,
                new Vec3(p.getX() - preX, p.getY() - preY, p.getZ() - preZ));
            com.blackwithersteve.lattedoom.diag.DoomDiag.rec("ground", String.format(
                "ground=%s (doom=%s) dmY=%.3f y=%.2f",
                grounded, r.onGround(), p.getDeltaMovement().y, p.getY()));
        } else {
            // Fallback for a level raised in the overworld rather than its own dimension.
            // Terrain intersecting the level (leaves, hilltops) would collide with every
            // step, so the level's collision is applied directly and blocks are ignored.
            p.setPos(tx, ridY, tz);
            // The walk counter (view-bob + step sounds) normally accumulates inside
            // move(), which is bypassed here, so it is given the real distance covered.
            p.moveDist = preMoveDist + (float) Math.hypot(tx - preX, tz - preZ) * 0.6f;
            p.flyDist = preFlyDist;
        }

        // Velocity follows the clip result: walls zero or redirect it, landing zeroes fall
        final Vec3 dm = p.getDeltaMovement();
        double ndx = dm.x, ndy = dm.y, ndz = dm.z;
        if (r.slid()) {
            final double vx = dm.x, vy = -dm.z; // into doom axes
            final double t = vx * r.slideDirX() + vy * r.slideDirY();
            ndx = t * r.slideDirX();
            ndz = -(t * r.slideDirY());
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
        // Pushers reach this player as a direct velocity addition. The per-tick amount is
        // the engine's per-tic momentum gain rescaled twice by 35/20: once for the tics
        // elapsed per Minecraft tick, once because momentum is displacement per engine tic
        // while velocity is displacement per Minecraft tick.
        //
        // Friction floors deliberately do not apply here: overriding acceleration would
        // replace the Minecraft movement this mode exists to keep. A transformed player is
        // subject to both friction and pushers; an untransformed one only to pushers.
        final WorldSnapshot snap = LatteWorld.worldSnap();
        if (snap != null && (snap.playerPushX != 0 || snap.playerPushY != 0)) {
            final double k = TICS_PER_MC_TICK * TICS_PER_MC_TICK / LatteWorld.UNITS;
            ndx += snap.playerPushX * k;
            ndz += -snap.playerPushY * k; // map +y is world -z
        }
        p.setDeltaMovement(ndx, ndy, ndz);
        if (!inLevelDim) {
            // In the level dimension the flag was already set with its supporting block
            // position; the bare setter drops that pairing and reads as airborne again.
            p.setOnGround(grounded);
        }
        p.resetFallDistance(); // sector floors are the ground and DOOM has no fall damage
    }

    private DoomMovement() {}
}
