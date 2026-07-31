package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DOOM has no fall damage, so neither does a marine. The client already resets its fall
 * distance, but the SERVER tracks falls on its own from movement packets — this is the
 * server-side (and client-instance) cancel, keyed on the synced marine roster.
 */
@Mixin(Player.class)
public abstract class FallDamageMixin {

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void lattedoom$marinesFallLikeDoomguy(double fallDistance, float multiplier,
                                                  DamageSource source,
                                                  CallbackInfoReturnable<Boolean> cir) {
        final Player self = (Player) (Object) this;
        if (MarineRoster.SERVER.contains(self.getUUID())
            || MarineRoster.CLIENT.contains(self.getUUID())) {
            cir.setReturnValue(false);
        }
    }
}
