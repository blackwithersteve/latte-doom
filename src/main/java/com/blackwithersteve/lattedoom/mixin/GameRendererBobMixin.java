package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inside a DOOM level the view bob comes from the engine's own {@code P_CalcHeight}
 * curve, applied to the eye position in {@link DeathCameraMixin}, so Minecraft's
 * pose-swaying head bob is cancelled for every player there. Minecraft's bob is driven by
 * accumulated walk distance and its cadence does not match DOOM movement, which makes the
 * two read as camera shake when combined. Outside a level, vanilla bobbing is untouched.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererBobMixin {

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void lattedoom$doomBobOnly(CameraRenderState state, PoseStack pose, CallbackInfo ci) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null
            && LatteWorld.insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
            ci.cancel();
        }
    }
}
