package com.blackwithersteve.lattedoom.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the raised level as one custom-geometry batch per texture, from vertices prepared by
 * {@link LatteWorld}. It runs once per frame from the level renderer's entity-submit pass,
 * the same deferred surface entity renderers use, so no placeholder entities are required.
 *
 * <p>Sector light is carried in the vertex colour over a full-bright lightmap coordinate,
 * with each vertex naming its sector so the current engine light level can be applied.
 * A DOOM room is therefore lit by its own light levels and not by Minecraft's sky light.
 */
public final class LatteWorldRenderer {

    private static final int FULLBRIGHT = 0xF000F0;
    private static final Map<String, Identifier> IDS = new HashMap<>();
    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("lattedoom");
    private static int frameCount;

    /** Per-sector frustum culling, on by default. {@code -Dlattedoom.cull=false}, or
     * {@code /cull off}, disables it, which is useful when measuring its effect. */
    public static volatile boolean CULL_ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("lattedoom.cull"));

    public static void submit(PoseStack pose, LevelRenderState lrs, SubmitNodeCollector collector) {
        // A transformed player outside a level raises no geometry: the level and its
        // monsters must not appear in the surrounding world, only the weapon and HUD.
        if (!LatteWorld.warpedIn()) {
            return;
        }
        final Map<Integer, Map<String, float[]>> groups = LatteWorld.groups();
        if (groups == null || groups.isEmpty() || lrs.cameraRenderState == null) {
            return;
        }
        final Vec3 cam = lrs.cameraRenderState.pos;

        // Advance the keyframes at the engine's 35 Hz. Sectors currently moving are rebuilt
        // this frame at their interpolated height, so they move smoothly at any frame rate.
        LatteWorld.renderSync();
        final java.util.Set<Integer> moving = LatteWorld.movingSectors();
        final double a = LatteWorld.alpha();
        final var snap = LatteWorld.snap();
        final int tic = snap != null ? snap.tic : 0;
        // Minecraft's own prepared cull frustum, tested per sector against a world-space box.
        final net.minecraft.client.renderer.culling.Frustum frustum =
            lrs.cameraRenderState.cullFrustum;
        final Map<Integer, double[]> bounds = LatteWorld.sectorBounds();
        final double ox = LatteWorld.originX(), oy = LatteWorld.originY(), oz = LatteWorld.originZ();

        // Persistent-geometry mode: static geometry is baked into GPU buffers once and
        // redrawn from a dedicated pass later in the frame. In this mode only the moving
        // sectors are updated here and the per-frame vertex emission below is skipped.
        final boolean persist = LatteSectorBuffers.ENABLED;
        if (persist) {
            // Build lazily once, then keep the moving sectors' buffers current; the camera
            // and the draw call itself are handled by the later render pass.
            if (LatteSectorBuffers.isEmpty()) {
                LatteSectorBuffers.buildAll(groups);
            }
            for (int s : moving) {
                LatteSectorBuffers.rebuild(s, LatteWorld.bakeInterp(s, a)); // interpolated movers
            }
        }

        pose.pushPose();
        pose.translate(ox - cam.x, oy - cam.y, oz - cam.z);

        if (!persist) {
            // ---- Per-frame path: flatten the per-sector groups into one draw per texture.
            // Animated flats and walls resolve their current frame here, on the engine's
            // own animation timing. ----
            int total = 0, culled = 0;
            final Map<Identifier, List<float[]>> frame = new HashMap<>();
            for (Map.Entry<Integer, Map<String, float[]>> g : groups.entrySet()) {
                total++;
                final boolean isMoving = moving.contains(g.getKey());
                if (CULL_ENABLED && frustum != null && !isMoving && bounds != null) {
                    final double[] b = bounds.get(g.getKey());
                    if (b != null && !frustum.isVisible(new net.minecraft.world.phys.AABB(
                            ox + b[0] - 1, oy + b[1] - 1, oz + b[2] - 1,
                            ox + b[3] + 1, oy + b[4] + 1, oz + b[5] + 1))) {
                        culled++;
                        continue; // out of view: do not submit
                    }
                }
                final Map<String, float[]> group = isMoving
                    ? LatteWorld.bakeInterp(g.getKey(), a) : g.getValue();
                for (Map.Entry<String, float[]> e : group.entrySet()) {
                    frame.computeIfAbsent(idOf(LatteAnims.frame(e.getKey(), tic)), k -> new ArrayList<>())
                        .add(e.getValue());
                }
            }
            if (frustum != null && (frameCount++ % 200) == 0) {
                LOGGER.info("LatteWorld cull: {} of {} sectors drawn ({} culled)",
                    total - culled, total, culled);
            }
            for (Map.Entry<Identifier, List<float[]>> e : frame.entrySet()) {
                final List<float[]> lists = e.getValue();
                collector.submitCustomGeometry(pose, RenderTypes.entityCutout(e.getKey()),
                    (p, vc) -> {
                        for (float[] v : lists) {
                            for (int i = 0; i < v.length; i += 6) {
                                final int c = LatteMesh.shadeByte(LatteWorld.lightOf((int) v[i + 5]));
                                vc.addVertex(p, v[i], v[i + 1], v[i + 2])
                                  .setColor(c, c, c, 255)
                                  .setUv(v[i + 3], v[i + 4])
                                  .setOverlay(OverlayTexture.NO_OVERLAY)
                                  .setLight(FULLBRIGHT)
                                  .setNormal(p, 0.0f, 1.0f, 0.0f);
                            }
                        }
                    });
            }
        }

        // Map objects are drawn as billboards in the same pose space; sprites use the
        // submit path in both rendering modes.
        LatteSprites.submit(pose, collector, LatteWorld.snap(), LatteWorld.mapRef(),
            LatteWorld.sprites(), cam.x, cam.z,
            LatteWorld.originX(), LatteWorld.originZ(), LatteWorld.cx(), LatteWorld.cy());

        // The sky, behind everything else: sky ceilings are left open in the mesh, and this
        // fills the resulting gaps.
        submitSky(pose, collector, cam);

        pose.popPose();
    }

    /**
     * Draws the sky. Sky ceilings are left open by the mesh builder, and the map's own sky
     * texture is drawn behind them on a camera-centred cylinder whose radius sits just
     * inside the far plane, so uncovered pixels resolve to sky and real geometry occludes
     * it without needing a particular draw order or a separate pass.
     *
     * <p>The mapping follows {@code r_sky}: 1024 angular columns per revolution, so a
     * 256-pixel sky repeats four times around the horizon and a 1024-pixel one wraps once,
     * with texture row 100 on the horizon at about 0.3685 degrees per row. Rows clamp at the
     * poles, since free look can point where the original could not, and stretching the edge
     * rows beats repeating the sky overhead. Drawn full-bright, as the engine draws it.
     */
    private static void submitSky(PoseStack pose, SubmitNodeCollector collector, Vec3 cam) {
        final var snap = LatteWorld.snap();
        final String sky = snap != null ? snap.skyTexture : null;
        if (sky == null) {
            return;
        }
        final String key = "walls/" + sky.toLowerCase(java.util.Locale.ROOT);
        final int[] size = DoomRuntimeTextures.textureSize(key);
        if (size == null) {
            return;
        }
        final double radius = Math.max(96.0,
            net.minecraft.client.Minecraft.getInstance().options.renderDistance().get()
                * 16.0 * 0.85);
        final double degPerRow = 0.3685; // 100 rows from horizon to +36.85° (texturemid)
        final double eTop = Math.toRadians(Math.min(88.0, 100.0 * degPerRow));
        final double eBot = -Math.toRadians(Math.min(88.0,
            Math.max(5.0, (size[1] - 100.0) * degPerRow)));
        final double repeats = 1024.0 / size[0];
        final int segs = 32;
        final double bx = cam.x - LatteWorld.originX();
        final double by = cam.y - LatteWorld.originY();
        final double bz = cam.z - LatteWorld.originZ();

        // Geometry that occludes through sky openings, so that distant parts of the
        // level are not visible through a window, is not implemented. Projecting the sky
        // onto such faces per vertex distorts it, because the projection is angular while
        // interpolation across a polygon is affine; doing it correctly requires subdivided
        // geometry or a dedicated pass.
        final List<float[]> quads = new ArrayList<>(segs * 3);
        for (int i = 0; i < segs; i++) {
            final double t0 = i / (double) segs, t1 = (i + 1) / (double) segs;
            final double a0 = t0 * Math.PI * 2.0, a1 = t1 * Math.PI * 2.0;
            // Map angle to world direction: map +x is world +x, and map +y is world -z.
            final double c0 = Math.cos(a0), s0 = -Math.sin(a0);
            final double c1 = Math.cos(a1), s1 = -Math.sin(a1);
            final double span = repeats / segs;
            final float u0 = (float) ((i * span) % 1.0);
            final float u1 = (float) (u0 + span);
            // Three bands: the zenith cap with the top row stretched, the mapped sky,
            // and the nadir cap.
            quads.add(band(bx, by, bz, radius, c0, s0, c1, s1,
                Math.PI / 2.0, eTop, u0, u1, 0f, 0f));
            quads.add(band(bx, by, bz, radius, c0, s0, c1, s1,
                eTop, eBot, u0, u1, 0f, 1f));
            quads.add(band(bx, by, bz, radius, c0, s0, c1, s1,
                eBot, -Math.PI / 2.0, u0, u1, 1f, 1f));
        }
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(idOf(key)),
            (p, vc) -> {
                for (float[] q : quads) {
                    for (int i = 0; i < q.length; i += 5) {
                        vc.addVertex(p, q[i], q[i + 1], q[i + 2])
                          .setColor(255, 255, 255, 255)
                          .setUv(q[i + 3], q[i + 4])
                          .setOverlay(OverlayTexture.NO_OVERLAY)
                          .setLight(FULLBRIGHT)
                          .setNormal(p, 0.0f, 1.0f, 0.0f);
                    }
                }
            });
    }

    /** One sky quad: the elevation band between two angles for a single azimuth
     * segment, as four vertices of position and texture coordinates. */
    private static float[] band(double bx, double by, double bz, double r,
                                double c0, double s0, double c1, double s1,
                                double eHi, double eLo, float u0, float u1,
                                float vHi, float vLo) {
        final double chH = Math.cos(eHi) * r, yH = by + Math.sin(eHi) * r;
        final double chL = Math.cos(eLo) * r, yL = by + Math.sin(eLo) * r;
        return new float[]{
            (float) (bx + c0 * chH), (float) yH, (float) (bz + s0 * chH), u0, vHi,
            (float) (bx + c1 * chH), (float) yH, (float) (bz + s1 * chH), u1, vHi,
            (float) (bx + c1 * chL), (float) yL, (float) (bz + s1 * chL), u1, vLo,
            (float) (bx + c0 * chL), (float) yL, (float) (bz + s0 * chL), u0, vLo,
        };
    }

    /** Maps a texture key such as {@code walls/startan3} to the identifier it was
     * registered under. */
    private static Identifier idOf(String key) {
        return IDS.computeIfAbsent(key,
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private LatteWorldRenderer() {}
}
