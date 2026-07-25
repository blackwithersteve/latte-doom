package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DOOM has no fall damage, so a transformed player takes none either. The client resets
 * its own fall distance, but the server tracks falls independently from movement packets,
 * so the cancel has to happen here as well; it is keyed on the synchronised marine roster
 * and therefore applies on both sides.
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
