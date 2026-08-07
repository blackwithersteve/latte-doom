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
 * The other half of the combat translation: Minecraft attacks become damage the engine
 * understands. An untransformed player's fist or sword swing and any flying arrow are
 * tested against the snapshot's shootable mobjs; hits ship to the level owner's engine
 * (DoomHitC2S) where P_DamageMobj runs with the attacker's possessed body as the source,
 * so demons fight back at whoever hit them, barrels explode, and the dice are the
 * engine's own. Scale: Minecraft damage x5 = DOOM hit points, the inverse of the hearts
 * law.
 */
public final class MinecraftCombat {

    private static final double MELEE_REACH_U = 84.0;   // MC's ~3-block arm, in map units
    private static final Set<Integer> arrowsSpent = new HashSet<>();

    /**
     * DOOM hit points per point of Minecraft ATTACK_DAMAGE for a melee swing against
     * demons. The plain inverse of the hearts law would be 5.0; this is deliberately
     * higher so melee is tiered by weapon and worth using. ATTACK_DAMAGE already encodes
     * the tier, so everything scales from it: wood and gold 4 to 48, stone 5 to 60, iron
     * 6 to 72 (which one-shots an imp), diamond 7 to 84, netherite 8 to 96, and axes
     * higher still. Iron at 72 is the reference point.
     */
    private static final double MELEE_DOOM_SCALE_DEFAULT = 18.0;

    /** The configured scale, or the default when no config has loaded yet. */
    private static double meleeScale() {
        final var cfg = com.blackwithersteve.lattedoom.LatteDoomClient.config();
        return cfg != null ? cfg.meleeScale : MELEE_DOOM_SCALE_DEFAULT;
    }

    /**
     * Wakes the monsters that can hear the player. The engine raises this itself whenever a
     * DOOM weapon fires, so only Minecraft attacks, which happen outside it, have to say so.
     * Without it a level stays asleep around a player shooting a bow across a room.
     */
    public static void alertMonsters() {
        final var host = com.blackwithersteve.lattedoom.LatteDoomClient.host();
        if (host != null && LatteWorld.playMode()) {
            host.alertMonsters();
        }
    }

    /**
     * Raises any gun-triggered special the shot crosses, taking the nearest line first, as
     * P_LineAttack does. Only DOOM weapons run through the engine's own attack code, so a
     * Minecraft arrow or swing would otherwise pass straight through a shootable switch.
     */
    public static void shootSpecials(Vec3 from, Vec3 dir, double rangeU) {
        final var host = com.blackwithersteve.lattedoom.LatteDoomClient.host();
        final com.blackwithersteve.lattedoom.render.DoomMap map = LatteWorld.map();
        if (host == null || map == null || !LatteWorld.playMode()) {
            return;
        }
        final double ox = LatteWorld.worldToDoomX(from.x);
        final double oy = LatteWorld.worldToDoomY(from.z);
        final double ex = ox + dir.x * rangeU;
        final double ey = oy - dir.z * rangeU;
        int best = -1;
        double bestT = Double.MAX_VALUE;
        for (int i = 0; i < map.lines.size(); i++) {
            final com.blackwithersteve.lattedoom.render.DoomMap.Line l = map.lines.get(i);
            if (l.special() == 0) {
                continue;
            }
            final double t = segmentHit(ox, oy, ex, ey, l);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = i;
            }
        }
        if (best >= 0) {
            host.requestShoot(best);
        }
    }

    /** Where along (ax,ay)->(bx,by) the linedef is crossed, or -1 when it is not. */
    private static double segmentHit(double ax, double ay, double bx, double by,
                                     com.blackwithersteve.lattedoom.render.DoomMap.Line l) {
        final double rx = bx - ax, ry = by - ay;
        final double sx = l.x2() - l.x1(), sy = l.y2() - l.y1();
        final double den = rx * sy - ry * sx;
        if (Math.abs(den) < 1.0e-9) {
            return -1;
        }
        final double t = ((l.x1() - ax) * sy - (l.y1() - ay) * sx) / den;
        final double u = ((l.x1() - ax) * ry - (l.y1() - ay) * rx) / den;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? t : -1;
    }

    /** A plain-Steve attack click that hit no Minecraft entity: try the DOOM things
     * under the crosshair. Returns true if a demon (or barrel) took the hit. */
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
            // A swing that hits no monster can still hit a wall, and a shootable switch is
            // exactly the case this was added for. The noise carries either way.
            alertMonsters();
            shootSpecials(eye, dir, MELEE_REACH_U);
            return false;
        }
        final double mcDamage = p.getAttributeValue(Attributes.ATTACK_DAMAGE)
            * Math.max(0.2, p.getAttackStrengthScale(0.5f));
        alertMonsters();
        shootSpecials(eye, dir, MELEE_REACH_U);
        // Clamped to the server's per-hit ceiling. An over-cap packet is rejected whole
        // rather than trimmed, so a high tier weapon under a strength effect would land for
        // nothing at all.
        final int doomHp = Math.max(1,
            Math.min(200, (int) Math.round(mcDamage * meleeScale())));
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
        // only bother near the raised level (the demons all live there)
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
            // level GEOMETRY stops arrows too: sample this tick's flight — dipping under
            // a floor, over a ceiling, or out of all sectors mid-level = a wall hit
            if (hitsGeometry(from, from.add(vel))) {
                arrowsSpent.add(e.getId());
                // An arrow striking a wall raises any gun-triggered special on it, the same
                // as a hitscan would.
                shootSpecials(from, vel.normalize(), vel.length() * LatteWorld.UNITS * 1.5);
                LOGGER.info("MC->DOOM: arrow {} hit level geometry", e.getId());
                com.blackwithersteve.lattedoom.net.LatteNet.sendDoomHit(-1, 1, e.getId());
            }
        }
        // Forget an arrow once it no longer exists, rather than clearing the whole set on a
        // size threshold. Clearing wholesale forgets arrows that are still in flight and
        // already counted, which lets one of them deal its damage a second time.
        arrowsSpent.removeIf(id -> {
            final var e = mc.level.getEntity(id);
            return e == null || !e.isAlive();
        });
        if (arrowsSpent.size() > 512) {
            arrowsSpent.clear(); // backstop only; the sweep above should keep it small
        }
    }

    /** Nearest SHOOTABLE mobj along a world-space ray (cylinder test in doom space),
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
        // players' possessed bodies are never doom-punch targets: your OWN body sits at
        // the ray origin (a joining player hurt themselves punching air), and OTHER
        // players are Minecraft-combat targets, not engine things
        java.util.Set<Integer> possessed = null;
        if (s.rbMobjId != null) {
            possessed = new HashSet<>();
            for (int id : s.rbMobjId) {
                possessed.add(id);
            }
        }
        int best = -1;
        double bestT = Double.MAX_VALUE;
        // Aim at the drawn position, not the newest engine one. Sprites are interpolated on
        // the delayed render clock, so a moving target's true position leads its body by up
        // to the render latency, and a swing aimed at what is on screen misses entirely.
        final double[] at = new double[3];
        for (int i = 0; i < s.mobjCount; i++) {
            if (!s.mShootable[i] || (i == s.playerMobj && (LatteWorld.playMode() || s.remote))) {
                continue;
            }
            if (possessed != null && possessed.contains(s.mId[i])) {
                continue;
            }
            // closest approach of the 2D ray to the mobj column
            LatteWorld.thingDrawn(s, i, at);
            final double px = at[0] - ox, py = at[1] - oy;
            final double t = (px * dx + py * dy) / (dlen * dlen);
            if (t < 0 || t * dlen > rangeU) {
                continue;
            }
            final double cx = ox + dx * t, cy = oy + dy * t;
            final double miss = Math.hypot(at[0] - cx, at[1] - cy);
            if (miss > s.mRadius[i] + 12.0) {
                continue;
            }
            // vertical window: eye height along the ray vs the thing's body column
            final double hAt = oh + dh * t;
            if (hAt < at[2] - 8.0 || hAt > at[2] + 88.0) {
                continue;
            }
            if (t < bestT) {
                bestT = t;
                best = i;
            }
        }
        return best;
    }

    /** Did this world-space segment cross into the level's solid geometry? */
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
