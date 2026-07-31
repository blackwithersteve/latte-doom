package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import com.blackwithersteve.lattedoom.render.LatteWorldRenderer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the raised DOOM level into the world's deferred-submit pass, right after entities —
 * the same per-frame SubmitNodeCollector surface entity renderers get, without needing any
 * client-side fake entity (whose EntityType would live in a SYNCED registry and break joining
 * vanilla servers).
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void lattedoom$submitDoomLevel(PoseStack pose, LevelRenderState state,
                                           SubmitNodeCollector collector, CallbackInfo ci) {
        LatteWorldRenderer.submit(pose, state, collector);
    }

    /**
     * S2: after the world frame-graph has run and the composited color+depth live in the main
     * render target, draw the level's persistent GPU buffers in our own pass. No-op unless
     * /persist is on. This is the only legal spot to open our own top-level RenderPass with
     * the world already drawn (Fabric's world-render callbacks are gone in 26.2).
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void lattedoom$drawPersistent(GraphicsResourceAllocator alloc, DeltaTracker delta,
                                          boolean renderBlockOutline, CameraRenderState camera,
                                          Matrix4fc projection, GpuBufferSlice fog,
                                          Vector4f fogColor, boolean sky, CallbackInfo ci) {
        LatteWorld.drawPersistent(camera.viewRotationMatrix, camera.pos.x, camera.pos.y,
            camera.pos.z, camera.cullFrustum);
    }
}
