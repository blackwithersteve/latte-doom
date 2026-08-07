package com.blackwithersteve.lattedoom.menu;

import com.blackwithersteve.lattedoom.render.DoomRuntimeTextures;
import com.blackwithersteve.lattedoom.render.LatteHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Canvas geometry for the menu, and the shared pieces pages draw with.
 *
 * Menu art is authored against DOOM's 320x200 canvas and projected 4:3. This is the only
 * place that converts between canvas and screen, so drawing and mouse hit-testing cannot
 * disagree.
 *
 * The thermometer follows M_DrawThermo: the pen advances a fixed 8 canvas px per piece
 * while each patch draws at its own size with its own offsets applied. In doom.wad
 * M_THERML is 6px wide at leftoffset -2 and M_THERMM is 9px, so consecutive tiles overlap
 * by a column. Drawing the pieces at a forced 8px width removes that overlap and opens
 * seams, and mangles a PWAD's restyled art.
 */
public final class MenuGeom {

    public static final int CANVAS_W = 320, CANVAS_H = 200;

    /** m_menu.c LINEHEIGHT: one menu row. */
    public static final int LINEHEIGHT = 16;
    /** A row carrying a thermometer on the line beneath it, as vanilla lays them out. */
    public static final int SLIDER_HEIGHT = LINEHEIGHT * 2;

    /** The skull sits one item-width left of the column and 5px above the row's top. */
    public static final int SKULL_DX = -32, SKULL_DY = -5;

    public static double xScale(int guiH) {
        return guiH * (4.0 / 3.0) / CANVAS_W;
    }

    public static double yScale(int guiH) {
        return guiH / (double) CANVAS_H;
    }

    public static int screenX(int guiW, int guiH, double canvasX) {
        return (int) Math.round(guiW / 2.0 + (canvasX - 160.0) * xScale(guiH));
    }

    public static int screenY(int guiH, double canvasY) {
        return (int) Math.round(canvasY * yScale(guiH));
    }

    /** Screen point to canvas point: the inverse of the patch blitter's mapping. */
    public static double[] toCanvas(int guiW, int guiH, double mouseX, double mouseY) {
        return new double[]{
            160.0 + (mouseX - guiW / 2.0) / xScale(guiH),
            mouseY / yScale(guiH)};
    }

    /** The cursor alternates every 8 tics, as M_Ticker does. */
    public static boolean skullFirst() {
        return (System.currentTimeMillis() / 233L) % 2L == 0L;
    }

    public static void skull(GuiGraphicsExtractor g, double canvasX, double canvasY,
                             int guiW, int guiH) {
        LatteHud.drawGfx(g, skullFirst() ? "m_skull1" : "m_skull2",
            canvasX, canvasY, guiW, guiH);
    }
    /**
     * A slider in UZDoom's shape rather than DOOM's thermometer: a plain trough with a knob,
     * twelve cells long, the knob at the value's fraction of the way along.
     *
     * UZDoom draws it from ConFont glyphs (0x10 cap, 0x11 x10, 0x12 cap, 0x13 knob) at
     * 16*CleanXfac_1 per cell. That font ships inside gzdoom.pk3, so this draws the same
     * shape as geometry — which is also why it stays sharp at any scale, unlike the
     * M_THERM patches being resampled.
     *
     * Returns the x just past the trough, where the numeric readout goes.
     */
    public static int sliderPx(GuiGraphicsExtractor g, int px, int py, double fraction,
                               int fac, int screenW) {
        int cells = 12;
        final int cell = 8 * fac;
        // UZDoom's short form when the bar would reach the edge
        if (px + (cells + 4) * cell > screenW) {
            cells = 7;
        }
        final int w = cells * cell;
        final int h = Math.max(3, 5 * fac);
        final int border = Math.max(1, fac);
        // trough: a dark well with a lighter edge
        g.fill(px, py, px + w, py + h, 0xFF202020);
        g.fill(px, py, px + w, py + border, 0xFF585858);
        g.fill(px, py + h - border, px + w, py + h, 0xFF585858);
        g.fill(px, py, px + border, py + h, 0xFF585858);
        g.fill(px + w - border, py, px + w, py + h, 0xFF585858);
        // knob
        final double f = Math.max(0.0, Math.min(1.0, fraction));
        final int knobW = Math.max(3, 3 * fac);
        final int kx = px + border + (int) Math.round(f * (w - 2 * border - knobW));
        g.fill(kx, py - border, kx + knobW, py + h + border, 0xFFD8D8D8);
        return px + w + 4 * fac;
    }

    /**
     * The selected-row marker. UZDoom uses the glyph U+25C4; STCFN has no such character, so
     * the port draws it as geometry — a small right-pointing triangle.
     */
    public static void caret(GuiGraphicsExtractor g, int px, int py, int fac, int color) {
        final int h = 4 * fac;
        for (int i = 0; i < h; i++) {
            final int half = h - i;
            g.fill(px + i, py - half, px + i + 1, py + half, color);
        }
    }

    /** A scrollbar track and thumb in real pixels. */
    public static void scrollbarPx(GuiGraphicsExtractor g, int px, int py, int height,
                                   int scroll, int shown, int total, int fac) {
        final int w = Math.max(2, 3 * fac);
        g.fill(px, py, px + w, py + height, 0x50FFFFFF);
        final int thumb = Math.max(4 * fac, height * shown / Math.max(1, total));
        final int travel = total > shown
            ? (height - thumb) * scroll / (total - shown) : 0;
        g.fill(px, py + travel, px + w, py + travel + thumb, 0xC0FFFFFF);
    }

    /** A filled rectangle in canvas coordinates. */
    public static void bar(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2,
                           int argb, int guiW, int guiH) {
        g.fill(screenX(guiW, guiH, x1), screenY(guiH, y1),
            screenX(guiW, guiH, x2), screenY(guiH, y2), argb);
    }

    /** M_DrawLoad/M_DrawSave's slot trough: a left cap, 24 centre pieces and a right cap. */
    public static void slotTrough(GuiGraphicsExtractor g, double x, double y,
                                  int guiW, int guiH) {
        piece(g, "m_lsleft", x - 8, y, guiW, guiH, false);
        double pen = x;
        for (int i = 0; i < 24; i++) {
            piece(g, "m_lscntr", pen, y, guiW, guiH, true);
            pen += 8;
        }
        piece(g, "m_lsrght", pen, y, guiW, guiH, false);
    }

    /**
     * One piece with V_DrawPatchDirect semantics: offsets subtracted, true size. A middle
     * tile widens to at least the next 8px pen stop; both edges go through the same
     * rounding as the neighbour's start, since round(size * scale) drifts a pixel against
     * it at fractional gui scales and opens vertical seams in windowed mode.
     */
    private static void piece(GuiGraphicsExtractor g, String lump, double canvasX,
                              double canvasY, int guiW, int guiH, boolean tile) {
        final String key = "gfx/" + lump;
        final int[] size = DoomRuntimeTextures.textureSize(key);
        if (size == null) {
            return;
        }
        double cx = canvasX, cy = canvasY;
        final int[] offset = DoomRuntimeTextures.spriteOffset(key);
        if (offset != null) {
            cx -= offset[0];
            cy -= offset[1];
        }
        final int px = screenX(guiW, guiH, cx);
        final int py = screenY(guiH, cy);
        int w = Math.max(1, screenX(guiW, guiH, cx + size[0]) - px);
        if (tile) {
            w = Math.max(w, screenX(guiW, guiH, cx + 8) - px);
        }
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
            Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/gfx/" + lump + ".png"),
            px, py, 0.0f, 0.0f, w,
            Math.max(1, (int) Math.round(size[1] * yScale(guiH))),
            size[0], size[1], size[0], size[1]);
    }

    private MenuGeom() {
    }
}
