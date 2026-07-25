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
 * Feeds the raised DOOM level into the world's deferred-submit pass, immediately after
 * entities. This uses the same per-frame {@link SubmitNodeCollector} surface that entity
 * renderers use, which avoids introducing a client-side placeholder entity: an
 * {@code EntityType} lives in a synchronised registry and would prevent the client from
 * joining vanilla servers.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void lattedoom$submitDoomLevel(PoseStack pose, LevelRenderState state,
                                           SubmitNodeCollector collector, CallbackInfo ci) {
        LatteWorldRenderer.submit(pose, state, collector);
    }

    /**
     * Draws the level's persistent GPU buffers in a dedicated pass once the world frame
     * graph has run and the composited colour and depth are in the main render target.
     * This is a no-op unless persistent-buffer mode is enabled. It is the only point at
     * which a top-level render pass can be opened with the world already drawn, since
     * Fabric's world-render callbacks no longer exist in this Minecraft version.
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
