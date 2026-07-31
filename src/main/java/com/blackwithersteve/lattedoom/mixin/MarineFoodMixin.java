package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DOOM has no food: while transformed, the whole hunger system stands still — no natural
 * regeneration, no saturation top-ups, no
 * hunger drain, no starvation. Health moves ONLY through the DOOM lanes: engine damage
 * bills hearts, medikits/soulspheres heal them, the reborn refills them. Plain Steve
 * (off-roster) keeps vanilla hunger untouched.
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
