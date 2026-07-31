package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marine form has the WAD's own view weapon on screen — Steve's floating first-person
 * hand/held item underneath it broke the picture. Transformed = no vanilla hands at all.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class HandRenderMixin {

    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noSteveHands(CallbackInfo ci) {
        if (com.blackwithersteve.lattedoom.render.LatteHud.ready()) {
            ci.cancel();
        }
    }
}
