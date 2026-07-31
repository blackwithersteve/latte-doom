package com.blackwithersteve.lattedoom.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * S2 — PERSISTENT GEOMETRY. Instead of re-emitting every sector vertex every frame (the
 * FPS killer), each sector's mesh is baked ONCE into a {@link GpuBuffer} that lives for the
 * map's lifetime and is just redrawn each frame — exactly how Minecraft renders its own
 * chunk sections. Only the handful of moving sectors (doors/lifts) rebuild their bytes.
 *
 * <p>Engine light is baked into the vertex colour (Option A — matches the current renderer
 * exactly, no shader assumptions); a sector whose light changed is re-baked alongside the
 * movers. Animated flats/walls need NO rebake: the current-frame texture is bound at draw
 * time against the same buffer (every anim frame shares dimensions, so the UVs stay valid).
 *
 * <p>All GPU calls run on the render thread (build in the client-tick GL slot, draw from the
 * LevelRenderer.render TAIL mixin). This whole path is gated behind {@code /persist on} — the
 * classic per-frame renderer stays the safe default until this is proven in-game.
 */
public final class LatteSectorBuffers {

    private static final int FULLBRIGHT = 0xF000F0;

    /** S2 opt-in. OFF = the proven per-frame renderer draws (safe default). `/persist on`
     * flips to the persistent-GPU-buffer path. Kept a toggle until it's confirmed in-game. */
    public static volatile boolean ENABLED =
        "true".equalsIgnoreCase(System.getProperty("lattedoom.persist"));

    /** sector -> (base texture key -> baked buffer). Mirrors LatteWorld.groups(). */
    private static final Map<Integer, Map<String, SectorBuf>> BUFFERS = new HashMap<>();
    private static final Map<String, Identifier> IDS = new HashMap<>();

    private static VertexFormat fmt;
    private static RenderPipeline pipeline;

    /** A sector-texture batch's persistent vertex buffer. Geometry is 100% quads. */
    private record SectorBuf(GpuBuffer vbo, int vertexCount) {
        int indexCount() {
            return vertexCount / 4 * 6;
        }
    }

    private static VertexFormat fmt() {
        if (fmt == null) {
            fmt = RenderTypes.entityCutout(dummyId()).format();
        }
        return fmt;
    }

    private static RenderPipeline pipeline() {
        if (pipeline == null) {
            pipeline = RenderTypes.entityCutout(dummyId()).pipeline();
        }
        return pipeline;
    }

    private static Identifier dummyId() {
        // any registered doom texture works to resolve the shared entity pipeline/format
        return Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/walls/startan3.png");
    }

    // ---------------------------------------------------------------- bake

    /** Bake one sector-texture batch (LatteMesh float[]: x,y,z,u,v,sectorIdx per vertex) into
     * a persistent GPU buffer, with the sector's current engine light folded into vertex colour. */
    private static SectorBuf bake(float[] v, String debugName) {
        final int verts = v.length / 6;
        try (ByteBufferBuilder bbb = new ByteBufferBuilder(Math.max(64, verts * fmt().getVertexSize()))) {
            final BufferBuilder bb = new BufferBuilder(bbb, PrimitiveTopology.QUADS, fmt());
            for (int i = 0; i < v.length; i += 6) {
                final int c = LatteMesh.shadeByte(LatteWorld.lightOf((int) v[i + 5]));
                bb.addVertex(v[i], v[i + 1], v[i + 2])
                  .setColor(c, c, c, 255)
                  .setUv(v[i + 3], v[i + 4])
                  .setOverlay(OverlayTexture.NO_OVERLAY)
                  .setLight(FULLBRIGHT)
                  .setNormal(0.0f, 1.0f, 0.0f);
            }
            try (MeshData mesh = bb.buildOrThrow()) {
                final GpuDevice dev = RenderSystem.getDevice();
                final GpuBuffer vbo = dev.createBuffer(() -> "latte:" + debugName,
                    GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                return new SectorBuf(vbo, mesh.drawState().vertexCount());
            }
        }
    }

    /** Build every sector's buffers from scratch (called once when the map loads). */
    public static void buildAll(Map<Integer, Map<String, float[]>> groups) {
        dispose();
        if (groups == null) {
            return;
        }
        for (Map.Entry<Integer, Map<String, float[]>> g : groups.entrySet()) {
            rebuild(g.getKey(), g.getValue());
        }
    }

    /** Rebuild a single sector's buffers (moving sectors each frame; relit/height-changed
     * sectors in syncHeights). Closes the old GPU buffers first. */
    public static void rebuild(int sector, Map<String, float[]> group) {
        final Map<String, SectorBuf> old = BUFFERS.remove(sector);
        if (old != null) {
            for (SectorBuf b : old.values()) {
                b.vbo().close();
            }
        }
        if (group == null || group.isEmpty()) {
            return;
        }
        final Map<String, SectorBuf> made = new HashMap<>();
        for (Map.Entry<String, float[]> e : group.entrySet()) {
            if (e.getValue().length >= 6) {
                made.put(e.getKey(), bake(e.getValue(), sector + "/" + e.getKey()));
            }
        }
        BUFFERS.put(sector, made);
    }

    /** Free every GPU buffer (level unload). */
    public static void dispose() {
        for (Map<String, SectorBuf> m : BUFFERS.values()) {
            for (SectorBuf b : m.values()) {
                b.vbo().close();
            }
        }
        BUFFERS.clear();
    }

    public static boolean isEmpty() {
        return BUFFERS.isEmpty();
    }

    // ---------------------------------------------------------------- draw

    /**
     * Draw every (visible) sector's persistent buffers in ONE render pass, composited over
     * the already-rendered world (load color+depth, no clear). {@code mv} is the full
     * camera-relative model-view (captured camera view * translate(origin - cam)).
     */
    public static void draw(Matrix4f mv, int tic, Frustum frustum,
                            Map<Integer, double[]> bounds, java.util.Set<Integer> moving,
                            double ox, double oy, double oz) {
        if (BUFFERS.isEmpty()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (main == null) {
            return;
        }
        final GpuTextureView color = main.getColorTextureView();
        final GpuTextureView depth = main.useDepth ? main.getDepthTextureView() : null;
        final GpuSampler linClamp = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        final GpuTextureView overlayView = mc.gameRenderer.overlayTexture().getTextureView();
        final GpuTextureView lightmapView = mc.gameRenderer.lightmap();
        final var seq = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        final var xform = RenderSystem.getDynamicUniforms().writeTransform(mv);

        final GpuDevice dev = RenderSystem.getDevice();
        try (RenderPass pass = dev.createCommandEncoder().createRenderPass(
                () -> "latte-doom-world", color, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(pipeline());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", xform);
            pass.bindTexture("Sampler1", overlayView, linClamp);
            pass.bindTexture("Sampler2", lightmapView, linClamp);

            for (Map.Entry<Integer, Map<String, SectorBuf>> ge : BUFFERS.entrySet()) {
                final int sector = ge.getKey();
                if (frustum != null && bounds != null && !moving.contains(sector)) {
                    final double[] b = bounds.get(sector);
                    if (b != null && !frustum.isVisible(new AABB(
                            ox + b[0] - 1, oy + b[1] - 1, oz + b[2] - 1,
                            ox + b[3] + 1, oy + b[4] + 1, oz + b[5] + 1))) {
                        continue; // off-screen: skip
                    }
                }
                for (Map.Entry<String, SectorBuf> te : ge.getValue().entrySet()) {
                    final SectorBuf buf = te.getValue();
                    final Identifier id = idOf(LatteAnims.frame(te.getKey(), tic));
                    final AbstractTexture tex = mc.getTextureManager().getTexture(id);
                    pass.bindTexture("Sampler0", tex.getTextureView(), tex.getSampler());
                    pass.setVertexBuffer(0, buf.vbo().slice());
                    final int ic = buf.indexCount();
                    pass.setIndexBuffer(seq.getBuffer(ic), seq.type());
                    pass.drawIndexed(ic, 1, 0, 0, 0);
                }
            }
        }
    }

    static Identifier idOf(String key) {
        return IDS.computeIfAbsent(key,
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private LatteSectorBuffers() {}
}
