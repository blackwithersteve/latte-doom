package com.blackwithersteve.lattedoom.play;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Translates DOOM weapon fire into damage against Minecraft entities.
 *
 * <p>The engine owns the trigger, the weapon state machine and the ammunition. This class
 * observes the resulting discharges: ammunition decrements and melee swing frames, and
 * casts the equivalent Minecraft ray at the same moment, using DOOM's own ranges, spreads
 * and damage dice. Targets are Minecraft mobs and other players; monsters remain the
 * engine's business and are hit by its own hitscan code.
 *
 * <p>Hits are sent to the server so that clients other than the host behave identically,
 * and they arrive attributed to the shooter, so mobs retaliate against the right player.
 *
 * <p>Currently limited to hitscan weapons: fist, chainsaw, pistol, shotgun and chaingun.
 * Rockets, plasma and the BFG travel as engine projectiles, and their effect on Minecraft
 * entities is not implemented yet.
 */
public final class DoomCombat {

    private static final double MISSILE_RANGE_BLOCKS = 2048.0 / LatteWorld.UNITS; // 73
    private static final double MELEE_RANGE_BLOCKS = 64.0 / LatteWorld.UNITS;     // 2.3
    private static final double SPREAD_DEG = 5.625; // DOOM's <<18 angle jitter

    private static final Random DICE = new Random(); // distributions match vanilla dice

    private static int lastAmmoBullets = -1, lastAmmoShells = -1;
    private static int lastWeapon = -1;
    private static int lastWFrame = -1;
    private static long lastShotMs;

    /** Called once per client tick with this client's own engine snapshot. */
    public static void tick(Minecraft mc, WorldSnapshot suit) {
        final LocalPlayer p = mc.player;
        if (p == null || suit == null || !LatteWorld.marineForm()) {
            reset(suit);
            return;
        }
        final int weapon = suit.readyWeapon;
        final int bullets = suit.ammo != null && suit.ammo.length > 0 ? suit.ammo[0] : -1;
        final int shells = suit.ammo != null && suit.ammo.length > 1 ? suit.ammo[1] : -1;
        if (weapon != lastWeapon) {
            // While switching weapons, ammunition differences are not discharges.
            lastWeapon = weapon;
            lastAmmoBullets = bullets;
            lastAmmoShells = shells;
            lastWFrame = suit.wFrame & 0x7FFF;
            return;
        }

        // ---- Discharge detection, based on the engine's own ammunition bookkeeping. ----
        int shots = 0;
        if (weapon == 1 || weapon == 3) { // pistol, chaingun: bullets
            if (lastAmmoBullets >= 0 && bullets >= 0 && bullets < lastAmmoBullets) {
                shots = Math.min(4, lastAmmoBullets - bullets);
            }
        } else if (weapon == 2) { // shotgun: shells (7 pellets each)
            if (lastAmmoShells >= 0 && shells >= 0 && shells < lastAmmoShells) {
                shots = Math.min(2, lastAmmoShells - shells);
            }
        } else if (weapon == 8) { // super shotgun: 2 shells per blast, 20 pellets
            if (lastAmmoShells >= 0 && shells >= 0 && shells < lastAmmoShells) {
                shots = Math.min(1, (lastAmmoShells - shells) / 2);
            }
        }
        lastAmmoBullets = bullets;
        lastAmmoShells = shells;

        // ---- Melee weapons consume no ammunition, so discharges are detected from the
        // weapon sprite's swing frames instead. This is deliberately not gated on the fire
        // button, because a tapped swing plays out long after the button is released; the
        // engine only reaches these frames when it has accepted a shot. ----
        int melee = 0;
        final int wf = suit.wFrame & 0x7FFF;
        if (wf != lastWFrame) {
            if (weapon == 0 && lastWFrame == 0 && wf > 0) {
                melee = 1; // fist: one punch per swing cycle (idle A -> swing)
            } else if (weapon == 7 && wf >= 2) {
                melee = 1; // chainsaw: A_Saw alternates frames C/D ~ every 4 tics
            }
        }
        lastWFrame = wf;

        if (shots == 0 && melee == 0) {
            return;
        }
        final boolean refire = System.currentTimeMillis() - lastShotMs < 500;
        lastShotMs = System.currentTimeMillis();
        // Swing the Minecraft arm on each discharge. Vanilla attacks are cancelled while
        // transformed, so this is what drives the attack frame other players see.
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        // ---- Translate each discharge into Minecraft-space rays. ----
        final Map<Integer, Integer> damageByEntity = new HashMap<>();
        for (int s = 0; s < shots; s++) {
            switch (weapon) {
                case 1 -> ray(mc, p, refire ? SPREAD_DEG : 0, MISSILE_RANGE_BLOCKS,
                    bulletDamage(), damageByEntity);
                case 3 -> ray(mc, p, refire ? SPREAD_DEG : 0, MISSILE_RANGE_BLOCKS,
                    bulletDamage(), damageByEntity);
                case 2 -> {
                    for (int i = 0; i < 7; i++) { // A_FireShotgun: 7 pellets, always spread
                        ray(mc, p, SPREAD_DEG, MISSILE_RANGE_BLOCKS, bulletDamage(), damageByEntity);
                    }
                }
                case 8 -> { // A_FireShotgun2 (super shotgun): 20 pellets, wide spread
                    for (int i = 0; i < 20; i++) {
                        ray(mc, p, SPREAD_DEG * 2.0, MISSILE_RANGE_BLOCKS, bulletDamage(), damageByEntity);
                    }
                }
                default -> { }
            }
        }
        for (int m = 0; m < melee; m++) {
            // A_Punch / A_Saw: (1d10)*2, melee range, slight angle wobble
            ray(mc, p, 2.0, MELEE_RANGE_BLOCKS, 2 * (1 + DICE.nextInt(10)), damageByEntity);
        }
        for (Map.Entry<Integer, Integer> e : damageByEntity.entrySet()) {
            // Clamp to the server's per-hit cap: a point-blank super shotgun volley can
            // exceed it, and an over-cap packet is rejected in full, losing the whole hit.
            com.blackwithersteve.lattedoom.net.LatteNet.sendHit(e.getKey(), Math.min(120, e.getValue()));
        }
    }

    /** P_GunShot's dice: 5 * (1d3). */
    private static int bulletDamage() {
        return 5 * (1 + DICE.nextInt(3));
    }

    /** Casts one hitscan ray in Minecraft space: from the eye, along the look direction
     * with DOOM's yaw jitter applied, clipped by blocks, against the first living entity. */
    private static void ray(Minecraft mc, LocalPlayer p, double spreadDeg, double range,
                            int damageHp, Map<Integer, Integer> out) {
        final Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getLookAngle();
        if (spreadDeg > 0) {
            final double jitter = Math.toRadians((DICE.nextDouble() * 2.0 - 1.0) * spreadDeg);
            final double cos = Math.cos(jitter), sin = Math.sin(jitter);
            dir = new Vec3(dir.x * cos - dir.z * sin, dir.y, dir.x * sin + dir.z * cos);
        }
        Vec3 end = eye.add(dir.scale(range));
        final HitResult block = mc.level.clip(new ClipContext(eye, end,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
        }
        final EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.level, p, eye, end,
            new AABB(eye, end).inflate(1.0),
            e -> e instanceof LivingEntity && e.isAlive() && e != p && !e.isSpectator(),
            0.3f);
        if (hit != null && hit.getEntity() != null) {
            out.merge(hit.getEntity().getId(), damageHp, Integer::sum);
        }
    }

    private static void reset(WorldSnapshot suit) {
        lastWeapon = suit != null ? suit.readyWeapon : -1;
        lastAmmoBullets = suit != null && suit.ammo != null ? suit.ammo[0] : -1;
        lastAmmoShells = suit != null && suit.ammo != null && suit.ammo.length > 1
            ? suit.ammo[1] : -1;
    }

    private DoomCombat() {}
}
