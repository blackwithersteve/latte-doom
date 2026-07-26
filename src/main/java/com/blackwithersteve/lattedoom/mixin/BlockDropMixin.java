package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.net.LatteNet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A level is raised in an empty dimension with no ground under it, so an item dropped inside
 * one falls out of the world. Drops inside that dimension go straight into the inventory of
 * the nearest player instead, and are only dropped as an entity when nobody is close enough
 * to have caused them.
 */
@Mixin(Block.class)
public class BlockDropMixin {

    @Inject(method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), cancellable = true)
    private static void lattedoom$giveInsteadOfDropping(Level level, BlockPos pos,
                                                        ItemStack stack, CallbackInfo ci) {
        if (level.isClientSide() || stack.isEmpty()
            || !level.dimension().equals(LatteNet.DOOM_LEVEL_DIM)) {
            return;
        }
        // Measured from the centre of the block, so the radius covers a player standing at
        // any reach distance from it. Without a candidate the vanilla drop is left alone.
        final Player p = level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5,
            pos.getZ() + 0.5, 6.0, false);
        if (p == null) {
            return;
        }
        ci.cancel();
        // A full inventory falls back to dropping at the player rather than at the block,
        // which keeps the item inside the walkable part of the level.
        if (!p.getInventory().add(stack) && !stack.isEmpty()) {
            p.drop(stack, false);
        }
    }
}
