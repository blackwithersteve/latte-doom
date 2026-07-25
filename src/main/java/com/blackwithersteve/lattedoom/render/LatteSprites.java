package com.blackwithersteve.lattedoom.render;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import data.spritenum_t;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a billboard for every map object in the snapshot: monsters, items, projectiles and
 * other players' bodies.
 *
 * <p>This is a direct translation of engine state. Sprite, frame and angle come from the
 * snapshot; the view is selected with the engine's own eight-rotation formula from
 * {@code r_things.c}, {@code (viewerToThing - thingAngle + 202.5°) / 45°}; the quad is
 * anchored using the patch's own left and top offsets; and it is lit by the sector's
 * current light level unless the frame is marked full-bright. Billboarding is cylindrical,
 * so quads face the camera horizontally but stay upright.
 */
public final class LatteSprites {

    private static final int FULLBRIGHT = 0xF000F0;
    private static final double UNITS = LatteWorld.UNITS;
    // The artwork was drawn for a 320x200 image shown at 4:3, so its pixels are 1.2 times
    // taller than wide. Height stays true at one pixel per map unit, which is what matches
    // wall heights and collision, so width is reduced by the same factor. Drawing at true
    // width instead makes every sprite look too wide; a collision box wider than the
    // artwork is original behaviour.
    private static final double HSQUEEZE = 1.0 / 1.2;
    private static final double VSTRETCH = 1.0;
    private static final int FF_FRAMEMASK = 0x7FFF;
    private static final int FF_FULLBRIGHT_BIT = 0x8000;

    /** Sprite index to four-character name, taken from the engine's own enumeration. */
    private static final String[] NAMES;

    static {
        final spritenum_t[] all = spritenum_t.values();
        NAMES = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            final String n = all[i].name();
            NAMES[i] = n.startsWith("SPR_") ? n.substring(4) : n;
        }
    }

    /** Sprite index to four-character name, for any code drawing engine sprites. */
    public static String spriteName(int ordinal) {
        if (ordinal >= 0 && ordinal < NAMES.length) {
            return NAMES[ordinal];
        }
        // MBF-class DEHACKED patches extend the sprite table beyond the built-in enum, so
        // extended names are resolved from the patch state instead.
        return deh.DehState.spriteNameOf(ordinal);
    }

    private static final Map<String, Identifier> IDS = new HashMap<>();

    // ---- Interpolation: object positions are blended between the last two engine tics
    // so that motion is smooth at any frame rate. ----
    private static int lastTic = -1;
    private static Map<Integer, double[]> prevPos = new HashMap<>();
    private static Map<Integer, double[]> curPos = new HashMap<>();
    /** One tic at top speed covers about 30 map units, so a larger step is a teleport or
     * respawn and must snap rather than interpolate. */
    private static final double TELEPORT_SNAP = 128.0;

    /** Called inside the renderer's origin-translated pose, with the camera position in
     * world coordinates. */
    public static void submit(PoseStack pose, SubmitNodeCollector collector,
                              WorldSnapshot snap, DoomMap map, SpriteSet sprites,
                              double camWorldX, double camWorldZ,
                              double originX, double originZ, double cx, double cy) {
        if (snap == null || sprites == null) {
            return;
        }
        if (snap.tic != lastTic) { // new engine tic: the current keyframe becomes previous
            prevPos = curPos;
            curPos = new HashMap<>(snap.mobjCount * 2);
            for (int i = 0; i < snap.mobjCount; i++) {
                curPos.put(snap.mId[i], new double[]{snap.mx[i], snap.my[i], snap.mz[i]});
            }
            lastTic = snap.tic;
        }
        final double alpha = LatteWorld.alpha(); // the same clock the moving sectors use

        // The camera in map space, used for view selection and billboard facing.
        final double camDx = (camWorldX - originX) * UNITS + cx;
        final double camDy = cy - (camWorldZ - originZ) * UNITS;

        // Every mirrored player body belongs to a real Minecraft player whose avatar is
        // already drawn at that position, so those objects must not be drawn twice.
        java.util.Set<Integer> possessed = null;
        if (snap.rbMobjId != null) {
            possessed = new java.util.HashSet<>();
            for (int id : snap.rbMobjId) {
                possessed.add(id);
            }
        }
        final Map<Identifier, List<float[]>> batches = new HashMap<>();
        for (int i = 0; i < snap.mobjCount; i++) {
            if (i == snap.playerMobj && (LatteWorld.playMode() || snap.remote)) {
                continue; // this object is the local player's own body
            }
            if (possessed != null && possessed.contains(snap.mId[i])) {
                continue;
            }
            final int ord = snap.mSprite[i];
            // Resolve through spriteName: DEHACKED-extended sprite indices lie beyond the
            // built-in name table and must still be drawn.
            final String sprName = spriteName(ord);
            if (sprName == null) {
                continue;
            }
            double mx = snap.mx[i], my = snap.my[i], mz = snap.mz[i];
            final double[] p0 = prevPos.get(snap.mId[i]);
            if (p0 != null && Math.abs(p0[0] - mx) + Math.abs(p0[1] - my) < TELEPORT_SNAP) {
                mx = p0[0] + (mx - p0[0]) * alpha;
                my = p0[1] + (my - p0[1]) * alpha;
                mz = p0[2] + (mz - p0[2]) * alpha;
            }
            final double vdx = mx - camDx, vdy = my - camDy;
            final double dist = Math.hypot(vdx, vdy);
            if (dist < 1.0e-3) {
                continue; // the camera is inside the object
            }
            // The engine's view selection, in degrees, counter-clockwise as it uses.
            final double viewerToThing = Math.toDegrees(Math.atan2(vdy, vdx));
            final int rot = (int) Math.floor(
                (((viewerToThing - snap.mAngleDeg[i] + 202.5) % 360.0) + 360.0) % 360.0 / 45.0) & 7;
            final SpriteSet.View view = sprites.view(sprName, snap.mFrame[i] & FF_FRAMEMASK, rot);
            if (view == null) {
                continue;
            }
            final String key = "sprites/" + view.lump();
            final int[] size = DoomRuntimeTextures.textureSize(key);
            final int[] ofs = DoomRuntimeTextures.spriteOffset(key);
            if (size == null || ofs == null) {
                continue;
            }
            final int w = size[0], h = size[1];
            // Mirrored views keep the same screen box and only reverse the texture
            // columns, so the anchor offset is never mirrored with them.
            final int leftOfs = ofs[0];

            // Billboard axes: the viewer's right is the view direction rotated by -90° in
            // the engine's counter-clockwise plane, that is (vdy, -vdx). Rotating by +90°
            // instead points screen-left and mirrors every sprite horizontally.
            final double rxD = vdy / dist, ryD = -vdx / dist;
            // Map space to mesh-local space; the pose is already translated to the origin.
            final float rx = (float) (rxD / UNITS), rz = (float) (-ryD / UNITS);
            final float bxc = (float) ((mx - cx) / UNITS), bzc = (float) ((cy - my) / UNITS);

            final double l0 = -leftOfs * HSQUEEZE, l1 = (w - leftOfs) * HSQUEEZE;
            final float x0 = (float) (bxc + rx * l0), z0 = (float) (bzc + rz * l0);
            final float x1 = (float) (bxc + rx * l1), z1 = (float) (bzc + rz * l1);

            final int sec = map != null ? map.sectorAt(mx, my) : -1;
            // Some artwork extends below its own origin. The software renderer clipped
            // those pixels at the floor, but in 3D they sink into it, so grounded objects
            // are lifted until their feet meet the floor. The lift is capped so that
            // deliberately sunken objects keep their intended depth, and airborne objects
            // such as projectiles and flying monsters are never adjusted.
            double lift = 0;
            if (sec >= 0 && sec < snap.floorH.length) {
                final double floor = snap.floorH[sec];
                final double bottom = mz + ofs[1] - h;
                if (mz - floor < 0.5 && bottom < floor) {
                    lift = Math.min(floor - bottom, 24.0);
                }
            }
            // The feet stay on the ground and the sprite is scaled upwards, so the aspect
            // ratio is corrected without moving the anchor.
            final float yBot = (float) ((mz + ofs[1] + lift - h) / UNITS);
            final float yTop = (float) ((mz + ofs[1] + lift - h + h * VSTRETCH) / UNITS);

            final float u0 = view.flip() ? 1 : 0, u1 = view.flip() ? 0 : 1;
            final boolean bright = (snap.mFrame[i] & FF_FULLBRIGHT_BIT) != 0;
            final int light = bright ? 255
                : (sec >= 0 && sec < snap.light.length ? snap.light[sec] : 255);
            final float shade = LatteMesh.shadeByte(light) / 255.0f;

            batches.computeIfAbsent(idOf(key), k -> new ArrayList<>()).add(new float[]{
                x0, yTop, z0, u0, x1, yBot, z1, u1, shade});
        }

        for (Map.Entry<Identifier, List<float[]>> e : batches.entrySet()) {
            final List<float[]> quads = e.getValue();
            collector.submitCustomGeometry(pose, RenderTypes.entityCutout(e.getKey()),
                (p, vc) -> {
                    for (float[] q : quads) {
                        final int c = (int) (q[8] * 255.0f);
                        vertex(vc, p, q[0], q[1], q[2], q[3], 0, c);
                        vertex(vc, p, q[4], q[1], q[6], q[7], 0, c);
                        vertex(vc, p, q[4], q[5], q[6], q[7], 1, c);
                        vertex(vc, p, q[0], q[5], q[2], q[3], 1, c);
                    }
                });
        }
    }

    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose p,
                               float x, float y, float z, float u, float v, int c) {
        vc.addVertex(p, x, y, z)
          .setColor(c, c, c, 255)
          .setUv(u, v)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(FULLBRIGHT)
          .setNormal(p, 0.0f, 1.0f, 0.0f);
    }

    private static Identifier idOf(String key) {
        // Route through the name sanitiser: lump names containing characters that are not
        // valid in resource identifiers must map to the same safe name they were registered
        // under.
        return IDS.computeIfAbsent(DoomRuntimeTextures.safe(key),
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private LatteSprites() {}
}
