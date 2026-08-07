package com.blackwithersteve.lattedoom.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The sprite pass: DOOM's colormap model alone, texture times the CPU-computed doomShade
 * grey. Sprites must not ride the vanilla entity shader, whose lightmap multiply and tint
 * terms sit outside this light model and dim sprites below the walls around them, leaving
 * fullbright frames unlit. The pipeline rules match WorldLightPipeline: ENTITY_SNIPPET
 * parent, SAMPLER1 bind group declared, culling off, and one unique RenderType name per
 * texture. Falls back to entityCutout on failure.
 */
public final class SpriteShadePipeline {

    private static RenderPipeline pipeline;
    private static volatile boolean failed;
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();

    private static synchronized void init() {
        if (pipeline != null || failed) {
            return;
        }
        try {
            pipeline = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                    .withLocation("pipeline/lattedoom_spriteshade")
                    .withVertexShader(Identifier.fromNamespaceAndPath("lattedoom", "core/spriteshade"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("lattedoom", "core/spriteshade"))
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
                    .withCull(false)
                    .build());
        } catch (Throwable t) {
            failed = true;
            System.err.println("[lattedoom] sprite pipeline unavailable, entityCutout stays: " + t);
        }
    }

    /** The sprite render type for a texture, or null when the pass is off. */
    public static RenderType type(Identifier texture) {
        init();
        if (failed) {
            return null;
        }
        RenderType t = TYPES.get(texture);
        if (t == null) {
            try {
                t = RenderType.create("lattedoom_spriteshade_" + texture.getPath()
                        .replace('/', '_').replace('.', '_'),
                    RenderSetup.builder(pipeline)
                        .withTexture("Sampler0", texture)
                        .useLightmap()
                        .useOverlay()
                        .createRenderSetup());
                TYPES.put(texture, t);
            } catch (Throwable ex) {
                failed = true;
                System.err.println("[lattedoom] sprite render type failed: " + ex);
                return null;
            }
        }
        return t;
    }

    private SpriteShadePipeline() {
    }
}
