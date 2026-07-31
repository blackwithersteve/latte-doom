package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteHud;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the marine's view weapon (engine psprite state) over the Minecraft HUD — and in
 * marine form REMOVES Minecraft's inventory UI outright (hotbar, its items, the held-item
 * name): the STBAR is the whole interface. The hotbar SLOTS keep working invisibly as the
 * DOOM weapon selector (number keys / scroll).
 */
@Mixin(Hud.class)
public abstract class HudExtractMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void lattedoom$weaponOverlay(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        LatteHud.extract(g);
    }

    @Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noHotbar(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (com.blackwithersteve.lattedoom.render.LatteHud.ready()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noHotbarItems(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (com.blackwithersteve.lattedoom.render.LatteHud.ready()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noItemName(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (com.blackwithersteve.lattedoom.render.LatteHud.ready()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noVanillaCrosshair(GuiGraphicsExtractor g, DeltaTracker delta,
                                              CallbackInfo ci) {
        // the marine aims with DOOM's reticle (the Crispness crosshair, when enabled) —
        // Minecraft's cross and its attack indicator are the wrong interface here
        if (com.blackwithersteve.lattedoom.render.LatteHud.ready()) {
            ci.cancel();
        }
    }
}
