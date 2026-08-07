package com.blackwithersteve.lattedoom.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The lit-world pass: the software renderer's light chain, meaning banded light, distance
 * diminishing, fake contrast and fullbright, computed per pixel in this mod's own shaders.
 * Built from the same snippet as the entity pipelines so the existing vertex writers work
 * unchanged; the mesh encodes light data in the Color channel instead of a baked flat
 * shade. Creation is fenced: any failure flips a flag and the renderer keeps the
 * flat-shade path.
 */
public final class WorldLightPipeline {

    private static RenderPipeline pipeline;
    private static RenderPipeline depthPipeline;
    private static volatile boolean failed;
    /** The DIAGNOSTIC depth variant fences separately: its failure must never
     * take down the production lit pass. */
    private static volatile boolean depthFailed;
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();
    private static final Map<Identifier, RenderType> DEPTH_TYPES = new HashMap<>();

    /** Diagnostic: paint per-pixel distance instead of the lit texture. */
    public static volatile boolean depthDebug;

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
                    .withLocation("pipeline/lattedoom_worldlight")
                    .withVertexShader(Identifier.fromNamespaceAndPath("lattedoom", "core/worldlight"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("lattedoom", "core/worldlight"))
                    // the setup binds the overlay sampler (useOverlay), so the
                    // pipeline MUST declare its bind-group layout, exactly as
                    // vanilla ENTITY_CUTOUT does — a bind without a declared
                    // layout corrupted the draw (phantom flickering triangles)
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
                    // NO face culling, exactly like the sky pipeline and the flat path:
                    // the mesh's winding was never disciplined (it never had to be), so
                    // culling opened sector-shaped holes — and the state visibly bled
                    // into the sky draws of the same pass, blanking the sky
                    .withCull(false)
                    .build());
        } catch (Throwable t) {
            failed = true;
            System.err.println("[lattedoom] world light pipeline unavailable, flat shade stays: " + t);
        }
        try {
            depthPipeline = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                    .withLocation("pipeline/lattedoom_worldlight_depth")
                    .withVertexShader(Identifier.fromNamespaceAndPath("lattedoom", "core/worldlight"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("lattedoom", "core/worldlight"))
                    .withShaderDefine("DEPTH_DEBUG")
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
                    .withCull(false)
                    .build());
        } catch (Throwable t) {
            depthFailed = true;
            System.err.println("[lattedoom] depth diagnostic pipeline unavailable: " + t);
        }
    }

    /** The lit render type for a world texture, or null when the pass is off. */
    public static RenderType type(Identifier texture) {
        init();
        if (failed) {
            return null;
        }
        // one consistent read: a mid-call toggle must not mix the cache, the
        // name and the pipeline across modes
        final boolean depth = depthDebug && !depthFailed && depthPipeline != null;
        final Map<Identifier, RenderType> cache = depth ? DEPTH_TYPES : TYPES;
        RenderType t = cache.get(texture);
        if (t == null) {
            try {
                // one UNIQUE name per texture: dozens of distinct types sharing one
                // name let the collector interleave their vertex streams — phantom
                // triangles stitched between unrelated surfaces, reshuffling with
                // the view
                t = RenderType.create("lattedoom_worldlight"
                        + (depth ? "_depth_" : "_") + texture.getPath()
                        .replace('/', '_').replace('.', '_'),
                    RenderSetup.builder(depth ? depthPipeline : pipeline)
                        .withTexture("Sampler0", texture)
                        .useLightmap()
                        .useOverlay()
                        .createRenderSetup());
                cache.put(texture, t);
            } catch (Throwable ex) {
                if (depth) {
                    depthFailed = true; // diagnostics die alone; the lit pass lives
                    System.err.println("[lattedoom] depth render type failed: " + ex);
                } else {
                    failed = true;
                    System.err.println("[lattedoom] world light render type failed: " + ex);
                }
                return null;
            }
        }
        return t;
    }

    private WorldLightPipeline() {
    }
}
