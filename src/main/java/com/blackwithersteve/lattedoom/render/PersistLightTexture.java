package com.blackwithersteve.lattedoom.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The per-sector light feed for the persistent lit pass: an Nx2 texture whose
 * row 0 carries each sector's light (boost folded in) and whose row 1 pixel 0
 * carries the gamma exponent byte. Updated by diff on the render thread before
 * the persistent draw — a light change costs one tiny upload, never a rebake.
 */
final class PersistLightTexture {

    private static DynamicTexture tex;
    private static int width;
    private static int[] uploaded;
    private static int uploadedGamma = -1;

    /** Refresh from the snapshot's live light; (re)creates on size change. */
    static void update(short[] light) {
        if (light == null || light.length == 0) {
            return;
        }
        final int w = Math.min(light.length, 16384);
        if (tex == null || width < w) {
            dispose();
            width = w;
            tex = new DynamicTexture(() -> "latte:sector-light", width, 2, false);
            uploaded = null;
        }
        final int boost = LatteMesh.lightBoost()
            + LatteWorld.extraLightBytes(); // gun flash / DEH flashlight
        final int gamma = LatteMesh.gammaAlpha();
        boolean dirty = uploaded == null || uploadedGamma != gamma;
        final NativeImage img = tex.getPixels();
        if (img == null) {
            return;
        }
        if (uploaded == null) {
            uploaded = new int[width];
            java.util.Arrays.fill(uploaded, -1);
        }
        for (int i = 0; i < w; i++) {
            final int v = Math.max(0, Math.min(255, light[i] + boost));
            if (uploaded[i] != v) {
                // the value in EVERY color byte: channel order cannot matter
                img.setPixel(i, 0, 0xFF000000 | (v << 16) | (v << 8) | v);
                uploaded[i] = v;
                dirty = true;
            }
        }
        if (uploadedGamma != gamma) {
            img.setPixel(0, 1, 0xFF000000 | (gamma << 16) | (gamma << 8) | gamma);
            uploadedGamma = gamma;
        }
        if (dirty) {
            tex.upload();
        }
    }

    static com.mojang.blaze3d.textures.GpuTextureView view() {
        return tex != null ? tex.getTextureView() : null;
    }

    static void dispose() {
        if (tex != null) {
            tex.close();
            tex = null;
        }
        uploaded = null;
        uploadedGamma = -1;
        width = 0;
    }

    private PersistLightTexture() {
    }
}
