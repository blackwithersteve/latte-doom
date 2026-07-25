package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Translates Minecraft attacks into damage the engine understands, the counterpart to
 * {@link DoomCombat}.
 *
 * <p>An untransformed player's melee swing and any flying arrow are tested against the
 * shootable map objects in the current snapshot. Hits are sent to the client running the
 * engine, where {@code P_DamageMobj} is applied with the attacker's own body as the
 * source, so monsters retaliate against the correct player, barrels explode and all damage
 * dice remain the engine's. Minecraft damage is converted at five DOOM hit points per
 * point of damage, the inverse of the health conversion used elsewhere.
 */
public final class MinecraftCombat {

    private static final double MELEE_REACH_U = 84.0;   // MC's ~3-block arm, in map units
    private static final Set<Integer> arrowsSpent = new HashSet<>();

    /**
     * DOOM hit points awarded per point of Minecraft attack damage for a melee swing
     * against a monster. The strict inverse of the health conversion would be 5.0; this
     * higher value is a deliberate balance choice, so that Minecraft melee is competitive
     * with DOOM weapons. Because attack damage already encodes the weapon tier, every
     * weapon scales from it: a wooden or golden sword deals 48, stone 60, iron 72, which
     * kills an imp outright, diamond 84 and netherite 96, with axes higher still.
     */
    private static final double MELEE_DOOM_SCALE = 12.0;

    /** Handles an attack that hit no Minecraft entity by testing the DOOM map objects
     * under the crosshair. Returns true if one of them took the hit. */
    public static boolean meleeAttack(Minecraft mc) {
        final LocalPlayer p = mc.player;
        final WorldSnapshot s = snap();
        if (p == null || s == null) {
            return false;
        }
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            return false; // a real Minecraft entity is in reach: vanilla owns this swing
        }
        final Vec3 eye = p.getEyePosition();
        final Vec3 dir = p.getLookAngle();
        final int target = rayMobj(s, eye, dir, MELEE_REACH_U);
        if (target < 0) {
            return false;
        }
        final double mcDamage = p.getAttributeValue(Attributes.ATTACK_DAMAGE)
            * Math.max(0.2, p.getAttackStrengthScale(0.5f));
        final int doomHp = Math.max(1, (int) Math.round(mcDamage * MELEE_DOOM_SCALE));
        LOGGER.info("MC->DOOM: melee hit mobj {} for {} hp", s.mId[target], doomHp);
        com.blackwithersteve.lattedoom.net.LatteNet.sendDoomHit(s.mId[target], doomHp, -1);
        return true;
    }

    /** Arrows (and tridents) in flight vs the engine's shootable things, each tick. */
    public static void tickArrows(Minecraft mc) {
        final WorldSnapshot s = snap();
        if (mc.level == null || mc.player == null || s == null || LatteWorld.map() == null) {
            return;
        }
        // Only test near the raised level, since all monsters are inside it.
        final AABB levelBox = new AABB(
            LatteWorld.doomToWorldX(-40000), 0, LatteWorld.doomToWorldZ(40000),
            LatteWorld.doomToWorldX(40000), 400, LatteWorld.doomToWorldZ(-40000));
        for (final var e : mc.level.getEntitiesOfClass(AbstractArrow.class, levelBox)) {
            final Vec3 vel = e.getDeltaMovement();
            if (vel.lengthSqr() < 0.05 || arrowsSpent.contains(e.getId())) {
                continue; // stuck in the ground, or already translated
            }
            final Vec3 from = e.position();
            final int target = rayMobj(s, from, vel.normalize(),
                Math.max(16.0, vel.length() * LatteWorld.UNITS * 1.5));
            if (target >= 0) {
                arrowsSpent.add(e.getId());
                final int mcDamage = Math.max(2, (int) Math.ceil(vel.length() * 2.0));
                LOGGER.info("MC->DOOM: arrow {} hit mobj {} for {} hp",
                    e.getId(), s.mId[target], mcDamage * 5);
                com.blackwithersteve.lattedoom.net.LatteNet.sendDoomHit(s.mId[target],
                    mcDamage * 5, e.getId());
                continue;
            }
            // Level geometry stops arrows as well: sample this tick's flight, treating a
            // dip below a floor, a rise above a ceiling, or leaving every sector while
            // inside the level as an impact.
            if (hitsGeometry(from, from.add(vel))) {
                arrowsSpent.add(e.getId());
                LOGGER.info("MC->DOOM: arrow {} hit level geometry", e.getId());
                com.blackwithersteve.lattedoom.net.LatteNet.sendDoomHit(-1, 1, e.getId());
            }
        }
        if (arrowsSpent.size() > 64) {
            arrowsSpent.clear(); // entity ids recycle slowly, so a periodic clear suffices
        }
    }

    /** Nearest shootable mobj along a world-space ray (cylinder test in doom space),
     * or -1. Range in map units. */
    private static int rayMobj(WorldSnapshot s, Vec3 from, Vec3 dir, double rangeU) {
        if (s.mShootable == null) {
            return -1;
        }
        final double ox = LatteWorld.worldToDoomX(from.x);
        final double oy = LatteWorld.worldToDoomY(from.z);
        final double oh = LatteWorld.worldToDoomH(from.y);
        final double dx = dir.x * LatteWorld.UNITS;
        final double dy = -dir.z * LatteWorld.UNITS;
        final double dh = dir.y * LatteWorld.UNITS;
        final double dlen = Math.hypot(dx, dy);
        if (dlen < 1.0e-6) {
            return -1;
        }
        // Players' mirrored bodies are never targets here: the local player's own body
        // sits at the ray origin, which would make punching thin air self-damaging, and
        // other players are handled as Minecraft entities rather than engine objects.
        java.util.Set<Integer> possessed = null;
        if (s.rbMobjId != null) {
            possessed = new HashSet<>();
            for (int id : s.rbMobjId) {
                possessed.add(id);
            }
        }
        int best = -1;
        double bestT = Double.MAX_VALUE;
        for (int i = 0; i < s.mobjCount; i++) {
            if (!s.mShootable[i] || (i == s.playerMobj && (LatteWorld.playMode() || s.remote))) {
                continue;
            }
            if (possessed != null && possessed.contains(s.mId[i])) {
                continue;
            }
            // Closest approach of the horizontal ray to the object's column.
            final double px = s.mx[i] - ox, py = s.my[i] - oy;
            final double t = (px * dx + py * dy) / (dlen * dlen);
            if (t < 0 || t * dlen > rangeU) {
                continue;
            }
            final double cx = ox + dx * t, cy = oy + dy * t;
            final double miss = Math.hypot(s.mx[i] - cx, s.my[i] - cy);
            if (miss > s.mRadius[i] + 12.0) {
                continue;
            }
            // Vertical window: the eye height along the ray against the object's body.
            final double hAt = oh + dh * t;
            if (hAt < s.mz[i] - 8.0 || hAt > s.mz[i] + 88.0) {
                continue;
            }
            if (t < bestT) {
                bestT = t;
                best = i;
            }
        }
        return best;
    }

    /** Whether this world-space segment crossed into the level's solid geometry. */
    private static boolean hitsGeometry(Vec3 from, Vec3 to) {
        final var map = LatteWorld.map();
        if (map == null) {
            return false;
        }
        boolean wasInside = false;
        for (int i = 0; i <= 6; i++) {
            final double f = i / 6.0;
            final double wx = from.x + (to.x - from.x) * f;
            final double wy = from.y + (to.y - from.y) * f;
            final double wz = from.z + (to.z - from.z) * f;
            if (!LatteWorld.insideLevel(wx, wy, wz)) {
                continue; // outside the envelope entirely: open Minecraft air
            }
            final int sec = map.sectorAt(LatteWorld.worldToDoomX(wx), LatteWorld.worldToDoomY(wz));
            if (sec < 0) {
                if (wasInside) {
                    return true; // flew out of all sectors mid-level: a one-sided wall
                }
                continue;
            }
            final double h = LatteWorld.worldToDoomH(wy);
            if (wasInside && (h < map.floorNow(sec) || h > map.ceilNow(sec))) {
                return true; // under the floor / into the ceiling of the room ahead
            }
            wasInside = true;
        }
        return false;
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static WorldSnapshot snap() {
        return LatteWorld.worldSnap();
    }

    private MinecraftCombat() {}
}
