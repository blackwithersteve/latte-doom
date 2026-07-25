package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Camera behaviour inside a DOOM level: the engine's view bob while alive, and the
 * engine's death view while dying.
 *
 * <p>A dying transformed player gets DOOM's death camera rather than Minecraft's: no
 * sideways roll, the eye sinking straight down to 6 map units above the feet, matching
 * {@code P_DeathThink}, which drops the view height from 41 to 6. The red fade over it is
 * drawn by {@code DoomDeathScreen}. Untransformed players keep Minecraft's death
 * animation.
 */
@Mixin(Camera.class)
public abstract class DeathCameraMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void lattedoom$doomDeathView(CameraRenderState state, float partial, CallbackInfo ci) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!mc.player.isDeadOrDying()) {
            // Alive, first person, inside a level: apply DOOM's view bob for every player
            // at Minecraft's eye height. A transformed player's amplitude comes from the
            // movement integrator's momentum, an untransformed player's from actual
            // velocity, so the bob can never outpace the movement it belongs to.
            // Minecraft's own head bob is cancelled in GameRendererBobMixin.
            if (mc.options.getCameraType().isFirstPerson()
                && LatteWorld.insideLevel(mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
                final double bob = LatteWorld.marineForm()
                    ? com.blackwithersteve.lattedoom.play.DoomMovement.viewBobOffsetBlocks(
                        LatteWorld.renderTics())
                    : com.blackwithersteve.lattedoom.play.DoomMovement.viewBobForSpeed(
                        LatteWorld.renderTics(),
                        mc.player.getDeltaMovement().horizontalDistance());
                state.pos = new net.minecraft.world.phys.Vec3(
                    state.pos.x, state.pos.y + bob, state.pos.z);
            }
            return;
        }
        if (!LatteWorld.marineForm()) {
            return; // an untransformed player dies the vanilla way
        }
        // Suppress Minecraft's death roll; the downstream renderer derives the tilt from
        // these two fields.
        if (state.entityRenderState != null) {
            state.entityRenderState.deathTime = 0;
            state.entityRenderState.isDeadOrDying = false;
        }
        // Sink the eye to 6/32 of a block above the feet over the death ticks.
        final double t = Math.min(1.0, (mc.player.deathTime + partial) / 20.0);
        final double targetY = mc.player.getY() + 6.0 / 32.0;
        final double y = state.pos.y + (targetY - state.pos.y) * t;
        state.pos = new net.minecraft.world.phys.Vec3(state.pos.x, y, state.pos.z);
    }
}
