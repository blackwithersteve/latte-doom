package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taking a hit in DOOM flashes the screen red and never rotates the camera, so
 * Minecraft's hurt tilt is cancelled while a player is transformed. The status-bar face
 * and the damage flash carry the feedback instead.
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
