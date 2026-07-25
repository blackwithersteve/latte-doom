package com.blackwithersteve.lattedoom;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.blackwithersteve.lattedoom.render.DoomRuntimeTextures;
import com.blackwithersteve.lattedoom.render.DoomSfx;
import com.blackwithersteve.lattedoom.render.LatteHud;
import g.Signals.ScanCode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Intermission tally, drawn from the WAD's {@code WI_*} art and the engine's end-of-level
 * numbers, following {@code wi_stuff.c}. Key presses are forwarded to the engine as well,
 * which stays the authority on advancing; this screen closes itself once the next map is
 * live.
 */
public final class LatteIntermissionScreen extends Screen {

    private static final int SND_PISTOL = data.sounds.sfxenum_t.sfx_pistol.ordinal();
    private static final int SND_BAREXP = data.sounds.sfxenum_t.sfx_barexp.ordinal();
    private static final int SND_SGCOCK = data.sounds.sfxenum_t.sfx_sgcock.ordinal();

    private static final int COUNT_KILLS = 0, COUNT_ITEMS = 1, COUNT_SECRET = 2,
        COUNT_TIME = 3, DONE = 4, ENTERING = 5;

    private final DoomHost host;
    private int phase = COUNT_KILLS;
    private int killsPct, itemsPct, secretPct; // displayed, count toward target
    private int timeShown;                     // displayed seconds
    private int ticks;
    private int keyReleaseIn;

    public LatteIntermissionScreen(DoomHost host) {
        super(Component.literal("Latte Doom: intermission"));
        this.host = host;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void tick() {
        ticks++;
        if (keyReleaseIn > 0 && host != null && --keyReleaseIn == 0) {
            host.postKey(ScanCode.SC_SPACE, false);
        }
        final int gsk = host != null ? host.gamestateKind() : -1;
        if (gsk == 0 || gsk == 3 || gsk == -1) {
            onClose(); // the next level went live, or the engine left this state
            return;
        }
        final DoomHost.InterSnap is = host.interSnap();
        if (is == null || phase >= DONE) {
            return;
        }
        // Count speed as in the original: +2 per 35 Hz tic, so about +4 per client tick,
        // with the counting sound played every fourth step.
        if (ticks % 2 == 0) {
            DoomSfx.play(SND_PISTOL, false, 0, 0);
        }
        switch (phase) {
            case COUNT_KILLS -> {
                killsPct += 4;
                if (killsPct >= pct(is.kills, is.maxKills)) {
                    killsPct = pct(is.kills, is.maxKills);
                    phase = COUNT_ITEMS;
                    DoomSfx.play(SND_BAREXP, false, 0, 0);
                }
            }
            case COUNT_ITEMS -> {
                itemsPct += 4;
                if (itemsPct >= pct(is.items, is.maxItems)) {
                    itemsPct = pct(is.items, is.maxItems);
                    phase = COUNT_SECRET;
                    DoomSfx.play(SND_BAREXP, false, 0, 0);
                }
            }
            case COUNT_SECRET -> {
                secretPct += 4;
                if (secretPct >= pct(is.secret, is.maxSecret)) {
                    secretPct = pct(is.secret, is.maxSecret);
                    phase = COUNT_TIME;
                    DoomSfx.play(SND_BAREXP, false, 0, 0);
                }
            }
            case COUNT_TIME -> {
                timeShown += 3;
                if (timeShown >= is.timeTics / 35) {
                    timeShown = is.timeTics / 35;
                    phase = DONE;
                    DoomSfx.play(SND_BAREXP, false, 0, 0);
                }
            }
            default -> { }
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xFF000000);
        final DoomHost.InterSnap is = host != null ? host.interSnap() : null;
        final int ep = is != null ? is.epsd : 0;
        if (ep <= 2 && LatteHud.hasGfx("wimap" + ep)) {
            LatteHud.drawGfx(g, "wimap" + ep, 0, 0, this.width, this.height);
        } else if (LatteHud.hasGfx("interpic")) {
            LatteHud.drawGfx(g, "interpic", 0, 0, this.width, this.height);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        final DoomHost.InterSnap is = host != null ? host.interSnap() : null;
        final int guiW = this.width, guiH = this.height;
        if (is == null) {
            g.centeredText(this.font, "TALLYING...", guiW / 2, guiH / 2, 0xFFFF5555);
            return;
        }
        if (phase < ENTERING) {
            // Finished level name and the FINISHED banner.
            final String lv = "wilv" + is.epsd + is.last;
            final int nameH = centered(g, lv, 2, guiW, guiH);
            centered(g, "wif", 2 + nameH * 5 / 4, guiW, guiH);
            // Stats block, single-player layout: labels at x=50, values right-aligned
            // at x=270.
            LatteHud.drawGfx(g, "wiostk", 50, 50, guiW, guiH);
            pctRight(g, killsPct, 270, 50, guiW, guiH);
            LatteHud.drawGfx(g, "wiosti", 50, 83, guiW, guiH);
            pctRight(g, itemsPct, 270, 83, guiW, guiH);
            LatteHud.drawGfx(g, "wiscrt2", 50, 116, guiW, guiH);
            pctRight(g, secretPct, 270, 116, guiW, guiH);
            // Elapsed time on the left half, par time on the right.
            LatteHud.drawGfx(g, "witime", 16, 160, guiW, guiH);
            timeRight(g, timeShown, 144, 160, guiW, guiH);
            if (phase >= COUNT_TIME || phase == DONE) {
                LatteHud.drawGfx(g, "wipar", 176, 160, guiW, guiH);
                timeRight(g, is.parTics / 35, 304, 160, guiW, guiH);
            }
        } else {
            // The ENTERING banner and the next level's name.
            final int entH = centered(g, "wienter", 2, guiW, guiH);
            centered(g, "wilv" + nextEpisode(is) + is.next, 2 + entH * 5 / 4, guiW, guiH);
        }
    }

    /** The next map stays within the current episode, including after a secret exit. */
    private static int nextEpisode(DoomHost.InterSnap is) {
        return is.epsd;
    }

    private static int pct(int have, int max) {
        return have * 100 / Math.max(1, max);
    }

    /** Draws a patch centred horizontally at the given canvas y and returns its height. */
    private int centered(GuiGraphicsExtractor g, String lump, int y, int guiW, int guiH) {
        final int[] size = DoomRuntimeTextures.textureSize("gfx/" + lump);
        if (size == null) {
            return 12;
        }
        LatteHud.drawGfx(g, lump, (320 - size[0]) / 2.0, y, guiW, guiH);
        return size[1];
    }

    /** Draws a percentage right-aligned at the given canvas x, using the winum digits
     * and the WIPCNT percent sign. */
    private void pctRight(GuiGraphicsExtractor g, int value, int endX, int y,
                          int guiW, int guiH) {
        final int[] pc = DoomRuntimeTextures.textureSize("gfx/wipcnt");
        double x = endX;
        if (pc != null) {
            x -= pc[0];
            LatteHud.drawGfx(g, "wipcnt", x, y, guiW, guiH);
        }
        numRight(g, value, x, y, guiW, guiH);
    }

    /** Draws mm:ss right-aligned at the given canvas x, using the winum digits and the
     * WICOLON separator. */
    private void timeRight(GuiGraphicsExtractor g, int seconds, int endX, int y,
                           int guiW, int guiH) {
        double x = numRight(g, seconds % 60, endX, y, guiW, guiH);
        // Seconds are a two-digit field, so the colon position is padded below 10.
        if (seconds % 60 < 10) {
            final int[] d = DoomRuntimeTextures.textureSize("gfx/winum0");
            if (d != null) {
                x -= d[0];
                LatteHud.drawGfx(g, "winum0", x, y, guiW, guiH);
            }
        }
        final int[] col = DoomRuntimeTextures.textureSize("gfx/wicolon");
        if (col != null) {
            x -= col[0];
            LatteHud.drawGfx(g, "wicolon", x, y, guiW, guiH);
        }
        numRight(g, seconds / 60, x, y, guiW, guiH);
    }

    /** Draws a winum number right-aligned at the given canvas x and returns the left
     * edge it reached. */
    private double numRight(GuiGraphicsExtractor g, int value, double endX, int y,
                            int guiW, int guiH) {
        int v = Math.max(0, value);
        double x = endX;
        do {
            final String lump = "winum" + (v % 10);
            final int[] size = DoomRuntimeTextures.textureSize("gfx/" + lump);
            if (size == null) {
                return x;
            }
            x -= size[0];
            LatteHud.drawGfx(g, lump, x, y, guiW, guiH);
            v /= 10;
        } while (v > 0);
        return x;
    }

    // ---- Input: accelerate the local tally, and always forward the press to the
    // engine, which owns the advance to the next level. ----

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT || LatteDoomClient.isBootKey(event)) {
            onClose();
            return true;
        }
        press();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        press();
        return true;
    }

    private void press() {
        final DoomHost.InterSnap is = host != null ? host.interSnap() : null;
        if (is != null && phase < DONE) {
            // Skip: land every remaining stat at once, as the original does.
            killsPct = pct(is.kills, is.maxKills);
            itemsPct = pct(is.items, is.maxItems);
            secretPct = pct(is.secret, is.maxSecret);
            timeShown = is.timeTics / 35;
            phase = DONE;
            DoomSfx.play(SND_BAREXP, false, 0, 0);
        } else if (phase == DONE) {
            phase = ENTERING;
            DoomSfx.play(SND_SGCOCK, false, 0, 0);
        }
        if (host != null && keyReleaseIn == 0) {
            host.postKey(ScanCode.SC_SPACE, true); // the engine's WI advances on the same press
            keyReleaseIn = 2;
        }
    }
}
