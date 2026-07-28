package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A transformed player carries DOOM's arsenal rather than Minecraft's, so walking over
 * dropped items must not collect them. This runs on the server thread and reads the
 * synchronised marine roster, which covers every transformed player rather than only the
 * one hosting the engine.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void lattedoom$marineLeavesLoot(Player player, CallbackInfo ci) {
        if (MarineRoster.SERVER.contains(player.getUUID())) {
            ci.cancel();
        }
    }
}
