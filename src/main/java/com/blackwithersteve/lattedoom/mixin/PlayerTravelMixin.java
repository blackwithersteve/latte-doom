package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.DoomMovement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's hands, DOOM's law: vanilla travel() runs untouched, then the resulting
 * displacement is re-applied under the level's collision (walls, floors, steps, lifts).
 * In MARINE FORM vanilla is skipped outright and pure DOOM physics own the tick.
 */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void lattedoom$beforeTravel(Vec3 input, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer local) {
            // the transformed marine moves like 1993 — vanilla travel is skipped entirely
            if (DoomMovement.marineTravel(local, input)) {
                ci.cancel();
                return;
            }
            DoomMovement.beforeTravel(local);
        }
    }

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("TAIL"))
    private void lattedoom$afterTravel(Vec3 input, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer local) {
            DoomMovement.afterTravel(local);
        }
    }
}
