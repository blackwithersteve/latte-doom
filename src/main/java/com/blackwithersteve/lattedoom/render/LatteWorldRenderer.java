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
 * Draws the raised level: one custom-geometry batch per texture, vertices pre-baked by
 * {@link LatteWorld}. Called once per frame from the LevelRenderer.submitEntities tail (mixin) —
 * the same deferred-submit surface entity renderers use, no fake entities involved. Sector light
 * rides in vertex colour over a fullbright lightmap coord (vertex slot 5 = sector index, resolved
 * against the ENGINE's live light levels): DOOM rooms light themselves, the MC sky has no vote.
 */
public final class LatteWorldRenderer {

    private static final int FULLBRIGHT = 0xF000F0;
    private static final Map<String, Identifier> IDS = new HashMap<>();
    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("lattedoom");
    private static int frameCount;

    /** Frustum culling toggle (S1). Default ON; `-Dlattedoom.cull=false` (or /cull off)
     * turns it off — the "without" side of the A/B comparison. */
    public static volatile boolean CULL_ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("lattedoom.cull"));

    public static void submit(PoseStack pose, LevelRenderState lrs, SubmitNodeCollector collector) {
        // suit-only (marine in the overworld, no /load) raises no world: the geometry and
        // its monsters must not appear in the survival world — only the HUD/gun do
        if (!LatteWorld.warpedIn()) {
            return;
        }
        final Map<Integer, Map<String, float[]>> groups = LatteWorld.groups();
        if (groups == null || groups.isEmpty() || lrs.cameraRenderState == null) {
            return;
        }
        final Vec3 cam = lrs.cameraRenderState.pos;

        // advance mover keyframes at the engine's 35Hz; sectors mid-glide re-bake THIS FRAME
        // at the interpolated height (uncapped interpolation) — doors glide at full FPS
        LatteWorld.renderSync();
        final java.util.Set<Integer> moving = LatteWorld.movingSectors();
        final double a = LatteWorld.alpha();
        final var snap = LatteWorld.snap();
        final int tic = snap != null ? snap.tic : 0;
        // MC's own prepared cull frustum (per-sector world AABB test)
        final net.minecraft.client.renderer.culling.Frustum frustum =
            lrs.cameraRenderState.cullFrustum;
        final Map<Integer, double[]> bounds = LatteWorld.sectorBounds();
        final double ox = LatteWorld.originX(), oy = LatteWorld.originY(), oz = LatteWorld.originZ();

        // S2 — PERSISTENT GEOMETRY: the level's static geometry is baked into GPU buffers
        // once and redrawn from a dedicated pass at LevelRenderer.render TAIL (see the mixin
        // + LatteWorld.drawPersistent). Here we only advance movers + hand the camera to that
        // pass; the per-frame vertex re-emission below is SKIPPED. Behind /persist (default off).
        final boolean persist = LatteSectorBuffers.ENABLED;
        if (persist) {
            // build once (lazy) + keep the moving sectors' buffers current; the camera + draw
            // are handled from the render TAIL mixin (CameraRenderState.viewRotationMatrix)
            if (LatteSectorBuffers.isEmpty()) {
                LatteSectorBuffers.buildAll(groups);
            }
            for (int s : moving) {
                LatteSectorBuffers.rebuild(s, LatteWorld.bakeInterp(s, a)); // interpolated movers
            }
        }

        pose.pushPose();
        pose.translate(ox - cam.x, oy - cam.y, oz - cam.z);

        final Map<Identifier, List<float[]>> skyFrame = new HashMap<>();
        final Map<Identifier, List<float[]>> skyFlatFrame = new HashMap<>();
        if (!persist) {
            // ---- CLASSIC PER-FRAME PATH: flatten per-sector groups into one draw per texture;
            // animated flats/walls resolve their CURRENT frame here (P_UpdateSpecials timing).
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
                        continue; // out of view — do not submit
                    }
                }
                final Map<String, float[]> group = isMoving
                    ? LatteWorld.bakeInterp(g.getKey(), a) : g.getValue();
                for (Map.Entry<String, float[]> e : group.entrySet()) {
                    if (e.getKey().startsWith("sky!") || e.getKey().startsWith("fsky!")) {
                        if (SkyPipeline.available()) {
                            (e.getKey().charAt(0) == 'f' ? skyFlatFrame : skyFrame)
                                .computeIfAbsent(skyId(e.getKey(), snap),
                                    k -> new ArrayList<>()).add(e.getValue());
                        }
                        continue; // never through the texture pipeline
                    }
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
                // single-faced, culled — the hardware-port model: GZDoom draws every
                // surface facing its own sector only, and from outside a level you look
                // INTO the rooms. Double-faced walls showed mirrored exteriors no real
                // port shows. What sells the outside view there is not back faces, it is
                // the SKY drawn as surfaces rather than a backdrop — the sky pass below.
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

        if (persist && SkyPipeline.available()) {
            // the persistent path skips the flatten loop, but the sky pass always rides
            // the per-frame submit — collect just the sky keys
            for (Map.Entry<Integer, Map<String, float[]>> g : groups.entrySet()) {
                for (Map.Entry<String, float[]> e : g.getValue().entrySet()) {
                    if (e.getKey().startsWith("sky!") || e.getKey().startsWith("fsky!")) {
                        (e.getKey().charAt(0) == 'f' ? skyFlatFrame : skyFrame)
                            .computeIfAbsent(skyId(e.getKey(), snap),
                                k -> new ArrayList<>()).add(e.getValue());
                    }
                }
            }
        }
        submitSkyBatches(pose, collector, skyFlatFrame, true);
        submitSkyBatches(pose, collector, skyFrame, false);

        // the living things: every engine mobj as a DOOM billboard, in the same pose space
        // (sprites stay on the submit path in BOTH modes)
        LatteSprites.submit(pose, collector, LatteWorld.snap(), LatteWorld.mapRef(),
            LatteWorld.sprites(), cam.x, cam.z,
            LatteWorld.originX(), LatteWorld.originZ(), LatteWorld.cx(), LatteWorld.cy());

        // the backdrop cylinder survives only as the fallback: with the sky pass live,
        // sky exists exactly where the map put sky surfaces, and nowhere else
        if (!SkyPipeline.available()) {
            submitSky(pose, collector, cam);
        }

        pose.popPose();
    }

    /**
     * DOOM's sky. Sky ceilings are holes (LatteMesh's sky hack: F_SKY1 flats not emitted,
     * sky-to-sky uppers skipped); behind them we draw the map's sky TEXTURE (engine truth
     * via the snapshot — episode skies, PWAD replacements, all of it) on a camera-centered
     * cylinder whose radius sits just inside the far plane: every pixel nothing else drew
     * resolves to sky, and all real geometry occludes it — order-independent, no extra pass.
     *
     * Mapping is vanilla r_sky: 1024 angle-columns per revolution (a 256-wide sky repeats
     * 4x around, BTSX's 1024-wide wraps exactly once), texturemid row 100 on the horizon at
     * ~0.3685° per texel row. Rows clamp at the poles — Minecraft free-look can stare where
     * 1993 couldn't; stretching the edge rows (the GZDoom compromise) beats wrapping the
     * mountains overhead. Fullbright, exactly like the engine draws it.
     */
    private static void submitSkyBatches(PoseStack pose, SubmitNodeCollector collector,
            Map<Identifier, List<float[]>> batches, boolean flat) {
        for (Map.Entry<Identifier, List<float[]>> e : batches.entrySet()) {
            final var type = flat ? SkyPipeline.flatType(e.getKey())
                : SkyPipeline.type(e.getKey());
            if (type == null) {
                return;
            }
            final List<float[]> lists = e.getValue();
            collector.submitCustomGeometry(pose, type, (p, vc) -> {
                for (float[] v : lists) {
                    for (int i = 0; i < v.length; i += 6) {
                        vc.addVertex(p, v[i], v[i + 1], v[i + 2])
                          .setColor(255, 255, 255, 255)
                          .setUv(v[i + 3], v[i + 4])
                          .setOverlay(OverlayTexture.NO_OVERLAY)
                          .setLight(FULLBRIGHT)
                          .setNormal(p, 0.0f, 1.0f, 0.0f);
                    }
                }
            });
        }
    }

    /** A "sky!"/"fsky!" batch key to its texture: "*" is the level's own sky. */
    private static Identifier skyId(String key,
            com.blackwithersteve.lattedoom.engine.WorldSnapshot snap) {
        String name = key.substring(key.indexOf('!') + 1);
        if (name.equals("*")) {
            name = snap != null && snap.skyTexture != null
                ? snap.skyTexture.toLowerCase(java.util.Locale.ROOT) : "sky1";
        }
        return idOf("walls/" + name);
    }

    private static void submitSky(PoseStack pose, SubmitNodeCollector collector, Vec3 cam) {
        final var snap = LatteWorld.snap();
        String sky = snap != null ? snap.skyTexture : null;
        // Boom sky transfer (specials 271 and 272) gives tagged sectors their own sky. The
        // sky is one camera-centred cylinder, so the sector the camera stands in selects it;
        // that is exact everywhere except a vantage point showing two sky regions at once,
        // which needs a per-opening pass to resolve.
        boolean flip = false;
        int xofs = 0;
        final DoomMap map = LatteWorld.map();
        if (map != null && map.hasSkyTransfers()) {
            final int camSec = map.sectorAt(
                LatteWorld.worldToDoomX(cam.x), LatteWorld.worldToDoomY(cam.z));
            final String transferred = map.skyTextureFor(camSec);
            if (transferred != null) {
                sky = transferred;
                flip = map.skyFlippedFor(camSec);
                xofs = map.skyXOffsetFor(camSec);
            }
        }
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

        // (A projected "occluder shell" over sky faces was tried for the window-leak
        // problem and REVERTED: affine UV interpolation across large polygons warped the
        // sky badly. The correct version needs subdivided geometry or a dedicated pass.)
        final List<float[]> quads = new ArrayList<>(segs * 3);
        for (int i = 0; i < segs; i++) {
            final double t0 = i / (double) segs, t1 = (i + 1) / (double) segs;
            final double a0 = t0 * Math.PI * 2.0, a1 = t1 * Math.PI * 2.0;
            // doom angle -> world direction: doom x+ = world x+, doom y+ = world z-
            final double c0 = Math.cos(a0), s0 = -Math.sin(a0);
            final double c1 = Math.cos(a1), s1 = -Math.sin(a1);
            final double span = repeats / segs;
            final double shift = xofs / (double) size[0];
            float u0 = (float) ((i * span + shift) % 1.0);
            float u1 = (float) (u0 + span);
            if (flip) {
                // Special 272 mirrors the sky: reverse the column direction across the quad.
                final float t = u0;
                u0 = -u1;
                u1 = -t;
            }
            // three bands: zenith cap (top row stretched), the mapped sky, nadir cap
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

    /** One sky quad: the [eHi..eLo] elevation band of one azimuth segment (x,y,z,u,v ×4). */
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

    /** Texture key ("walls/startan3") -> the Identifier DoomRuntimeTextures registered it under. */
    private static Identifier idOf(String key) {
        return IDS.computeIfAbsent(key,
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private LatteWorldRenderer() {}
}
