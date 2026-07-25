package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A transformed player sees the WAD's own view weapon, so Minecraft's first-person hand
 * and held item are suppressed: rendering both at once puts a floating vanilla hand
 * underneath the DOOM weapon sprite.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class HandRenderMixin {

    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noSteveHands(CallbackInfo ci) {
        if (LatteWorld.marineForm()) {
            ci.cancel();
        }
    }
}
