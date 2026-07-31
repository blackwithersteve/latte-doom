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
 * Inside a DOOM level everyone bobs like DOOM, not Minecraft: the camera lane
 * (DeathCameraMixin) applies P_CalcHeight's momentum sine to the eye — marine amplitude
 * from the integrator's momenta, plain Steve's from his actual velocity — so Minecraft's
 * own pose-swaying head-bob sits out entirely (its cadence read "way too fast" in levels
 * no matter the walk counters). The overworld keeps vanilla bobbing untouched.
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
