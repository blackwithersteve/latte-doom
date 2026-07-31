package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.LatteDoomClient;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Free look off means the pitch input never applies, not that something fights it:
 * resetting the camera after the mouse had already moved it snapped the view back once
 * per tick, which read as constant teleporting. Zeroing the delta here keeps the mouse's
 * horizontal turn intact and leaves nothing to snap.
 */
@Mixin(Entity.class)
public abstract class PitchLockMixin {

    @ModifyVariable(method = "turn", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private double lattedoom$lockPitch(double xTurn) {
        if ((Object) this instanceof LocalPlayer
            && !LatteDoomClient.freelook()
            && (LatteWorld.marineForm() || LatteWorld.playMode())) {
            return 0;
        }
        return xTurn;
    }
}
