package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DOOM has no food, so while transformed the whole hunger system stands still: no natural
 * regeneration, no saturation top-ups, no hunger drain and no starvation. Health moves
 * only through the DOOM lanes, where engine damage bills hearts, medikits and soulspheres
 * heal them, and the reborn refills them. An untransformed player keeps vanilla hunger
 * untouched.
 */
@Mixin(FoodData.class)
public abstract class MarineFoodMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lattedoom$suitSustainsTheMarine(ServerPlayer player, CallbackInfo ci) {
        if (MarineRoster.SERVER.contains(player.getUUID())) {
            ci.cancel();
        }
    }
}
