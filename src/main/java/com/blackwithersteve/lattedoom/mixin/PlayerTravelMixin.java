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
 * Routes player movement through the level's collision. For an untransformed player,
 * vanilla {@code travel()} runs unchanged and the resulting displacement is then
 * re-applied under DOOM's collision rules (walls, floors, step-ups, moving platforms), so
 * Minecraft's own acceleration is kept inside a DOOM level. For a transformed player,
 * vanilla movement is skipped entirely and the DOOM physics integrator owns the tick.
 */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void lattedoom$beforeTravel(Vec3 input, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer local) {
            // A transformed player runs the DOOM integrator instead of vanilla movement.
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
