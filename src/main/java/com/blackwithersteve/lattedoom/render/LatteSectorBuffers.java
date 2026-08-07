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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * S2 — PERSISTENT GEOMETRY, texture-major with hole eviction. Static geometry
 * is merged into ONE GPU buffer PER TEXTURE at map load and redrawn with one
 * bind per texture — the draw count tracks the texture count, not sectors x
 * textures (the sector-major version was draw-call bound on big megawads).
 *
 * <p>A sector that CHANGES (mover frame, height sync, switch flip) is evicted
 * to its own per-sector buffers. Eviction NEVER rebakes a merged buffer: each
 * sector's quad range inside every merged buffer is recorded at build time, and
 * evicting just removes the range — the draw walks the remaining segments with
 * drawIndexed(firstIndex). The first door of a play session used to rebake
 * every merged buffer it touched (a whole-map hitch, since movingSectors()
 * evicts the mover AND its neighbors in one frame); now it costs nothing.
 *
 * <p>With doom light on, vertices carry the SECTOR INDEX and per-quad contrast;
 * live light rides {@link PersistLightTexture}, fetched per pixel by
 * {@link PersistLightPipeline} — light changes never touch geometry. With doom
 * light off (or the lit pipeline fenced off), the flat shade is baked, and a
 * per-frame light diff evicts+rebakes sectors whose light changed so flickers
 * keep animating.
 *
 * <p>All GPU calls run on the render thread; methods are synchronized only to
 * survive teardown races (the disconnect CME). Gated behind {@code /persist}.
 */
public final class LatteSectorBuffers {

    private static final int FULLBRIGHT = 0xF000F0;

    /**
     * The persistent path is how the level is drawn. It measured 45 to ~500fps on MAP10
     * against the per-frame renderer and is better on every map, so it is not optional
     * and there is no command to leave it.
     *
     * The constant stays because the per-frame path is still present in
     * LatteWorldRenderer.submit behind it; that code is now unreachable and comes out
     * once this build has been played.
     */
    public static final boolean ENABLED = true;

    /** One merged per-texture buffer: the vertices never change after build;
     * evictions only edit the segment list the draw walks. */
    private static final class MergedBuf {
        final GpuBuffer vbo;
        final int quadCount;
        /** sector -> {firstQuad, quadCount} inside the buffer. */
        final Map<Integer, int[]> bySector = new HashMap<>();
        /** Coalesced draw list, {firstQuad, quadCount} each; rebuilt on evict. */
        int[][] segments;

        MergedBuf(GpuBuffer vbo, int quadCount) {
            this.vbo = vbo;
            this.quadCount = quadCount;
        }

        /** Drop a sector's range and re-coalesce the survivors. */
        void evict(int sector) {
            if (bySector.remove(sector) == null) {
                return;
            }
            final List<int[]> ranges = new ArrayList<>(bySector.values());
            ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
            final List<int[]> segs = new ArrayList<>();
            for (int[] r : ranges) {
                final int[] last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
                if (last != null && last[0] + last[1] == r[0]) {
                    last[1] += r[1]; // contiguous: extend the segment
                } else {
                    segs.add(new int[]{r[0], r[1]});
                }
            }
            segments = segs.toArray(new int[0][]);
        }
    }

    /** texture key -> merged static buffer (every non-evicted sector's quads). */
    private static final Map<String, MergedBuf> MERGED = new HashMap<>();
    /** Sectors evicted to per-sector buffers (they changed at least once). */
    private static final Set<Integer> EVICTED = new HashSet<>();
    /** evicted sector -> (texture key -> its own buffer). */
    private static final Map<Integer, Map<String, SectorBuf>> INDIVIDUAL = new HashMap<>();
    private static final Map<String, Identifier> IDS = new HashMap<>();

    private static VertexFormat fmt;
    private static RenderPipeline pipeline;
    /** The encoding EVERY current buffer was baked with; a doom-light toggle
     * disposes the buffers (LatteMesh.setDoomLight), so this is all-or-nothing. */
    private static boolean bakedLit;
    /** Flat mode only: last light per sector, to evict+rebake on light change
     * (lit mode reads light live from the texture and never needs this). */
    private static short[] flatLight;

    /** A persistent vertex buffer; geometry is 100% quads. */
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

    private static boolean skyKey(String key) {
        // sky rides the SkyPipeline batches on the submit path in BOTH modes;
        // baking these keys crashed idOf on '!'/'*' (the old /persist sky crash)
        return key.startsWith("sky!") || key.startsWith("fsky!");
    }

    // ---------------------------------------------------------------- bake

    /** Write one LatteMesh vertex array (x,y,z,u,v,sectorIdx per vertex) with
     * the current encoding: lit = sector index + per-quad contrast (the classic
     * path's exact contrast rule); flat = baked shade. */
    private static void writeVerts(BufferBuilder bb, float[] v) {
        final boolean lit = bakedLit;
        int contrast = 128;
        for (int i = 0; i < v.length; i += 6) {
            if (lit && (i % 24) == 0 && i + 23 < v.length) {
                final float y0 = v[i + 1], y1 = v[i + 7];
                final float y2 = v[i + 13], y3 = v[i + 19];
                if (y0 == y1 && y1 == y2 && y2 == y3) {
                    contrast = 128;
                } else {
                    final int base = y0 == y1 ? i : y1 == y2 ? i + 6 : i + 12;
                    final float dx = v[base + 6] - v[base];
                    final float dz = v[base + 8] - v[base + 2];
                    contrast = dx == 0 ? 255 : dz == 0 ? 0 : 128;
                }
            }
            if (lit) {
                final int idx = (int) v[i + 5];
                bb.addVertex(v[i], v[i + 1], v[i + 2])
                  .setColor(idx & 255, (idx >> 8) & 255, contrast, 255)
                  .setUv(v[i + 3], v[i + 4])
                  .setOverlay(OverlayTexture.NO_OVERLAY)
                  .setLight(FULLBRIGHT)
                  .setNormal(0.0f, 1.0f, 0.0f);
            } else {
                final int c = LatteMesh.shadeByte(LatteWorld.lightOf((int) v[i + 5]));
                bb.addVertex(v[i], v[i + 1], v[i + 2])
                  .setColor(c, c, c, 255)
                  .setUv(v[i + 3], v[i + 4])
                  .setOverlay(OverlayTexture.NO_OVERLAY)
                  .setLight(FULLBRIGHT)
                  .setNormal(0.0f, 1.0f, 0.0f);
            }
        }
    }

    private static GpuBuffer bakeVbo(List<float[]> arrays, int verts, String debugName) {
        try (ByteBufferBuilder bbb = new ByteBufferBuilder(Math.max(64, verts * fmt().getVertexSize()))) {
            final BufferBuilder bb = new BufferBuilder(bbb, PrimitiveTopology.QUADS, fmt());
            for (float[] v : arrays) {
                writeVerts(bb, v);
            }
            try (MeshData mesh = bb.buildOrThrow()) {
                final GpuDevice dev = RenderSystem.getDevice();
                return dev.createBuffer(() -> "latte:" + debugName,
                    GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    /** Build the merged per-texture buffers from scratch (map load), recording
     * every sector's quad range so eviction can skip it without a rebake. */
    public static synchronized void buildAll(Map<Integer, Map<String, float[]>> groups) {
        dispose();
        if (groups == null) {
            return;
        }
        bakedLit = LatteMesh.doomLight() && PersistLightPipeline.get() != null;
        flatLight = null;
        final Map<String, List<float[]>> byTex = new HashMap<>();
        final Map<String, List<int[]>> ranges = new HashMap<>(); // {sector, quads} per array
        for (Map.Entry<Integer, Map<String, float[]>> g : groups.entrySet()) {
            for (Map.Entry<String, float[]> e : g.getValue().entrySet()) {
                if (skyKey(e.getKey()) || e.getValue().length < 24) {
                    continue;
                }
                byTex.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                ranges.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                    .add(new int[]{g.getKey(), e.getValue().length / 24});
            }
        }
        for (Map.Entry<String, List<float[]>> e : byTex.entrySet()) {
            int quads = 0;
            for (int[] r : ranges.get(e.getKey())) {
                quads += r[1];
            }
            if (quads == 0) {
                continue;
            }
            final GpuBuffer vbo = bakeVbo(e.getValue(), quads * 4, "merged/" + e.getKey());
            final MergedBuf mb = new MergedBuf(vbo, quads);
            int at = 0;
            for (int[] r : ranges.get(e.getKey())) {
                mb.bySector.put(r[0], new int[]{at, r[1]});
                at += r[1];
            }
            mb.segments = new int[][]{{0, quads}};
            MERGED.put(e.getKey(), mb);
        }
    }

    /**
     * A sector's geometry changed (mover frame, height sync, switch flip).
     * First time: punch its ranges out of every merged buffer (segment edit,
     * NO rebake). Always: (re)bake its own per-sector buffers.
     */
    public static synchronized void rebuild(int sector, Map<String, float[]> group) {
        if (MERGED.isEmpty() && INDIVIDUAL.isEmpty()) {
            return; // not built yet (buildAll runs at load)
        }
        if (EVICTED.add(sector)) {
            for (MergedBuf mb : MERGED.values()) {
                mb.evict(sector);
            }
        }
        final Map<String, SectorBuf> old = INDIVIDUAL.remove(sector);
        if (old != null) {
            for (SectorBuf b : old.values()) {
                b.vbo().close();
            }
        }
        if (group == null || group.isEmpty()) {
            return;
        }
        final Map<String, SectorBuf> made = new HashMap<>();
        try {
            for (Map.Entry<String, float[]> e : group.entrySet()) {
                if (skyKey(e.getKey()) || e.getValue().length < 24) {
                    continue;
                }
                final int verts = e.getValue().length / 6;
                final GpuBuffer vbo = bakeVbo(List.of(e.getValue()), verts,
                    sector + "/" + e.getKey());
                made.put(e.getKey(), new SectorBuf(vbo, verts));
            }
        } catch (Throwable th) {
            for (SectorBuf b : made.values()) {
                try {
                    b.vbo().close();
                } catch (Throwable ignored) {
                }
            }
            throw th; // buffers reclaimed — the failure itself still surfaces
        }
        INDIVIDUAL.put(sector, made);
    }

    /** Free every GPU buffer (level unload / encoding flip). Snapshot-then-close
     * under the class lock: disconnect teardown raced a second caller into a
     * ConcurrentModificationException here. */
    public static synchronized void dispose() {
        final List<GpuBuffer> all = new ArrayList<>();
        for (MergedBuf mb : MERGED.values()) {
            all.add(mb.vbo);
        }
        for (Map<String, SectorBuf> m : INDIVIDUAL.values()) {
            for (SectorBuf b : m.values()) {
                all.add(b.vbo());
            }
        }
        MERGED.clear();
        INDIVIDUAL.clear();
        EVICTED.clear();
        flatLight = null;
        for (GpuBuffer b : all) {
            try {
                b.close();
            } catch (Throwable ignored) {
                // a double-close on a teardown race must never take the client down
            }
        }
        PersistLightTexture.dispose();
    }

    public static synchronized boolean isEmpty() {
        return MERGED.isEmpty() && INDIVIDUAL.isEmpty();
    }

    // ---------------------------------------------------------------- draw

    /**
     * Draw the level: merged static buffers texture-major (one bind per texture,
     * one draw per surviving segment — the GPU eats uncullable static vertices
     * far cheaper than the CPU eats draw calls), then the few evicted sectors
     * with per-sector frustum culling.
     */
    public static void draw(Matrix4f mv, int tic, Frustum frustum,
                            Map<Integer, double[]> bounds, java.util.Set<Integer> moving,
                            double ox, double oy, double oz) {
        final long pt0 = LatteFrameStats.t();
        try {
            drawInner(mv, tic, frustum, bounds, moving, ox, oy, oz);
        } finally {
            LatteFrameStats.persist(pt0);
        }
    }

    /** Flat mode only: sectors whose light changed must rebake (their shade is
     * baked into the vertices). Lit mode reads light live — never needs this.
     * GLIDING sectors are skipped: their per-frame interp bake reads live light
     * already, and groups() deliberately holds pre-move geometry for them — a
     * rebuild here snapped a moving lift back to its old height (review find). */
    private static void syncFlatLight(Set<Integer> moving) {
        final var s = LatteWorld.snap();
        final Map<Integer, Map<String, float[]>> groups = LatteWorld.groups();
        if (s == null || s.light == null || groups == null) {
            return;
        }
        if (flatLight == null || flatLight.length != s.light.length) {
            flatLight = s.light.clone();
            return;
        }
        for (int i = 0; i < s.light.length; i++) {
            if (flatLight[i] != s.light[i]) {
                flatLight[i] = s.light[i];
                if (moving != null && moving.contains(i)) {
                    continue; // fresh interp bake this frame — already lit right
                }
                final Map<String, float[]> g = groups.get(i);
                if (g != null) {
                    rebuild(i, g); // evict (cheap segment edit) + rebake one sector
                }
            }
        }
    }

    private static synchronized void drawInner(Matrix4f mv, int tic, Frustum frustum,
                            Map<Integer, double[]> bounds, java.util.Set<Integer> moving,
                            double ox, double oy, double oz) {
        if (MERGED.isEmpty() && INDIVIDUAL.isEmpty()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (main == null) {
            return;
        }
        if (!bakedLit) {
            syncFlatLight(moving); // BEFORE the pass: rebakes record their own uploads
        }
        final GpuTextureView color = main.getColorTextureView();
        final GpuTextureView depth = main.useDepth ? main.getDepthTextureView() : null;
        final GpuSampler linClamp = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        final GpuTextureView overlayView = mc.gameRenderer.overlayTexture().getTextureView();
        final GpuTextureView lightmapView = mc.gameRenderer.lightmap();
        final var xform = RenderSystem.getDynamicUniforms().writeTransform(mv);

        final boolean lit = bakedLit;
        GpuTextureView lightView = null;
        if (lit) {
            // refresh the per-sector light feed BEFORE the pass opens (uploads
            // record their own commands); falls back to flat if unavailable
            final var s = LatteWorld.snap();
            if (s != null) {
                PersistLightTexture.update(s.light);
            }
            lightView = PersistLightTexture.view();
            if (lightView == null) {
                // lit-encoded vertex colors through the plain pipeline are
                // garbage tint, not a fallback — skip the frame (transient:
                // only before the first snapshot delivers light)
                return;
            }
        }
        final GpuDevice dev = RenderSystem.getDevice();
        try (RenderPass pass = dev.createCommandEncoder().createRenderPass(
                () -> "latte-doom-world", color, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(lit ? PersistLightPipeline.get() : pipeline());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", xform);
            pass.bindTexture("Sampler1", overlayView, linClamp);
            pass.bindTexture("Sampler2", lit ? lightView : lightmapView, linClamp);

            int mergedDraws = 0;
            int indivDraws = 0;
            int culled = 0;
            final var seq = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            for (Map.Entry<String, MergedBuf> te : MERGED.entrySet()) {
                final MergedBuf mb = te.getValue();
                if (mb.segments.length == 0) {
                    continue; // every contributor evicted
                }
                bindTex(pass, mc, te.getKey(), tic);
                pass.setVertexBuffer(0, mb.vbo.slice());
                // one sequential index buffer sized for the WHOLE quad range
                // serves every segment via firstIndex
                pass.setIndexBuffer(seq.getBuffer(mb.quadCount * 6), seq.type());
                for (int[] seg : mb.segments) {
                    pass.drawIndexed(seg[1] * 6, 1, seg[0] * 6, 0, 0);
                    mergedDraws++;
                }
            }
            for (Map.Entry<Integer, Map<String, SectorBuf>> ge : INDIVIDUAL.entrySet()) {
                final int sector = ge.getKey();
                if (frustum != null && bounds != null && !moving.contains(sector)) {
                    final double[] b = bounds.get(sector);
                    if (b != null && !frustum.isVisible(new AABB(
                            ox + b[0] - 1, oy + b[1] - 1, oz + b[2] - 1,
                            ox + b[3] + 1, oy + b[4] + 1, oz + b[5] + 1))) {
                        culled++;
                        continue; // off-screen evictee: skip
                    }
                }
                for (Map.Entry<String, SectorBuf> te : ge.getValue().entrySet()) {
                    final SectorBuf buf = te.getValue();
                    bindTex(pass, mc, te.getKey(), tic);
                    pass.setVertexBuffer(0, buf.vbo().slice());
                    final int ic = buf.indexCount();
                    pass.setIndexBuffer(seq.getBuffer(ic), seq.type());
                    pass.drawIndexed(ic, 1, 0, 0, 0);
                    indivDraws++;
                }
            }
            LatteFrameStats.persistDraws(mergedDraws, indivDraws, culled, lit);
        }
    }

    private static void bindTex(RenderPass pass, Minecraft mc, String key, int tic) {
        final Identifier id = idOf(LatteAnims.frame(key, tic));
        final AbstractTexture tex = mc.getTextureManager().getTexture(id);
        pass.bindTexture("Sampler0", tex.getTextureView(), tex.getSampler());
    }

    static Identifier idOf(String key) {
        return IDS.computeIfAbsent(key,
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private LatteSectorBuffers() {}
}
