package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In marine form the left mouse button IS the DOOM trigger — it must never mine blocks
 * or vanilla-punch. Vanilla consumes the click before our tick can see it, so the
 * cancelled startAttack also forwards the tap to the suit (quick clicks were falling
 * between 20Hz key samples: "the doommarine cant punch by clicking").
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
        // plain Steve: if no Minecraft entity is in reach, the swing may land on a DOOM
        // thing instead — Minecraft fists/swords hurt demons (the reverse translation)
        com.blackwithersteve.lattedoom.play.MinecraftCombat.meleeAttack((Minecraft) (Object) this);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noMiningInForm(boolean leftClick, CallbackInfo ci) {
        if (LatteWorld.marineForm()) {
            ci.cancel();
        }
    }
}
