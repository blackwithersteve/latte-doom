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
 * A transformed player's eye sits at DOOM's view height of 41 map units above the feet,
 * that is 1.28 blocks rather than Minecraft's 1.62 blocks (51.8 units). At Minecraft's
 * height the proportions of a DOOM level are wrong: 72-unit ceilings crowd the camera and
 * standard doorways scrape it. Applied to the local transformed player only.
 */
@Mixin(Avatar.class)
public abstract class AvatarDimensionsMixin {

    @Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
    private void lattedoom$marineEye(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        // Gated on the transformation rather than on being inside a level: an
        // untransformed player walking the same level keeps Minecraft's eye height.
        if (pose == Pose.STANDING && LatteWorld.marineForm()
            && (Object) this == Minecraft.getInstance().player) {
            cir.setReturnValue(cir.getReturnValue()
                .withEyeHeight((float) (41.0 / LatteWorld.UNITS)));
        }
    }
}
