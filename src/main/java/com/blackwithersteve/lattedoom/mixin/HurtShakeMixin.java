package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taking a hit in DOOM flashes the screen red — it never wrenches the camera around.
 * Minecraft's hurt tilt (bobHurt) is cancelled while transformed; the STBAR face and
 * the damage flash carry the feedback, 1993-style.
 */
@Mixin(GameRenderer.class)
public abstract class HurtShakeMixin {

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noHurtWrench(CallbackInfo ci) {
        if (LatteWorld.marineForm()) {
            ci.cancel();
        }
    }
}
