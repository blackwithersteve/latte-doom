package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the view weapon from the engine's psprite state over the Minecraft HUD. Once the
 * DOOM interface can be drawn the vanilla inventory interface is suppressed as well: hotbar,
 * hotbar items and the held-item name, because the DOOM status bar is the entire interface.
 * The hotbar slots keep working invisibly as the weapon selector, so number keys and the
 * scroll wheel still switch weapons.
 *
 * <p>The suppression waits on {@link LatteHud#ready()} rather than on the transformed form
 * alone. Transforming is instant while the engine boots on its own thread, so gating on the
 * form would leave the player with no interface at all for the length of that boot.
 */
@Mixin(Hud.class)
public abstract class HudExtractMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void lattedoom$weaponOverlay(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        LatteHud.extract(g);
    }

    @Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noHotbar(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (LatteHud.ready()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noHotbarItems(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        if (LatteHud.ready()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void lattedoom$noItemName(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (LatteHud.ready()) {
            ci.cancel();
        }
    }
}
