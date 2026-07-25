package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.DoomMovement;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A transformed player uses DOOM's key semantics: there is no crouch and no Minecraft
 * sprint, and Shift is the run key (Caps Lock toggles always-run; see
 * {@code LatteDoomClient}). Shift is captured for the movement integrator and then
 * stripped, together with sprint, from the input record, so vanilla never applies a
 * sneak pose or double-tap sprint to a transformed player.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void lattedoom$marineKeys(CallbackInfo ci) {
        if (!LatteWorld.marineForm()) {
            return;
        }
        final ClientInput self = (ClientInput) (Object) this;
        final Input k = self.keyPresses;
        DoomMovement.setRunHeld(k.shift());
        if (k.shift() || k.sprint()) {
            self.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(),
                k.jump(), false, false);
        }
    }
}
