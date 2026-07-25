package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DOOM has no food, so the hunger system is held still while a player is transformed: no
 * natural regeneration, no saturation top-ups, no hunger drain and no starvation damage.
 * Health then changes only through the DOOM paths: engine damage removes it, medikits and
 * soulspheres restore it, and a level restart refills it. Without this, Minecraft's own
 * regeneration would undo the engine's damage. Players not on the roster keep vanilla
 * hunger.
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
