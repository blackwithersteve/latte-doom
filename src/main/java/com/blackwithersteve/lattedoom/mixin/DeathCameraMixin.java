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
 * A dying MARINE's view falls the DOOM way, not Minecraft's: no sideways death roll —
 * the camera sinks STRAIGHT DOWN to 6 map units above the feet (P_DeathThink drops
 * viewheight 41 -> 6), red death fade over it (DoomDeathScreen). Vanilla deaths (not
 * transformed) keep Minecraft's keel-over untouched.
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
            // LIVING, first person, inside the level: DOOM's view bob for EVERYONE at
            // Minecraft's eye height (the 41-unit doom eye was tried and reverted —
            // monsters read proper-sized from the MC eye). Marine amplitude from the
            // integrator's momenta, plain Steve's from his real velocity; MC's own
            // head-bob sits out via GameRendererBobMixin — its cadence read "way too
            // fast" in levels regardless of the walk counters.
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
            return; // plain Steve's death stays fully vanilla
        }
        // kill the MC death roll (the downstream renderer reads these for the tilt)
        if (state.entityRenderState != null) {
            state.entityRenderState.deathTime = 0;
            state.entityRenderState.isDeadOrDying = false;
        }
        // DOOM's sink: lerp the eye to 6/32 blocks above the feet across the death ticks
        final double t = Math.min(1.0, (mc.player.deathTime + partial) / 20.0);
        final double targetY = mc.player.getY() + 6.0 / 32.0;
        final double y = state.pos.y + (targetY - state.pos.y) * t;
        state.pos = new net.minecraft.world.phys.Vec3(state.pos.x, y, state.pos.z);
    }
}
