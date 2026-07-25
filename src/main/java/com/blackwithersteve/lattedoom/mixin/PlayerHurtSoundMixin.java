package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A transformed player's pain sound comes from the engine's own {@code A_Pain} action, so
 * the vanilla hurt sound is suppressed for players on the roster; playing both produces a
 * doubled hurt sound. Untransformed players keep the Minecraft sound, and the engine
 * stays silent for them, decided per player.
 */
@Mixin(Player.class)
public abstract class PlayerHurtSoundMixin {

    @Inject(method = "getHurtSound", at = @At("HEAD"), cancellable = true)
    private void lattedoom$marineVoiceOnly(DamageSource source,
                                           CallbackInfoReturnable<SoundEvent> cir) {
        final Player self = (Player) (Object) this;
        if (MarineRoster.SERVER.contains(self.getUUID())
            || MarineRoster.CLIENT.contains(self.getUUID())) {
            cir.setReturnValue(null);
        }
    }
}
