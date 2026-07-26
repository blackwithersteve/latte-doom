package com.blackwithersteve.lattedoom.render;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the view weapon, its muzzle flash and the status bar as a HUD overlay. The engine's
 * own weapon-sprite state machine decides which sprite and frame are shown and where they
 * sit; this class draws that exact lump on Minecraft's screen.
 *
 * <p>Weapon sprites live in the engine's 320x200 canvas, where the left edge is
 * {@code sx - leftOffset} and the top is {@code sy - topOffset}, per {@code R_DrawPSprite}.
 * That canvas is mapped to the screen at 4:3, centred horizontally and anchored to the
 * bottom edge.
 */
public final class LatteHud {

    private static final Map<String, Identifier> IDS = new HashMap<>();

    /**
     * Whether the DOOM interface can be drawn. Transforming is instant but the engine boots
     * on its own thread, and its first snapshot can be seconds away, so this stays false for
     * a while after the player transforms. Anything that hides Minecraft's own interface has
     * to consult this rather than the form alone, or the player is left with neither
     * interface for as long as the boot takes.
     */
    public static boolean ready() {
        return LatteWorld.marineForm()
            && LatteWorld.suitSnap() != null
            && LatteWorld.sprites() != null;
    }

    public static void extract(GuiGraphicsExtractor g) {
        if (!LatteWorld.marineForm()) {
            return; // the view weapon belongs to a transformed player only
        }
        // Deliberately not restricted to being inside a level: a transformed player keeps
        // the weapon and status bar anywhere, since the engine's stats exist for as long as
        // it has a level loaded. These stats always come from this client's own engine,
        // even when the world geometry is being fed by another player's engine.
        final WorldSnapshot snap = LatteWorld.suitSnap();
        final SpriteSet sprites = LatteWorld.sprites();
        if (snap == null || sprites == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final int guiW = mc.getWindow().getGuiScaledWidth();
        final int guiH = mc.getWindow().getGuiScaledHeight();

        // The weapon bob is computed here rather than by the engine, because mirroring the
        // player's position zeroes the engine-side momentum the bob would be derived from.
        // The engine's own formula is used, min(16, v squared / 4), driven by its clock.
        double bobX = 0, bobY = 0;
        if (mc.player != null) {
            final double v = mc.player.getDeltaMovement().horizontalDistance()
                * LatteWorld.UNITS / 1.75; // blocks/tick -> u/tic
            final double bob = Math.min(16.0, v * v / 4.0);
            // A continuous tic clock rather than the integer tic: quantising the sway to
            // 35 Hz makes the bob visibly step on a high-refresh display.
            final double a = (LatteWorld.ticTime() * 128.0 % 8192.0) / 8192.0 * 2.0 * Math.PI;
            bobX = bob * Math.cos(a);
            bobY = bob * Math.abs(Math.sin(a));
        }
        // The weapon is lit by the sector the player stands in. Full-bright frames, such
        // as the muzzle flash, override this, as they do in R_DrawPSprite.
        int gunShade = 255;
        if (mc.player != null) {
            final int lc = LatteWorld.levelLightCoords(
                mc.player.getX(), mc.player.getY(), mc.player.getZ());
            if (lc >= 0) {
                gunShade = LatteMesh.shadeByte((lc >> 4) * 255 / 15);
            }
        }
        // The view weapon is hidden while the player is dead, as the engine lowers it out
        // of view on death; the status bar remains. The engine cannot drive this itself,
        // because the mirrored player object never dies.
        final boolean dead = mc.player != null && mc.player.isDeadOrDying();
        // The automap replaces the view, including the weapon, with the status bar drawn
        // on top; this is the original's layering.
        final DoomMap amMap = LatteWorld.map();
        final boolean automap = DoomAutomap.active() && amMap != null && mc.player != null;
        if (automap) {
            DoomAutomap.draw(g, amMap, snap,
                LatteWorld.worldToDoomX(mc.player.getX()),
                LatteWorld.worldToDoomY(mc.player.getZ()),
                -mc.player.getYRot() - 90.0, guiW, guiH);
        } else if (!dead) {
            draw(g, sprites, snap.wSprite, snap.wFrame, snap.wX + bobX, snap.wY + bobY,
                guiW, guiH, gunShade);
            draw(g, sprites, snap.fSprite, snap.fFrame, snap.fX + bobX, snap.fY + bobY,
                guiW, guiH, gunShade);
        }
        statusBar(g, snap, mc, guiW, guiH);
        flashes(g, snap, guiW, guiH);
    }

    // ------------------------------------------------------------------ status bar

    private static int lastWeaponMask = -1;
    private static int evilUntilTic;

    /** Classic st_stuff layout on the 320x200 canvas (bar at y=168), the WAD's own art. */
    private static void statusBar(GuiGraphicsExtractor g, WorldSnapshot snap,
                                  Minecraft mc, int guiW, int guiH) {
        patch(g, "stbar", 0, 168, guiW, guiH);
        patch(g, "starms", 104, 168, guiW, guiH);

        // Ammunition for the ready weapon, blank for the fist and chainsaw as in the original.
        if (snap.readyAmmoType >= 0 && snap.readyAmmoType < 4 && snap.ammo != null) {
            numRight(g, "sttnum", snap.ammo[snap.readyAmmoType], 44, 171, guiW, guiH);
        }
        final int health = Math.min(200, (int) Math.ceil(mc.player.getHealth() * 5.0f));
        numRight(g, "sttnum", health, 90, 171, guiW, guiH);
        patch(g, "sttprcnt", 90, 171, guiW, guiH);
        numRight(g, "sttnum", snap.armor, 221, 171, guiW, guiH);
        patch(g, "sttprcnt", 221, 171, guiW, guiH);

        // The arms panel: digits 2 to 7 light up for owned weapons, pistol through BFG.
        if (snap.weaponOwned != null) {
            for (int i = 0; i < 6; i++) {
                final boolean owned = i + 1 < snap.weaponOwned.length && snap.weaponOwned[i + 1];
                patch(g, (owned ? "stysnum" : "stgnum") + (i + 2),
                    111 + (i % 3) * 12, 172 + (i / 3) * 10, guiW, guiH);
            }
        }

        // Keys: key cards 0 to 2 and skull keys 3 to 5 share the three rows.
        if (snap.cards != null && snap.cards.length >= 6) {
            for (int row = 0; row < 3; row++) {
                if (snap.cards[row + 3]) {
                    patch(g, "stkeys" + (row + 3), 239, 171 + 10 * row, guiW, guiH);
                } else if (snap.cards[row]) {
                    patch(g, "stkeys" + row, 239, 171 + 10 * row, guiW, guiH);
                }
            }
        }

        // Ammunition table, current over maximum: the bullet, shell, rocket and cell rows
        // correspond to pools 0, 1, 3 and 2.
        if (snap.ammo != null && snap.maxAmmo != null) {
            final int[] rows = {0, 1, 3, 2};
            for (int r = 0; r < 4; r++) {
                numRight(g, "stysnum", snap.ammo[rows[r]], 288, 173 + 6 * r, guiW, guiH);
                numRight(g, "stysnum", snap.maxAmmo[rows[r]], 314, 173 + 6 * r, guiW, guiH);
            }
        }

        // The face, following a simplified st_stuff: pain level by health, a grimace while
        // the damage counter runs, a grin for about two seconds after picking up a new
        // weapon, and the dead face at zero health.
        if (snap.weaponOwned != null) {
            int mask = 0;
            for (int i = 0; i < snap.weaponOwned.length; i++) {
                if (snap.weaponOwned[i]) {
                    mask |= 1 << i;
                }
            }
            if (lastWeaponMask >= 0 && (mask & ~lastWeaponMask) != 0) {
                evilUntilTic = snap.tic + 70;
            }
            lastWeaponMask = mask;
        }
        final int pain = Math.min(4, Math.max(0, (100 - Math.min(100, health)) * 5 / 100));
        final String face;
        if (mc.player.isDeadOrDying() || health <= 0) {
            face = "stfdead0";
        } else if (snap.damageCount > 0) {
            face = "stfkill" + pain;
        } else if (snap.tic < evilUntilTic) {
            face = "stfevl" + pain;
        } else {
            face = "stfst" + pain + ((snap.tic / 16) % 3);
        }
        patch(g, face, 143, 168, guiW, guiH);
    }

    /** Vanilla's palette shifts, as overlays: damage reds, pickup golds, over everything. */
    private static void flashes(GuiGraphicsExtractor g, WorldSnapshot snap, int guiW, int guiH) {
        if (snap.damageCount > 0) {
            final int a = (int) (Math.min(1.0, snap.damageCount / 32.0) * 0.55 * 255);
            g.fill(0, 0, guiW, guiH, (a << 24) | 0xFF0000);
        } else if (snap.bonusCount > 0) {
            final int a = (int) (Math.min(1.0, snap.bonusCount / 24.0) * 0.35 * 255);
            g.fill(0, 0, guiW, guiH, (a << 24) | 0xD7BA45);
        }
    }

    /** Public draw of a registered DOOM menu/status patch ("gfx/&lt;lump&gt;") at 320x200 canvas
     * coordinates with V_DrawPatch semantics, centred at 4:3, for overlays drawn in the
     * engine's own style. Does nothing if the WAD does not provide the lump. */
    public static void drawGfx(GuiGraphicsExtractor g, String lump,
                               double canvasX, double canvasY, int guiW, int guiH) {
        patch(g, lump, canvasX, canvasY, guiW, guiH);
    }

    /** Whether a menu or status patch is registered, that is present in the loaded WAD. */
    public static boolean hasGfx(String lump) {
        return DoomRuntimeTextures.textureSize("gfx/" + lump) != null;
    }

    /** Blit a status graphic at 320x200 canvas coordinates with V_DrawPatch semantics. The
     * patch's own left and top offsets are subtracted, which matters for lumps such as the
     * status-bar faces, whose offsets are large. */
    private static void patch(GuiGraphicsExtractor g, String lump,
                              double canvasX, double canvasY, int guiW, int guiH) {
        final String key = "gfx/" + lump;
        final int[] size = DoomRuntimeTextures.textureSize(key);
        if (size == null) {
            return;
        }
        final int[] ofs = DoomRuntimeTextures.spriteOffset(key);
        if (ofs != null) {
            canvasX -= ofs[0];
            canvasY -= ofs[1];
        }
        final double xs = guiH * (4.0 / 3.0) / 320.0;
        final double ys = guiH / 200.0;
        final int x = (int) Math.round(guiW / 2.0 + (canvasX - 160.0) * xs);
        final int y = (int) Math.round(canvasY * ys);
        g.blit(RenderPipelines.GUI_TEXTURED, idOf(key), x, y, 0.0f, 0.0f,
            (int) Math.round(size[0] * xs), (int) Math.round(size[1] * ys),
            size[0], size[1], size[0], size[1]);
    }

    /** Right-aligned number in the given digit set, ending at canvas endX. */
    private static void numRight(GuiGraphicsExtractor g, String digitPrefix, int value,
                                 double endX, double y, int guiW, int guiH) {
        int v = Math.max(0, value);
        double x = endX;
        do {
            final String lump = digitPrefix + (v % 10);
            final int[] size = DoomRuntimeTextures.textureSize("gfx/" + lump);
            if (size == null) {
                return;
            }
            x -= size[0];
            patch(g, lump, x, y, guiW, guiH);
            v /= 10;
        } while (v > 0);
    }

    private static void draw(GuiGraphicsExtractor g, SpriteSet sprites,
                             int ord, int frame, double sxPx, double syPx,
                             int guiW, int guiH, int shade) {
        if (ord <= 0) {
            return; // -1 = none; 0 = SPR_TROO, the sprite slot S_NULL carries
        }
        final String name = LatteSprites.spriteName(ord);
        if (name == null) {
            return;
        }
        final SpriteSet.View view = sprites.view(name, frame & 0x7FFF, 0);
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
        final double canvasX = sxPx - ofs[0];
        // R_DrawPSprite with the status bar visible: the 3D view is 168 rows tall, so the
        // weapon-sprite canvas centres on row 84 rather than 100, placing sprites 16.5
        // pixels higher than a naive 200-row placement would, which would otherwise
        // leave the weapon partly hidden behind the status bar.
        final double canvasY = syPx - ofs[1] - 16.5;
        final double xs = guiH * (4.0 / 3.0) / 320.0; // 4:3 pixel aspect (1:1.2 CRT pixels)
        final double ys = guiH / 200.0;
        final int x = (int) Math.round(guiW / 2.0 + (canvasX - 160.0) * xs);
        final int y = (int) Math.round(canvasY * ys);
        final int dw = (int) Math.round(w * xs);
        final int dh = (int) Math.round(h * ys);
        final int s = (frame & 0x8000) != 0 ? 255 : shade; // FF_FULLBRIGHT wins
        final int tint = 0xFF000000 | (s << 16) | (s << 8) | s;
        g.blit(RenderPipelines.GUI_TEXTURED, idOf(key), x, y, 0.0f, 0.0f, dw, dh, w, h, w, h, tint);
    }

    private static Identifier idOf(String key) {
        // Route through the sanitizer: bracket-rotation sprite lumps (BBRN[1) must map to
        // the same safe name DoomRuntimeTextures registered
        return IDS.computeIfAbsent(DoomRuntimeTextures.safe(key),
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private LatteHud() {}
}
