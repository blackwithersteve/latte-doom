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
 * Marine form rewires the keyboard to 1993: there is no crouch and no Minecraft sprint —
 * SHIFT is DOOM's run key (Caps Lock toggles always-run; see LatteDoomClient). Shift is
 * captured for the physics, then shift+sprint are stripped from the input record so
 * vanilla never sneak-poses or double-tap-sprints the transformed marine.
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
