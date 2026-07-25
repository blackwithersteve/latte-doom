package com.blackwithersteve.lattedoom.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a transformed player as a billboard built from the WAD's own {@code PLAY} sprites
 * instead of the Minecraft player model. This is what other players and the third-person
 * camera see.
 *
 * <p>Frame selection follows the engine's {@code S_PLAY_*} state timings: standing frame
 * A, running A to D every 4 tics, attacking E, pain G, and the view is chosen with DOOM's
 * eight-rotation rule relative to the camera. Feet are anchored at the entity origin, with
 * the same clipping lift the world sprites use. All sprite pixels come from the runtime
 * texture compositor, so nothing is shipped with the mod.
 */
public final class MarineBody {

    private static final Map<String, Identifier> IDS = new HashMap<>();
    private static final int FULLBRIGHT = 0xF000F0;
    private static final double HSQUEEZE = 1.0 / 1.2;

    public static void submit(AvatarRenderState state, PoseStack pose,
                              SubmitNodeCollector collector, CameraRenderState camera) {
        final SpriteSet sprites = LatteWorld.sprites();
        if (sprites == null) {
            return;
        }
        // Derive the S_PLAY frame from the render state: the engine does not animate a
        // mirrored player object, so the animation is reconstructed here.
        final int frameIdx;
        if (state.deathTime > 0) {
            // S_PLAY_DIE1..7, frames H to N: Minecraft's 20 death ticks are spread across
            // the seven frames, and the final corpse frame holds until respawn.
            frameIdx = 'h' - 'a' + Math.min(6, (int) (state.deathTime * 7.0f / 20.0f));
        } else if (state.attackTime > 0.01f) {
            frameIdx = 'e' - 'a';
        } else if (state.hasRedOverlay) {
            frameIdx = 'g' - 'a';
        } else if (state.walkAnimationSpeed > 0.05f) {
            frameIdx = (int) ((state.ageInTicks * 1.75f) / 4.0f) % 4; // ABCD @ 4 tics
        } else {
            frameIdx = 0;
        }

        // Eight-rotation view pick, in map-space degrees. The render state splits rotation
        // in two, an absolute body angle and a head yaw relative to it, so the look direction
        // is their sum. A sprite has no separate head and must face that sum: the body angle
        // alone lags the look direction, and the head yaw alone never moves at all.
        final double mAngle = -(state.bodyRot + state.yRot) - 90.0;
        final double camDx = LatteWorld.worldToDoomX(camera.pos.x);
        final double camDy = LatteWorld.worldToDoomY(camera.pos.z);
        final double meDx = LatteWorld.worldToDoomX(state.x);
        final double meDy = LatteWorld.worldToDoomY(state.z);
        final double vdx = meDx - camDx, vdy = meDy - camDy;
        final double dist = Math.hypot(vdx, vdy);
        if (dist < 1.0e-3) {
            return;
        }
        final double viewerToMe = Math.toDegrees(Math.atan2(vdy, vdx));
        final int rot = (int) Math.floor(
            (((viewerToMe - mAngle + 202.5) % 360.0) + 360.0) % 360.0 / 45.0) & 7;
        final SpriteSet.View view = sprites.view("PLAY", frameIdx, rot);
        if (view == null) {
            return;
        }
        final String key = "sprites/" + view.lump();
        final int[] size = DoomRuntimeTextures.textureSize(key);
        final int[] ofs = DoomRuntimeTextures.spriteOffset(key);
        if (size == null || ofs == null) {
            return;
        }
        final int w = size[0], h = size[1];
        final int leftOfs = ofs[0];

        // Billboard axes in world space, emitted in the entity-local pose (translation-only)
        final double rxD = vdy / dist, ryD = -vdx / dist; // viewer's right, doom space
        final float rx = (float) (rxD / LatteWorld.UNITS);
        final float rz = (float) (-ryD / LatteWorld.UNITS);
        final double l0 = -leftOfs * HSQUEEZE, l1 = (w - leftOfs) * HSQUEEZE;
        final float x0 = (float) (rx * l0), z0 = (float) (rz * l0);
        final float x1 = (float) (rx * l1), z1 = (float) (rz * l1);
        double top = ofs[1], bottom = ofs[1] - h;
        if (bottom < 0) {
            top -= bottom; // keep the feet on the ground
            bottom = 0;
        }
        final float yT = (float) (top / LatteWorld.UNITS);
        final float yB = (float) (bottom / LatteWorld.UNITS);
        final float u0 = view.flip() ? 1 : 0, u1 = view.flip() ? 0 : 1;

        // Sector light applies only inside a level. Outside one, the same projection would
        // sample whatever room the position happens to map onto, so daylight is used.
        final int c;
        if (LatteWorld.insideLevel(state.x, state.y, state.z)) {
            final int sec = LatteWorld.map() != null ? LatteWorld.map().sectorAt(meDx, meDy) : -1;
            c = LatteMesh.shadeByte(LatteWorld.lightOf(sec));
        } else {
            c = 255;
        }

        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(idOf(key)),
            (p, vc) -> {
                vertex(vc, p, x0, yT, z0, u0, 0, c);
                vertex(vc, p, x1, yT, z1, u1, 0, c);
                vertex(vc, p, x1, yB, z1, u1, 1, c);
                vertex(vc, p, x0, yB, z0, u0, 1, c);
            });
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
        return IDS.computeIfAbsent(key,
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private MarineBody() {}
}
