package com.blackwithersteve.lattedoom.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The sky pass: a pipeline built from the same snippet as the entity pipelines (same
 * bind layouts, same vertex format, so the existing vertex writers work unchanged) with
 * the sky shaders swapped in. Depth is written at the surface's real position, which is
 * what reproduces the original's sky clipping — geometry behind a sky ceiling is cut by
 * it, exactly like 1993. Creation is fenced: any failure flips a flag and the renderer
 * keeps the old backdrop, so a shader problem degrades instead of crashing the frame.
 */
public final class SkyPipeline {

    private static RenderPipeline pipeline;
    private static RenderPipeline flatPipeline;
    private static volatile boolean failed;
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();
    private static final Map<Identifier, RenderType> FLAT_TYPES = new HashMap<>();

    public static boolean available() {
        init();
        return !failed;
    }

    private static synchronized void init() {
        if (pipeline != null || failed) {
            return;
        }
        try {
            pipeline = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                    .withLocation("pipeline/lattedoom_sky")
                    .withVertexShader(Identifier.fromNamespaceAndPath("lattedoom", "core/sky"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("lattedoom", "core/sky"))
                    .withCull(false)
                    .build());
            flatPipeline = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                    .withLocation("pipeline/lattedoom_sky_flat")
                    .withVertexShader(Identifier.fromNamespaceAndPath("lattedoom", "core/sky"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("lattedoom", "core/sky"))
                    .withShaderDefine("SKY_FAR")
                    .withCull(false)
                    // rendered at the far plane and never a depth author: the planes
                    // must lose to every real surface and claim nothing
                    .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                        com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .build());
        } catch (Throwable t) {
            failed = true;
            System.err.println("[lattedoom] sky pipeline unavailable, keeping the backdrop: " + t);
        }
    }

    /** The sky-wall render type for a texture (true depth: the original's sky
     * clipping), or null when the pass is off. */
    public static RenderType type(Identifier skyTexture) {
        return typeFrom(skyTexture, false);
    }

    /** The sky-plane render type (far-pushed: never occludes), or null. */
    public static RenderType flatType(Identifier skyTexture) {
        return typeFrom(skyTexture, true);
    }

    private static synchronized RenderType typeFrom(Identifier skyTexture, boolean flat) {
        init();
        if (failed) {
            return null;
        }
        final Map<Identifier, RenderType> cache = flat ? FLAT_TYPES : TYPES;
        RenderType t = cache.get(skyTexture);
        if (t == null) {
            try {
                t = RenderType.create(flat ? "lattedoom_sky_flat" : "lattedoom_sky",
                    RenderSetup.builder(flat ? flatPipeline : pipeline)
                        .withTexture("Sampler0", skyTexture)
                        .useLightmap()
                        .useOverlay()
                        .createRenderSetup());
                cache.put(skyTexture, t);
            } catch (Throwable ex) {
                failed = true;
                System.err.println("[lattedoom] sky render type failed: " + ex);
                return null;
            }
        }
        return t;
    }

    private SkyPipeline() {}
}
