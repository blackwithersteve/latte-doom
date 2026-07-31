package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The marine's eye sits 41 map units above his feet — 1.28 blocks, not Minecraft's 1.62
 * (51.8 units). Without this the whole map reads "weirdly tall": 72-unit ceilings crowd
 * the camera and doorways scrape it. Applied to the possessed local player only.
 */
@Mixin(Avatar.class)
public abstract class AvatarDimensionsMixin {

    @Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
    private void lattedoom$marineEye(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        // gated on the TRANSFORMATION, not on presence: a plain MC player in the level
        // keeps Steve's own 1.62 eyes; the marine's 41u eye comes with the future command
        if (pose == Pose.STANDING && LatteWorld.marineForm()
            && (Object) this == Minecraft.getInstance().player) {
            cir.setReturnValue(cir.getReturnValue()
                .withEyeHeight((float) (41.0 / LatteWorld.UNITS)));
        }
    }
}
