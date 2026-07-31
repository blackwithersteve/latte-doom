package com.blackwithersteve.lattedoom;

import com.blackwithersteve.lattedoom.render.DoomSfx;
import com.blackwithersteve.lattedoom.render.LatteHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * DOOM's own Sound Volume menu, rebuilt from the WAD's own art — the M_SVOL title, the
 * M_SFXVOL / M_MUSVOL labels, the M_THERM* thermometers and the blinking M_SKULL cursor —
 * driving Latte Doom's DEDICATED audio sliders (config.doomSfxVolume / doomMusicVolume),
 * NOT Minecraft's. Up/Down pick a slider, Left/Right move it in DOOM's 16 discrete steps,
 * ESC / ` / Enter close. Each nudge ticks the authentic sfx_stnmov blip, navigation the
 * sfx_swtchn switch. If the WAD lacks the menu lumps (or none is loaded yet) it degrades to
 * a plain-text slider so the control is never dead.
 *
 * Geometry is vanilla m_menu.c: SoundDef at (80,64), LINEHEIGHT 16, title at (60,38),
 * skull at (x-32, y-5 + item*LINEHEIGHT), knob at (x+8)+dot*8.
 */
public final class LatteVolumeScreen extends Screen {

    private static final int SND_STNMOV = data.sounds.sfxenum_t.sfx_stnmov.ordinal();
    private static final int SND_SWTCHN = data.sounds.sfxenum_t.sfx_swtchn.ordinal();
    private static final int SND_SWTCHX = data.sounds.sfxenum_t.sfx_swtchx.ordinal();

    private static final int MENU_X = 80, MENU_Y = 64, LINEHEIGHT = 16;

    private final LatteDoomConfig config;
    private int sel; // 0 = SFX, 1 = Music

    public LatteVolumeScreen(LatteDoomConfig config) {
        super(Component.literal("Latte Doom — Sound Volume"));
        this.config = config;
    }

    /** The DOOM engine runs on its own thread — don't pause the world; music changes are audible live. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // we close on ESC ourselves, with the exit blip
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xC0000000); // dim the world behind, DOOM-menu style
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

        // skull cursor: itemOn is 0 (sfx) or 2 (music); blink ~every 8 tics (≈233ms)
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

    /** Faithful m_menu.c M_DrawThermo. The pen advances a FIXED 8 canvas px per piece
     * while every patch draws at its own TRUE size with its own offsets applied — in
     * doom.wad M_THERML is 6px wide at leftoffset -2, M_THERMM is 9px wide (so the 8px
     * steps make consecutive tiles OVERLAP one column — that overlap, not luck, is why
     * vanilla has no seams), and the M_THERMO knob is 5x11 at (-2,-1), seating it INSIDE
     * the trough. The old forced-8px-unit drawing stretched all of that ("black spot",
     * knob beside the trough) and would mangle any PWAD's restyled art; this renders
     * whatever geometry the loaded WAD's own lumps declare. A coverage floor per middle
     * tile absorbs screen-space rounding so scaled tiles can't open 1px background seams. */
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

    /** One thermometer piece, V_DrawPatchDirect semantics (offsets subtracted, true
     * size). tile = middle piece: widen to at least the next 8px pen stop so rounding
     * can never leave a background-colored seam between tiles. */
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
        // projected right edge minus projected left edge: both ends rounded the same
        // way, so abutting patches abut at every gui scale (windowed seams fix)
        int w = Math.max(1, screenX(guiW, xs, cx + size[0]) - px);
        if (tile) {
            w = Math.max(w, screenX(guiW, xs, cx + 8) - px);
        }
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "lattedoom", "textures/doom/gfx/" + lump + ".png"),
            px, py, 0.0f, 0.0f, w, Math.max(1, (int) Math.round(size[1] * ys)),
            size[0], size[1], size[0], size[1]);
    }

    /** DOOM's 0-15 thermometer position for the given channel. */
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

    /** Screen -> canvas coordinates, the inverse of the 4:3 patch mapping. */
    private double[] canvasAt(double mx, double my) {
        final double xs = this.height * (4.0 / 3.0) / 320.0;
        return new double[]{160.0 + (mx - this.width / 2.0) / xs,
            my / (this.height / 200.0)};
    }

    /** 0 = the SFX rows, 1 = the MUSIC rows, -1 = neither. A row is its label line
     * plus the thermometer line under it. */
    private int rowAt(double cy) {
        if (cy >= MENU_Y - 6 && cy < MENU_Y + LINEHEIGHT * 2 - 4) {
            return 0;
        }
        if (cy >= MENU_Y + LINEHEIGHT * 2 - 4 && cy < MENU_Y + LINEHEIGHT * 4 + 4) {
            return 1;
        }
        return -1;
    }

    /** A press or drag on a thermometer: the knob goes where the mouse points
     * (vanilla knob geometry — dot steps 8 canvas px starting at x+8). */
    private boolean thermTo(double cx2, double cy) {
        final int row;
        if (cy >= MENU_Y + LINEHEIGHT - 6 && cy <= MENU_Y + LINEHEIGHT + 12) {
            row = 0;
        } else if (cy >= MENU_Y + LINEHEIGHT * 3 - 6 && cy <= MENU_Y + LINEHEIGHT * 3 + 12) {
            row = 1;
        } else {
            return false;
        }
        if (cx2 < MENU_X - 4 || cx2 > MENU_X + 140) {
            return false;
        }
        sel = row;
        final int nd = (int) Math.max(0, Math.min(15,
            Math.round((cx2 - (MENU_X + 8)) / 8.0)));
        if (nd != dot(row == 1)) {
            LatteDoomClient.setDoomVolume(row == 1, nd / 15f);
            DoomSfx.play(SND_STNMOV, false, 0, 0);
        }
        return true;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        final int row = rowAt(canvasAt(mx, my)[1]);
        if (row >= 0 && row != sel) {
            sel = row; // the skull follows the mouse
            DoomSfx.play(SND_SWTCHN, false, 0, 0);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,
                                boolean doubleClick) {
        if (event.button() == 1) { // right button leaves, like ESC
            DoomSfx.play(SND_SWTCHX, false, 0, 0);
            onClose();
            return true;
        }
        final double[] c = canvasAt(event.x(), event.y());
        thermTo(c[0], c[1]);
        return true;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,
                                double dragX, double dragY) {
        final double[] c = canvasAt(event.x(), event.y());
        thermTo(c[0], c[1]);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        final int row = rowAt(canvasAt(mx, my)[1]);
        if (row >= 0) {
            sel = row;
        }
        adjust(scrollY > 0 ? 1 : -1);
        return true;
    }
}
