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
 * A marine grunts in doomguy's voice (the engine's A_Pain) — the Minecraft "oof" on top
 * of it was a double hurt sound. Rostered marines get no vanilla hurt sound; plain
 * players keep theirs (and the engine keeps ITS mouth shut for them, per-player).
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
