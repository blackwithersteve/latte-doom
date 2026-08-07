package com.blackwithersteve.lattedoom.menu;

/**
 * How a page's coordinates reach the screen.
 *
 * UZDoom has two coordinate spaces and each element picks the right one; that is why its UI
 * survives 21:9 and 4K. This is the same split.
 *
 * FIT43 is the virtual canvas: 320x200 mapped onto a centred, aspect-corrected box, keeping
 * DOOM's non-square pixels. The art pages use it. It is what latte-doom always did, plus the
 * letterbox branch it was missing.
 *
 * SETTINGS is UZDoom's "clean" space: real pixels at an INTEGER factor that deliberately does
 * not grow proportionally with the screen. Settings pages use it, because a fractional factor
 * resamples DOOM's pixel art unevenly and that is what made the menu look wrong at high
 * resolution.
 *
 * One instance is built per page per frame and is used by BOTH the drawing and the mouse
 * hit-testing, so the two cannot disagree — the previous code kept two hand-copied mappings
 * and they had already drifted apart.
 */
public final class MenuScale {

    public enum Mode {
        /** DOOM's 320x200 canvas, aspect-corrected and centred. */
        FIT43,
        /** Real pixels at an integer scale factor. */
        SETTINGS
    }

    private final Mode mode;
    private final int fac;
    // the fit-43 destination rectangle, in screen pixels
    private final double left, top, width, height;
    private final int screenW, screenH;

    private MenuScale(Mode mode, int fac, double left, double top, double width, double height,
                      int screenW, int screenH) {
        this.mode = mode;
        this.fac = fac;
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.screenW = screenW;
        this.screenH = screenH;
    }

    /**
     * CalcFullscreenScale's ScaleToFit43: the virtual height is promoted 200 to 240 for the
     * aspect, then the canvas is PILLARBOXED into a wide screen or LETTERBOXED into a narrow
     * one. The letterbox branch is the one latte-doom never had: its mapping only ever
     * centred horizontally, so in any window narrower than 4:3 the canvas ran off both sides
     * and rows fell off the screen.
     */
    public static MenuScale fit43(int screenW, int screenH) {
        final double aspect = MenuGeom.CANVAS_W / (MenuGeom.CANVAS_H * 1.2); // 320/240
        final double screenRatio = screenW / (double) screenH;
        final double w, h, l, t;
        if (screenRatio >= aspect) {
            h = screenH;
            w = screenH * aspect;
            l = (screenW - w) / 2.0;
            t = 0;
        } else {
            w = screenW;
            h = screenW / aspect;
            l = 0;
            t = (screenH - h) / 2.0;
        }
        return new MenuScale(Mode.FIT43, 1, l, t, w, h, screenW, screenH);
    }

    /**
     * UZDoom's CleanXfac_1 ladder. The load-bearing part is the aspect clamp: a screen wider
     * than 17:10 has its width reduced to what 17:10 would be BEFORE the factor is chosen, so
     * an ultrawide does not get a bigger UI than a 16:9 screen of the same height. That one
     * clamp is what keeps the menu usable on an ultrawide display.
     *
     * The size passed in is Minecraft gui-scaled space, not raw pixels.
     * Minecraft then magnifies by its own integer gui scale, so an integer here stays an
     * integer on the real framebuffer, which is the whole point.
     */
    public static int cleanFac(int screenW, int screenH) {
        final double aspect = screenW / (double) screenH;
        int w = screenW;
        if (aspect > 1.7) {
            w = (int) (screenW * 1.7 / aspect);
        }
        int factor;
        if (w < 640) {
            factor = 1;
        } else if (w >= 1024 && w < 1280) {
            factor = 2;
        } else if (w >= 1600 && w < 1920) {
            factor = 3;
        } else {
            factor = w / 640;
        }
        // the second chain overwrites the first; only w/640 survives, feeding the 0.7
        if (w < 1360) {
            factor = 1;
        } else if (w < 1920) {
            factor = 2;
        } else {
            factor = (int) (factor * 0.7);
        }
        return Math.max(1, factor);
    }

    public static MenuScale settings(int screenW, int screenH) {
        return new MenuScale(Mode.SETTINGS, cleanFac(screenW, screenH),
            0, 0, screenW, screenH, screenW, screenH);
    }

    public static MenuScale of(MenuPage.Scaling scaling, int screenW, int screenH) {
        return scaling == MenuPage.Scaling.SETTINGS
            ? settings(screenW, screenH) : fit43(screenW, screenH);
    }

    public Mode mode() {
        return mode;
    }

    /** The integer patch scale. Always 1 in FIT43, where scaling is fractional by design. */
    public int fac() {
        return fac;
    }

    public int screenW() {
        return screenW;
    }

    public int screenH() {
        return screenH;
    }

    /** Horizontal scale of one canvas unit; only meaningful in FIT43. */
    public double unitX() {
        return width / MenuGeom.CANVAS_W;
    }

    /** Vertical scale of one canvas unit; only meaningful in FIT43. */
    public double unitY() {
        return height / MenuGeom.CANVAS_H;
    }

    public int x(double canvasX) {
        return (int) Math.round(left + canvasX * unitX());
    }

    public int y(double canvasY) {
        return (int) Math.round(top + canvasY * unitY());
    }

    public double canvasX(double screenX) {
        return (screenX - left) * MenuGeom.CANVAS_W / width;
    }

    public double canvasY(double screenY) {
        return (screenY - top) * MenuGeom.CANVAS_H / height;
    }
}
