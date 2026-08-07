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
 * Translates the marine's own fire into Minecraft damage. The engine owns the trigger,
 * the state machine and the ammo; this class watches the transformed player's discharges
 * (ammo drops and melee swing frames) and fires the equivalent Minecraft ray at the same
 * moment, using vanilla DOOM ranges, spreads and damage dice against Minecraft entities.
 * Other players are included, so co-op friendly fire behaves as DOOM's does. Hits ship to
 * the server (HitC2S) so guests behave like the host, and arrive attributed to the
 * shooter so mobs aggro back. Engine things stay the engine's business: an imp is shot by
 * the engine's hitscan, a creeper by this translator.
 *
 * v1 scope: hitscan weapons (fist, pistol, shotgun, chaingun, chainsaw). Rockets,
 * plasma and BFG fly as engine projectiles — their MC-side blast is a later step.
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

    /** Call once per client tick with the SUIT snapshot (our own engine). */
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
            // switching hands: ammo deltas across the switch are not discharges
            lastWeapon = weapon;
            lastAmmoBullets = bullets;
            lastAmmoShells = shells;
            lastWFrame = suit.wFrame & 0x7FFF;
            return;
        }

        // ---- discharge detection: the ENGINE's ammo bookkeeping is the truth ----
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

        // ---- melee: no ammo, so watch the weapon sprite's swing frames. NOT gated on
        // the button (a tap's swing plays out long after the click is released) — the
        // engine only reaches these frames when it accepted a fire. ----
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
        // swing the MC arm on each discharge: vanilla attacks are cancelled in form, so
        // this is what drives the attack frame (E) other players see on the marine body
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        // ---- translate each discharge into Minecraft rays ----
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
            // clamp to the server's per-hit cap so a point-blank SSG/double-shell volley isn't
            // rejected wholesale (>120 = the whole packet was dropped, so the blast did nothing)
            com.blackwithersteve.lattedoom.net.LatteNet.sendHit(e.getKey(), Math.min(120, e.getValue()));
        }
    }

    /** P_GunShot's dice: 5 * (1d3). */
    private static int bulletDamage() {
        return 5 * (1 + DICE.nextInt(3));
    }

    /** One hitscan ray in MINECRAFT space: eye origin, look direction with DOOM's yaw
     * jitter, clipped by blocks, hitting the first living entity. */
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
