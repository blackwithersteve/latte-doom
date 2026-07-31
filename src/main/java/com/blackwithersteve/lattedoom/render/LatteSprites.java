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
 * Billboards for every engine mobj in the snapshot — monsters, items, projectiles, the
 * marine. Pure translation: sprite + frame + angle come from the engine; we pick the view
 * with DOOM's own 8-rotation formula (r_things.c: rot = (viewerToThing - thingAngle +
 * 202.5°)/45°), anchor with the patch's left/top offsets, and light it with the sector's
 * live light (FF_FULLBRIGHT overrides). Widths are squeezed by 1/1.2 (the art was drawn
 * for the 1.2 vertical CRT stretch; heights stay true 1px = 1 map unit).
 * Cylindrical billboarding: quads face the camera horizontally.
 */
public final class LatteSprites {

    private static final int FULLBRIGHT = 0xF000F0;
    private static final double UNITS = LatteWorld.UNITS;
    // Aspect rule: DOOM's art was drawn
    // on 320x200 shown at 4:3, i.e. CRT pixels 1.2x TALLER than wide. Keeping HEIGHT true
    // (1px = 1 unit — correct against walls and collision height) therefore requires
    // squeezing WIDTH by 1/1.2 to preserve the drawn proportions; width-true "fixed" the
    // hitbox mismatch but makes every sprite visibly too wide. The collision box
    // poking past the art is GENUINE vanilla (a pinky's box is 60u wide vs ~44u of art —
    // demons are meant to block corridors); the earlier "twice as thick" was that original
    // behavior, not a rendering bug. So: the height-true rule stands.
    private static final double HSQUEEZE = 1.0 / 1.2;
    private static final double VSTRETCH = 1.0;
    private static final int FF_FRAMEMASK = 0x7FFF;
    private static final int FF_FULLBRIGHT_BIT = 0x8000;

    /** spritenum ordinal -> 4-char name ("TROO"), from the engine's own enum. */
    private static final String[] NAMES;

    static {
        final spritenum_t[] all = spritenum_t.values();
        NAMES = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            final String n = all[i].name();
            NAMES[i] = n.startsWith("SPR_") ? n.substring(4) : n;
        }
    }

    /** spritenum ordinal -> 4-char name, for anything rendering engine sprites (HUD too). */
    public static String spriteName(int ordinal) {
        if (ordinal >= 0 && ordinal < NAMES.length) {
            return NAMES[ordinal];
        }
        // DEH support: MBF-class DEHACKED patches extend the sprite table past the enum
        // (DOGS/PLS1/...); the engine-side deh state knows the extended names.
        return deh.DehState.spriteNameOf(ordinal);
    }

    private static final Map<String, Identifier> IDS = new HashMap<>();

    /** Scratch for the drawn-position lookup; the client thread is the only caller. */
    private static final double[] drawn = new double[3];

    /** Called inside the renderer's origin-translated pose. Camera position in WORLD coords. */
    public static void submit(PoseStack pose, SubmitNodeCollector collector,
                              WorldSnapshot snap, DoomMap map, SpriteSet sprites,
                              double camWorldX, double camWorldZ,
                              double originX, double originZ, double cx, double cy) {
        if (snap == null || sprites == null) {
            return;
        }
        // Positions come from LatteWorld's keyframe table, which the thing collision reads
        // as well, so a monster is drawn exactly where its blocking box is.

        // camera in DOOM map space (for rotation picking + billboard facing)
        final double camDx = (camWorldX - originX) * UNITS + cx;
        final double camDy = cy - (camWorldZ - originZ) * UNITS;

        // every possessed body (the owner's AND the remote players[1..3]) belongs to a
        // real Minecraft player whose avatar already stands there — never double them
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
                continue; // possessed: the marine is YOU — don't draw him at your feet
            }
            if (possessed != null && possessed.contains(snap.mId[i])) {
                continue;
            }
            final int ord = snap.mSprite[i];
            // resolve via spriteName(): DEH-extended ordinals (DOGS/PLS1...) live past
            // the enum-built NAMES table and must not be skipped
            final String sprName = spriteName(ord);
            if (sprName == null) {
                continue;
            }
            LatteWorld.thingDrawn(snap, i, drawn);
            final double mx = drawn[0], my = drawn[1], mz = drawn[2];
            final double vdx = mx - camDx, vdy = my - camDy;
            final double dist = Math.hypot(vdx, vdy);
            if (dist < 1.0e-3) {
                continue; // camera inside the thing
            }
            // DOOM's rotation pick (degrees, CCW like the engine's angles)
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
            // vanilla keeps the SAME screen box for mirrored views and only reverses the
            // texture columns (r_things xiscale flip) — so the anchor never mirrors
            final int leftOfs = ofs[0];

            // billboard axes: the VIEWER'S RIGHT = view direction rotated -90° in doom's
            // CCW y-up plane = (vdy, -vdx). The +90° rotation points screen-LEFT and
            // mirrors every sprite horizontally (rotation frames flip left-for-right —
            // the art's right edge lands on the viewer's left).
            final double rxD = vdy / dist, ryD = -vdx / dist;
            // doom -> mesh-local (the pose is already origin-translated): X=(x-cx)/32, Z=(cy-y)/32
            final float rx = (float) (rxD / UNITS), rz = (float) (-ryD / UNITS);
            final float bxc = (float) ((mx - cx) / UNITS), bzc = (float) ((cy - my) / UNITS);

            final double l0 = -leftOfs * HSQUEEZE, l1 = (w - leftOfs) * HSQUEEZE;
            final float x0 = (float) (bxc + rx * l0), z0 = (float) (bzc + rz * l0);
            final float x1 = (float) (bxc + rx * l1), z1 = (float) (bzc + rz * l1);

            final int sec = map != null ? map.sectorAt(mx, my) : -1;
            // Smart sprite clip (the GZDoom fix): some art extends below its origin; the 1993
            // software renderer CLIPPED those pixels at the floor, but in true 3D they sink in.
            // Grounded things get lifted so feet touch the floor (capped — deliberate deep
            // sinks stay); airborne things (missiles, cacos, lost souls) are never touched.
            double lift = 0;
            if (sec >= 0 && sec < snap.floorH.length) {
                final double floor = snap.floorH[sec];
                final double bottom = mz + ofs[1] - h;
                if (mz - floor < 0.5 && bottom < floor) {
                    lift = Math.min(floor - bottom, 24.0);
                }
            }
            // feet stay grounded (yBot, what the lift logic grounds); stretch the height up by
            // VSTRETCH so the aspect matches DOOM's CRT look without squeezing the width
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
        // route through the sanitizer: bracket-rotation sprite lumps (BBRN[1) must map to
        // the same safe name DoomRuntimeTextures registered
        return IDS.computeIfAbsent(DoomRuntimeTextures.safe(key),
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private LatteSprites() {}
}
