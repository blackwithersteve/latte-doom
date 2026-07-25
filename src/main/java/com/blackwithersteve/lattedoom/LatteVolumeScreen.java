package com.blackwithersteve.lattedoom;

import com.blackwithersteve.lattedoom.render.DoomSfx;
import com.blackwithersteve.lattedoom.render.LatteHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Sound volume menu, drawn from the WAD's {@code M_SVOL} and {@code M_THERM*} art. Drives
 * the mod's own audio levels, which are separate from Minecraft's sliders, in the original's
 * 16 discrete steps. Geometry follows {@code m_menu.c}: definition at (80, 64), line height
 * 16, title at (60, 38), knob at (x+8) + position * 8. Falls back to text sliders without
 * the menu lumps.
 */
public final class LatteVolumeScreen extends Screen {

    private static final int SND_STNMOV = data.sounds.sfxenum_t.sfx_stnmov.ordinal();
    private static final int SND_SWTCHN = data.sounds.sfxenum_t.sfx_swtchn.ordinal();
    private static final int SND_SWTCHX = data.sounds.sfxenum_t.sfx_swtchx.ordinal();

    private static final int MENU_X = 80, MENU_Y = 64, LINEHEIGHT = 16;

    private final LatteDoomConfig config;
    private int sel; // 0 = SFX, 1 = Music

    public LatteVolumeScreen(LatteDoomConfig config) {
        super(Component.literal("Latte Doom: Sound Volume"));
        this.config = config;
    }

    /** The engine runs on its own thread, so the world is not paused here and volume
     * changes are audible while the screen is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc is handled here, so the exit sound plays
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xC0000000); // dim the world behind the menu
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        final int guiW = this.width, guiH = this.height;

        if (config == null || !LatteHud.hasGfx("m_therml") || !LatteHud.hasGfx("m_svol")) {
            drawTextFallback(g);
            return;
        }
        LatteHud.drawGfx(g, "m_svol", 60, 38, guiW, guiH);                        // "Sound Volume"
        LatteHud.drawGfx(g, "m_sfxvol", MENU_X, MENU_Y, guiW, guiH);              // "Sfx Volume"
        LatteHud.drawGfx(g, "m_musvol", MENU_X, MENU_Y + LINEHEIGHT * 2, guiW, guiH); // "Music Volume"
        drawThermo(g, MENU_X, MENU_Y + LINEHEIGHT, dot(false), guiW, guiH);       // sfx thermometer
        drawThermo(g, MENU_X, MENU_Y + LINEHEIGHT * 3, dot(true), guiW, guiH);    // music thermometer

        // Skull cursor: the item index is 0 for effects or 2 for music; it blinks every
        // 8 tics, about 233 ms.
        final int itemOn = sel == 0 ? 0 : 2;
        final boolean firstSkull = (System.currentTimeMillis() / 233L) % 2L == 0L;
        LatteHud.drawGfx(g, firstSkull ? "m_skull1" : "m_skull2",
            MENU_X - 32, MENU_Y - 5 + itemOn * LINEHEIGHT, guiW, guiH);
    }

    private void drawTextFallback(GuiGraphicsExtractor g) {
        final int cx = this.width / 2, cy = this.height / 2;
        g.centeredText(this.font, "SOUND VOLUME", cx, cy - 34, 0xFFFFFFFF);
        g.centeredText(this.font, (sel == 0 ? "▶ " : "   ") + "SFX    " + pct(false) + "%",
            cx, cy - 10, sel == 0 ? 0xFFFFFF55 : 0xFFAAAAAA);
        g.centeredText(this.font, (sel == 1 ? "▶ " : "   ") + "MUSIC  " + pct(true) + "%",
            cx, cy + 6, sel == 1 ? 0xFFFFFF55 : 0xFFAAAAAA);
        g.centeredText(this.font, "arrows adjust   -   ESC closes", cx, cy + 30, 0xFF888888);
    }

    /** Draws a thermometer following {@code m_menu.c}'s {@code M_DrawThermo}. The drawing
     * position advances a fixed 8 canvas pixels per piece while each patch is drawn at its
     * own true size with its own offsets applied. In {@code doom.wad} the left cap is 6
     * pixels wide at a left offset of -2, the middle tile is 9 pixels wide, so the 8-pixel
     * step makes consecutive tiles overlap by one column, which is why the original shows
     * no seams, and the knob is 5 by 11 at offsets (-2, -1), which seats it inside the
     * trough. Forcing every piece into an 8-pixel box instead stretches the art, misplaces
     * the knob and would distort any WAD that restyles these lumps. Each middle tile is
     * additionally widened to the next drawing position so screen-space rounding cannot
     * open one-pixel gaps between tiles. */
    private void drawThermo(GuiGraphicsExtractor g, int x, int y, int thermDot, int guiW, int guiH) {
        final double xs = guiH * (4.0 / 3.0) / 320.0;
        final double ys = guiH / 200.0;
        int xx = x;
        thermPatch(g, "m_therml", xx, y, xs, ys, guiW, false);
        xx += 8;
        for (int i = 0; i < 16; i++) {
            thermPatch(g, "m_thermm", xx, y, xs, ys, guiW, true);
            xx += 8;
        }
        thermPatch(g, "m_thermr", xx, y, xs, ys, guiW, false);
        thermPatch(g, "m_thermo", x + 8 + thermDot * 8, y, xs, ys, guiW, false);
    }

    private static int screenX(int guiW, double xs, double canvasX) {
        return (int) Math.round(guiW / 2.0 + (canvasX - 160.0) * xs);
    }

    /** Draws one thermometer piece with {@code V_DrawPatchDirect} semantics: the patch's
     * own offsets are subtracted and its true size is used. For a middle tile the width is
     * extended to at least the next 8-pixel position so rounding cannot leave a
     * background-coloured seam between tiles. */
    private static void thermPatch(GuiGraphicsExtractor g, String lump, double cx, double cy,
                                   double xs, double ys, int guiW, boolean tile) {
        final String key = "gfx/" + lump;
        final int[] size = com.blackwithersteve.lattedoom.render.DoomRuntimeTextures.textureSize(key);
        if (size == null) {
            return;
        }
        final int[] ofs = com.blackwithersteve.lattedoom.render.DoomRuntimeTextures.spriteOffset(key);
        if (ofs != null) {
            cx -= ofs[0];
            cy -= ofs[1];
        }
        final int px = screenX(guiW, xs, cx);
        final int py = (int) Math.round(cy * ys);
        int w = Math.max(1, (int) Math.round(size[0] * xs));
        if (tile) {
            w = Math.max(w, screenX(guiW, xs, cx + 8) - px);
        }
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "lattedoom", "textures/doom/gfx/" + lump + ".png"),
            px, py, 0.0f, 0.0f, w, Math.max(1, (int) Math.round(size[1] * ys)),
            size[0], size[1], size[0], size[1]);
    }

    /** The 0-15 thermometer position for the given channel. */
    private int dot(boolean music) {
        final float v = music ? config.doomMusicVolume : config.doomSfxVolume;
        return Math.max(0, Math.min(15, Math.round(v * 15f)));
    }

    private int pct(boolean music) {
        return Math.round((music ? config.doomMusicVolume : config.doomSfxVolume) * 100f);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> move(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> move(1);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> adjust(-1);
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> adjust(1);
            case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_GRAVE_ACCENT,
                 GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                DoomSfx.play(SND_SWTCHX, false, 0, 0);
                onClose();
            }
            default -> { }
        }
        return true; // the menu owns the keyboard while open
    }

    private void move(int d) {
        final int ns = Math.max(0, Math.min(1, sel + d));
        if (ns != sel) {
            sel = ns;
            DoomSfx.play(SND_SWTCHN, false, 0, 0);
        }
    }

    private void adjust(int delta) {
        final boolean music = sel == 1;
        final int cur = dot(music);
        final int nd = Math.max(0, Math.min(15, cur + delta));
        if (nd != cur) {
            LatteDoomClient.setDoomVolume(music, nd / 15f);
            DoomSfx.play(SND_STNMOV, false, 0, 0);
        }
    }
}
