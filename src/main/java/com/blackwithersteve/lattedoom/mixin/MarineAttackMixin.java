package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While transformed, the left mouse button is the DOOM trigger and must neither mine
 * blocks nor perform a vanilla attack. Minecraft consumes the click before the mod's own
 * tick can observe it, so the cancelled {@code startAttack} forwards the press to the
 * weapon layer directly: sampling the key state at 20 Hz drops clicks shorter than one
 * tick.
 */
@Mixin(Minecraft.class)
public abstract class MarineAttackMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void lattedoom$marineTrigger(CallbackInfoReturnable<Boolean> cir) {
        if (LatteWorld.marineForm()) {
            LatteWorld.tapFire();
            cir.setReturnValue(false);
            return;
        }
        // Untransformed player: when no Minecraft entity is in reach the swing may land on
        // a DOOM thing instead, so Minecraft weapons can damage monsters.
        com.blackwithersteve.lattedoom.play.MinecraftCombat.meleeAttack((Minecraft) (Object) this);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noMiningInForm(boolean leftClick, CallbackInfo ci) {
        if (LatteWorld.marineForm()) {
            ci.cancel();
        }
    }
}
