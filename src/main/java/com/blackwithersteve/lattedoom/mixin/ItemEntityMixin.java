package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.ItemCollision;
import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void lattedoom$doomItemPhysics(CallbackInfo ci) {
        ItemEntity item = (ItemEntity) (Object) this;

        ItemCollision.tick(item);
    }

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void lattedoom$marineLeavesLoot(Player player, CallbackInfo ci) {
        /*
         * Only transformed Doom players are prevented from picking
         * up items.
         *
         * A normal Minecraft player is allowed to pick them up,
         * even while standing inside the Doom map.
         */
        if (MarineRoster.SERVER.contains(player.getUUID())) {
            ci.cancel();
        }
    }
}
