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
 * The transformed player's BODY: the WAD's own PLAY marine billboard in place of the
 * Steve model — what other players and the F5 camera see. Frame logic mirrors the
 * S_PLAY_* state timings (stand A, run ABCD every 4 tics, attack E, pain G); rotation is
 * DOOM's 8-view pick against the camera; feet anchor at the entity origin with the
 * smart-clip lift. Sprites come from the runtime compositor — nothing shipped.
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
        // S_PLAY frame from the render state (engine can't animate the mirrored mobj)
        final int frameIdx;
        if (state.deathTime > 0) {
            // S_PLAY_DIE1..7: frames H..N — the marine crumples and stays down. MC's 20
            // death ticks spread across the 7 frames; N (the corpse) holds at the end.
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

        // DOOM's 8-rotation pick, in doom-space degrees. The render state splits rotation:
        // bodyRot is absolute, yRot is the HEAD's yaw RELATIVE to it (what the head model
        // needs) — absolute look direction is their sum. A DOOM marine has no neck: the
        // whole sprite faces where the player looks, the instant they look. (Using yRot
        // alone froze remote marines' facing; bodyRot alone lagged the head by ~50°.)
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

        // billboard axes in world space, emitted in the entity-local pose (translation-only)
        final double rxD = vdy / dist, ryD = -vdx / dist; // viewer's right, doom space
        final float rx = (float) (rxD / LatteWorld.UNITS);
        final float rz = (float) (-ryD / LatteWorld.UNITS);
        final double l0 = -leftOfs * HSQUEEZE, l1 = (w - leftOfs) * HSQUEEZE;
        final float x0 = (float) (rx * l0), z0 = (float) (rz * l0);
        final float x1 = (float) (rx * l1), z1 = (float) (rz * l1);
        double top = ofs[1], bottom = ofs[1] - h;
        if (bottom < 0) {
            top -= bottom; // feet on the ground (smart clip)
            bottom = 0;
        }
        final float yT = (float) (top / LatteWorld.UNITS);
        final float yB = (float) (bottom / LatteWorld.UNITS);
        final float u0 = view.flip() ? 1 : 0, u1 = view.flip() ? 0 : 1;

        // sector light only applies INSIDE the level — an overworld marine was picking
        // up whatever dark room its position happened to project into. Daylight outside.
        final int c;
        if (LatteWorld.insideLevel(state.x, state.y, state.z)) {
            final int sec = LatteWorld.map() != null ? LatteWorld.map().sectorAt(meDx, meDy) : -1;
            c = LatteMesh.doomShade(
                Math.min(255, LatteWorld.lightOf(sec) + LatteWorld.extraLightBytes()),
                Math.hypot(vdx, vdy), false);
        } else {
            c = 255;
        }

        // same pass as every other doom sprite: texture x doomShade, no lightmap
        final var mt = SpriteShadePipeline.type(idOf(key));
        collector.submitCustomGeometry(pose,
            mt != null ? mt : RenderTypes.entityCutout(idOf(key)),
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
