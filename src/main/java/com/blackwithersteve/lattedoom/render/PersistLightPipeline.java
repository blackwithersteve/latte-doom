package com.blackwithersteve.lattedoom.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The pipeline for the persistent lit pass: the worldlight look against static buffers,
 * with light fetched from the per-sector texture bound at Sampler2. That slot is used
 * because the entity snippet already declares it; declaring a new bind-group layout does
 * not work here. Fenced on its own, so a failure drops the persistent path back to its
 * flat bake without taking anything else down.
 */
final class PersistLightPipeline {

    private static RenderPipeline pipeline;
    private static volatile boolean failed;

    static RenderPipeline get() {
        if (pipeline == null && !failed) {
            synchronized (PersistLightPipeline.class) {
                if (pipeline == null && !failed) {
                    try {
                        pipeline = RenderPipelines.register(
                            RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                                .withLocation("pipeline/lattedoom_persistlight")
                                .withVertexShader(Identifier.fromNamespaceAndPath(
                                    "lattedoom", "core/persistlight"))
                                .withFragmentShader(Identifier.fromNamespaceAndPath(
                                    "lattedoom", "core/persistlight"))
                                .withBindGroupLayout(
                                    net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
                                .withCull(false)
                                .build());
                    } catch (Throwable t) {
                        failed = true;
                        System.err.println(
                            "[lattedoom] persist light pipeline unavailable, flat bake stays: " + t);
                    }
                }
            }
        }
        return pipeline;
    }

    private PersistLightPipeline() {
    }
}
