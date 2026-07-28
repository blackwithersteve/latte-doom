package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.net.LatteNet;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crouching leaves a player unable to move inside a level. Minecraft shortens a crouching
 * player's movement until the destination has a block underneath it, which is what stops a
 * sneaking player walking off a ledge. A level is rendered geometry rather than blocks, so
 * that search finds no support in any direction and shortens the movement to nothing.
 *
 * The check is skipped inside the level dimension unless a placed block is under the feet,
 * since a placed block is the only surface it can act on. Runs on both sides because the
 * server applies the same movement rule and would otherwise pull the player back.
 */
@Mixin(Player.class)
public abstract class CrouchEdgeMixin {

    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void lattedoom$keepEdgeRuleForBlocksOnly(Vec3 vec, MoverType mover,
                                                     CallbackInfoReturnable<Vec3> cir) {
        final Player self = (Player) (Object) this;
        if (!self.level().dimension().equals(LatteNet.DOOM_LEVEL_DIM) || onPlacedBlock(self)) {
            return;
        }
        cir.setReturnValue(vec);
    }

    /** A thin slice below the box. The dimension holds no terrain, so a hit is player-built. */
    private static boolean onPlacedBlock(Player p) {
        final AABB bb = p.getBoundingBox();
        final AABB probe = new AABB(bb.minX, bb.minY - 0.08, bb.minZ,
            bb.maxX, bb.minY + 0.001, bb.maxZ);
        for (VoxelShape s : p.level().getBlockCollisions(p, probe)) {
            if (!s.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
