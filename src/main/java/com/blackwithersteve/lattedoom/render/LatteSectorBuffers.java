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
 * Persistent geometry: an alternative to re-emitting every sector vertex each frame. Each
 * sector's mesh is baked once into a {@link GpuBuffer} that lives for the lifetime of the
 * map and is redrawn each frame, in the same way Minecraft renders its own chunk sections.
 * Only sectors that move, such as doors and lifts, rebuild their contents.
 *
 * <p>Sector light is baked into the vertex colour, which matches the per-frame renderer
 * exactly and makes no assumptions about shaders; a sector whose light level changed is
 * rebaked along with the moving ones. Animated flats and walls need no rebake, because the
 * current frame's texture is bound at draw time against the same buffer and every frame of
 * an animation shares the same dimensions, leaving the texture coordinates valid.
 *
 * <p>All GPU calls run on the render thread: buffers are built during the client tick and
 * drawn from the level renderer's own pass. The path is opt-in through the
 * {@code lattedoom.persist} system property, with the per-frame renderer as the default.
 */
public final class LatteSectorBuffers {

    private static final int FULLBRIGHT = 0xF000F0;

    /** Opt-in switch for the persistent-buffer path; when false, the per-frame renderer
     * draws instead. Set with {@code -Dlattedoom.persist=true}. */
    public static volatile boolean ENABLED =
        "true".equalsIgnoreCase(System.getProperty("lattedoom.persist"));

    /** Sector index to base texture key to baked buffer, mirroring the mesh groups. */
    private static final Map<Integer, Map<String, SectorBuf>> BUFFERS = new HashMap<>();
    private static final Map<String, Identifier> IDS = new HashMap<>();

    private static VertexFormat fmt;
    private static RenderPipeline pipeline;

    /** One sector-texture batch's persistent vertex buffer. All geometry is quads. */
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
        // Any registered texture resolves the shared entity pipeline and vertex format.
        return Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/walls/startan3.png");
    }

    // ---------------------------------------------------------------- bake

    /** Bakes one sector-texture batch into a persistent GPU buffer, folding the sector's
     * current light level into the vertex colour. Input vertices carry position, texture
     * coordinates and a sector index, as produced by the mesh builder. */
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

    /** Builds every sector's buffers from scratch; called once when a map is loaded. */
    public static void buildAll(Map<Integer, Map<String, float[]>> groups) {
        dispose();
        if (groups == null) {
            return;
        }
        for (Map.Entry<Integer, Map<String, float[]>> g : groups.entrySet()) {
            rebuild(g.getKey(), g.getValue());
        }
    }

    /** Rebuilds one sector's buffers, closing the previous GPU buffers first. Moving
     * sectors are rebuilt every frame, and sectors whose light level or height changed are
     * rebuilt when that change is detected. */
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

    /** Frees every GPU buffer, on level unload. */
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
     * Draws every visible sector's persistent buffers in a single render pass, composited
     * over the already-rendered world by loading its colour and depth without clearing.
     * {@code mv} is the camera-relative model-view matrix: the captured camera view
     * multiplied by a translation from the camera to the level origin.
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
