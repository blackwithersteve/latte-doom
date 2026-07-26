package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteHud;
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
        // Gated on the DOOM interface being drawable rather than on the form alone, so a
        // player whose engine is still booting keeps the vanilla hand until the view weapon
        // has something to draw.
        if (LatteHud.ready()) {
            ci.cancel();
        }
    }
}
