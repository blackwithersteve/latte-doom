package com.blackwithersteve.lattedoom.render;

import com.blackwithersteve.lattedoom.engine.WorldSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The marine's view weapon (and muzzle flash) as a HUD overlay — the ENGINE's own psprite
 * state machine decides which sprite/frame shows and where it bobs; we draw the exact
 * lump on Minecraft's screen. Coordinates: DOOM psprites live in a 320x200 canvas
 * (R_DrawPSprite: left edge = sx - leftoffset, top = sy - topoffset); we map that canvas
 * to the screen at 4:3 (x-scale = height×4/3÷320) hugging the bottom, centered.
 */
public final class LatteHud {

    private static final Map<String, Identifier> IDS = new HashMap<>();

    /**
     * Whether the DOOM interface can actually be drawn. Transforming is instant but the
     * engine boots on its own thread, and its first snapshot can be seconds away, so this is
     * false for a while after the player transforms. Anything that hides Minecraft's own
     * interface has to consult this rather than the form alone, or the player is left with
     * neither interface for as long as the boot takes.
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
        // NOT gated on being inside the level: the marine keeps his suit (gun + STBAR)
        // in the overworld too — the engine's stats exist as long as it runs a level.
        // The SUIT snapshot is always OUR OWN engine — on a shared level the world may
        // be someone else's feed, but the ammo counter never is.
        final WorldSnapshot snap = LatteWorld.suitSnap();
        final SpriteSet sprites = LatteWorld.sprites();
        if (snap == null || sprites == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final int guiW = mc.getWindow().getGuiScaledWidth();
        final int guiH = mc.getWindow().getGuiScaledHeight();

        // DOOM's weapon bob, synthesized: the engine can't bob (the mirror zeroes the
        // marine's momentum), so feed its OWN formula with the MC player's speed —
        // bob = min(MAXBOB 16, v²/4), swaying on the engine's leveltime clock.
        double bobX = 0, bobY = 0;
        if (mc.player != null) {
            final double v = mc.player.getDeltaMovement().horizontalDistance()
                * LatteWorld.UNITS / 1.75; // blocks/tick -> u/tic
            // Crispy Doom's bob setting: full, three quarters, or none
            final double bobAmount = switch (com.blackwithersteve.lattedoom
                .LatteDoomClient.bobScale()) {
                case 1 -> 0.75;
                case 2 -> 0.0;
                default -> 1.0;
            };
            // The phase clock is continuous, but the AMPLITUDE comes from the player's
            // velocity, which only changes at 20Hz tick boundaries — so starting and
            // stopping staircased for a few frames while steady motion was smooth.
            // Ease the amplitude toward its target on frame time instead.
            final double target = Math.min(16.0, v * v / 4.0) * bobAmount;
            final long now = System.nanoTime();
            final double dt = lastBobNanos == 0 ? 0.05
                : Math.min(0.1, (now - lastBobNanos) / 1.0e9);
            lastBobNanos = now;
            smoothBobAmp += (target - smoothBobAmp) * Math.min(1.0, dt * 14.0);
            final double bob = smoothBobAmp;
            // CONTINUOUS tic clock, not the integer tic: quantizing the sway phase to
            // 35Hz made the bob step like a low-refresh screen on any fast monitor
            final double a = (LatteWorld.ticTime() * 128.0 % 8192.0) / 8192.0 * 2.0 * Math.PI;
            bobX = bob * Math.cos(a);
            bobY = bob * Math.abs(Math.sin(a));
        }
        // only the ready state bobs — A_WeaponReady is the one mover of the psprite in
        // the original, so firing freezes the sway wherever it was
        if (snap.weaponReady) {
            frozenBobX = bobX;
            frozenBobY = bobY;
        } else {
            bobX = frozenBobX;
            bobY = frozenBobY;
        }
        // the gun in your hands is lit by the room you stand in (fullbright frames — the
        // muzzle flash — override, exactly like R_DrawPSprite's colormap pick)
        int gunShade = 255;
        if (mc.player != null) {
            final int lc = LatteWorld.levelLightCoords(
                mc.player.getX(), mc.player.getY(), mc.player.getZ());
            if (lc >= 0) {
                // the view weapon sits at arm's length: the lit curve at ~41 units,
                // which is the software renderer's brightest scalelight rung
                gunShade = LatteMesh.doomShade(
                    Math.min(255, (lc >> 4) * 255 / 15 + LatteWorld.extraLightBytes()),
                    41.0, false);
            }
        }
        // dead marines hold no gun: DOOM drops the view weapon away on death — while the
        // MC player is dead (DoomDeathScreen up) the psprites vanish; the STBAR stays,
        // exactly like 1993 (the engine's immortal mirror can't do this for us).
        final boolean dead = mc.player != null && mc.player.isDeadOrDying();
        // the automap replaces the view (gun included), STBAR stays on top — vanilla layering
        final DoomMap amMap = LatteWorld.map();
        final boolean automap = DoomAutomap.active() && amMap != null && mc.player != null;
        if (automap) {
            DoomAutomap.draw(g, amMap, snap,
                LatteWorld.worldToDoomX(mc.player.getX()),
                LatteWorld.worldToDoomY(mc.player.getZ()),
                -mc.player.getYRot() - 90.0, guiW, guiH);
        } else if (!dead) {
            draw(g, sprites, snap.wSprite, snap.wFrame, snap.wX + bobX,
                snap.wY + bobY, guiW, guiH, gunShade);
            draw(g, sprites, snap.fSprite, snap.fFrame, snap.fX + bobX,
                snap.fY + bobY, guiW, guiH, gunShade);
        }
        switch (com.blackwithersteve.lattedoom.LatteDoomClient.hudSize()) {
            case 0 -> statusBar(g, snap, mc, guiW, guiH);
            case 1 -> minimalHud(g, snap, sprites, mc, guiW, guiH);
            default -> { } // size 2: nothing but the world
        }
        // messages arrive from the host's queue in the client tick, not from the snapshot
        if (!automap) {
            drawNotify(g, guiW, guiH);
        }
        if (!automap && !dead) {
            crosshair(g, snap, mc, guiW, guiH);
        }
        if (com.blackwithersteve.lattedoom.LatteDoomClient.levelStats()
            && LatteWorld.map() != null) {
            statsWidget(g, snap, guiW, guiH);
        }
        flashes(g, snap, guiW, guiH);
    }

    /** The crosshair shapes, in place of UZDoom's XHAIR lumps. */
    public static final String[] CROSSHAIR_NAMES =
        {"OFF", "CROSS", "OPEN CROSS", "DOT", "ANGLE", "CIRCLE", "CHEVRON"};

    /**
     * The crosshair.
     *
     * UZDoom picks a graphic from XHAIRS/XHAIRB lumps that it ships itself — DOOM.WAD has
     * none, so the shapes are drawn as geometry. Its BEHAVIOUR is reproduced: the size is
     * crosshairscale times an INTEGER screen multiplier (base_sbar.cpp:143-150 uses
     * max(height/720, 1), so 1080p gives 1 and not 1.5), the colour is lerped by health with
     * the breakpoint at 85 rather than 100, and a pickup briefly grows it.
     *
     * DIVERGENCE, disclosed: UZDoom centres the crosshair on the VIEW WINDOW, so with the
     * status bar up it sits at about 42% of screen height. Minecraft always renders the world
     * full-screen and centred, so the view-window centre does not exist here and this stays
     * at the screen centre.
     */
    private static void crosshair(GuiGraphicsExtractor g, WorldSnapshot snap, Minecraft mc,
                                  int guiW, int guiH) {
        final int style = com.blackwithersteve.lattedoom.LatteDoomClient.crosshair();
        if (style <= 0 || mc.player == null) {
            return;
        }
        int color = 0xFFDADADA;
        if (com.blackwithersteve.lattedoom.LatteDoomClient.crosshairHealth()) {
            // base_sbar.cpp:177-182 — below 85 lerp red to green, above 100 green to blue,
            // and between 86 and 100 the colour is pinned at full
            final int health = (int) Math.ceil(mc.player.getHealth() * 5.0f);
            if (health > 100) {
                color = lerpColor(0xFF00FF00, 0xFF7F7FFF, Math.min(1.0, (health - 100) / 100.0));
            } else if (health <= 85) {
                color = lerpColor(0xFFFF0000, 0xFF00FF00, Math.max(0.0, health / 85.0));
            } else {
                color = 0xFF00FF00;
            }
        }
        // grow on pickup, decaying like UZDoom's 1/18-per-tic CrosshairSize
        if (snap != null && snap.bonusCount > 0) {
            growUntil = System.currentTimeMillis() + 300;
        }
        double grow = 1.0;
        final long left = growUntil - System.currentTimeMillis();
        if (left > 0) {
            grow = 1.0 + 0.6 * (left / 300.0);
        }

        // Size in gui-scaled units directly. Minecraft's gui scale is already the thing that
        // keeps interface elements a consistent physical size, so applying UZDoom's own
        // screen-height multiplier on top of it and then dividing back out only collapsed
        // the shapes into two or three integer pixels, where none of them can be told apart.
        final double scale = com.blackwithersteve.lattedoom.LatteDoomClient.crosshairScale()
            * grow;
        final int cx = guiW / 2, cy = guiH / 2;
        final int r = Math.max(2, (int) Math.round(5 * scale));
        final int t = Math.max(1, (int) Math.round(1.2 * scale));
        // a bar of thickness t centred on the middle pixel, rather than hanging off it
        final int o = -(t - 1) / 2;
        // the hole in an open shape, always leaving arms at least two pixels long
        final int gap = Math.max(1, Math.min(r - 2, r / 2));

        switch (style) {
            case 1 -> { // full cross
                g.fill(cx - r, cy + o, cx + r + 1, cy + o + t, color);
                g.fill(cx + o, cy - r, cx + o + t, cy + r + 1, color);
            }
            case 2 -> { // open cross: four arms with a hole in the middle
                g.fill(cx - r, cy + o, cx - gap, cy + o + t, color);
                g.fill(cx + gap + 1, cy + o, cx + r + 1, cy + o + t, color);
                g.fill(cx + o, cy - r, cx + o + t, cy - gap, color);
                g.fill(cx + o, cy + gap + 1, cx + o + t, cy + r + 1, color);
            }
            case 3 -> g.fill(cx + o, cy + o, cx + o + t, cy + o + t, color); // dot
            case 4 -> { // angle brackets, opening left and right
                g.fill(cx - r, cy - gap, cx - r + t, cy + gap + 1, color);
                g.fill(cx - r, cy - gap, cx - gap, cy - gap + t, color);
                g.fill(cx - r, cy + gap + 1 - t, cx - gap, cy + gap + 1, color);
                g.fill(cx + r + 1 - t, cy - gap, cx + r + 1, cy + gap + 1, color);
                g.fill(cx + gap + 1, cy - gap, cx + r + 1, cy - gap + t, color);
                g.fill(cx + gap + 1, cy + gap + 1 - t, cx + r + 1, cy + gap + 1, color);
            }
            case 5 -> ring(g, cx, cy, r, t, color);
            default -> { // chevron pointing up, its point on the centre
                for (int i = 0; i <= r; i++) {
                    g.fill(cx - i, cy - r + i, cx - i + t, cy - r + i + t, color);
                    g.fill(cx + i, cy - r + i, cx + i + t, cy - r + i + t, color);
                }
            }
        }
    }

    /** An actual circle, plotted around the rim — the previous "circle" was a square ring. */
    private static void ring(GuiGraphicsExtractor g, int cx, int cy, int radius, int t,
                             int color) {
        final int steps = Math.max(24, radius * 8);
        for (int i = 0; i < steps; i++) {
            final double a = i * Math.PI * 2.0 / steps;
            final int px = cx + (int) Math.round(Math.cos(a) * radius);
            final int py = cy + (int) Math.round(Math.sin(a) * radius);
            g.fill(px, py, px + t, py + t, color);
        }
    }

    private static long growUntil;

    private static int lerpColor(int a, int b, double f) {
        final double k = Math.max(0.0, Math.min(1.0, f));
        final int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        final int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
            | ((int) Math.round(ar + (br - ar) * k) << 16)
            | ((int) Math.round(ag + (bg - ag) * k) << 8)
            | (int) Math.round(ab + (bb - ab) * k);
    }

    /**
     * The engine's heads-up lines, as a notify buffer rather than one line.
     *
     * DOOM prints pickups and key messages faster than a single 4-second slot can show, so
     * earlier lines were being lost. This keeps a small stack like a source port's: four
     * lines, three seconds each, the oldest dropping first, and an alpha fade over the last
     * stretch instead of a hard disappearance.
     */
    /**
     * Push an engine message onto the notify stack.
     *
     * A repeat of the line already on top does not add a row; it bumps a counter and
     * refreshes the timer, and the row then reads "MESSAGE (x3)". UZDoom's notify buffer
     * does the same thing, and without it picking up four shells in a row buries every
     * other message.
     */
    public static void pushMessage(String msg) {
        pushNotify(msg);
    }

    private static void pushNotify(String msg) {
        if (msg == null || msg.isEmpty()
            || !com.blackwithersteve.lattedoom.LatteDoomClient.showMessages()) {
            return;
        }
        final String text = msg.toUpperCase(java.util.Locale.ROOT);
        final long life = (long) (com.blackwithersteve.lattedoom.LatteDoomClient
            .messageTime() * 1000);
        if (!notify.isEmpty()) {
            final Notice top = notify.get(notify.size() - 1);
            if (top.text.equals(text)) {
                top.repeats++;
                top.until = System.currentTimeMillis() + life;
                return;
            }
        }
        notify.add(new Notice(text, System.currentTimeMillis() + life));
        final int max = com.blackwithersteve.lattedoom.LatteDoomClient.messageLines();
        while (notify.size() > max) {
            notify.remove(0);
        }
    }

    /**
     * The message stack, top left, wrapped to the screen and faded out at the end of each
     * line's life rather than vanishing.
     */
    private static void drawNotify(GuiGraphicsExtractor g, int guiW, int guiH) {
        final long now = System.currentTimeMillis();
        notify.removeIf(n -> now >= n.until);
        if (notify.isEmpty()) {
            return;
        }
        final double left = edgeLeft(guiW, guiH) + 2;
        final double usable = (edgeRight(guiW, guiH) - 4) - left;
        double y = 2;
        for (Notice n : notify) {
            final long remaining = n.until - now;
            final int alpha = remaining >= NOTIFY_FADE_MS ? 255
                : (int) Math.max(0, 255 * remaining / NOTIFY_FADE_MS);
            final String text = n.repeats > 1 ? n.text + " (X" + n.repeats + ")" : n.text;
            for (String line : wrap(text, usable)) {
                drawHudTextTinted(g, line, left, y, guiW, guiH, (alpha << 24) | 0xFFFFFF);
                y += fontHeight() + 1;
            }
        }
    }

    /** Break a line to fit the usable width, on spaces where possible. */
    private static java.util.List<String> wrap(String text, double widthUnits) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        if (widthUnits <= 0 || textWidth(text) <= widthUnits) {
            out.add(text);
            return out;
        }
        final StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            final String candidate = line.isEmpty() ? word : line + " " + word;
            if (textWidth(candidate) > widthUnits && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    private static final class Notice {
        private final String text;
        private long until;
        private int repeats = 1;

        Notice(String text, long until) {
            this.text = text;
            this.until = until;
        }
    }


    private static final long NOTIFY_FADE_MS = 400;
    private static final java.util.List<Notice> notify = new java.util.ArrayList<>();

    /** Crispy Doom's level stats: kills, items, secrets and the level time, top left,
     * in the WAD's small font. */
    private static void statsWidget(GuiGraphicsExtractor g, WorldSnapshot snap,
                                    int guiW, int guiH) {
        // below the message stack, and on the HUD's scale like everything else on screen
        final double left = edgeLeft(guiW, guiH) + 2;
        final double top = 4 + (fontHeight() + 1)
            * com.blackwithersteve.lattedoom.LatteDoomClient.messageLines();
        final int line = fontHeight() + 2;
        drawHudTextTinted(g, String.format("K %d/%d  I %d/%d  S %d/%d",
            snap.killCount, snap.totalKills, snap.itemCount, snap.totalItems,
            snap.secretCount, snap.totalSecrets), left, top, guiW, guiH, 0xFFFFFFFF);
        final int seconds = snap.levelTime / 35;
        drawHudTextTinted(g, String.format("TIME %d:%02d", seconds / 60, seconds % 60),
            left, top + line, guiW, guiH, 0xFFFFFFFF);
    }

    /**
     * The simplified fullscreen interface source ports show at larger screen sizes:
     * health at the left edge, the ready weapon's ammo at the right, owned keys stacked
     * above it — all from the status bar's own art, nothing modern.
     */
    /**
     * fullscreenOffsets: a canvas coordinate anchored to the TRUE screen edge rather than to
     * the 4:3 box, which is how a widescreen source port places a fullscreen HUD. A negative
     * x measures in from the right edge, a negative y up from the bottom, exactly as
     * UZDoom's DrawFullScreenStuff coordinates read.
     */
    private static double fsX(double x, int guiW, int guiH) {
        return x >= 0 ? edgeLeft(guiW, guiH) + x : edgeRight(guiW, guiH) + x;
    }

    private static double fsY(double y, int guiH) {
        return y >= 0 ? y : edgeBottom(guiH) + y;
    }

    /**
     * The fullscreen HUD, following DOOM's own (doom_sbar.zs DrawFullScreenStuff) rather
     * than UZDoom's AltHud — the AltHud needs two fonts that ship inside gzdoom.pk3, while
     * this uses nothing but IWAD art and sits on the same 320x200 canvas as the status bar.
     *
     * Layout is UZDoom's, coordinate for coordinate: the medikit and health at the bottom
     * left with armour stacked ABOVE it, the ready ammo bottom right, and the keys in the
     * top right corner stacking downward. Numbers are three mono cells of STTNUM wide, so a
     * three-digit value never reaches its icon.
     */
    private static void minimalHud(GuiGraphicsExtractor g, WorldSnapshot snap,
                                   SpriteSet sprites, Minecraft mc, int guiW, int guiH) {
        final int health = Math.min(200, (int) Math.ceil(mc.player.getHealth() * 5.0f));
        final int cell = 14; // HUDFONT_DOOM's mono cell: the width of STTNUM0

        // health, with berserk swapping the medikit for the strength sprite as UZDoom does
        final boolean berserk = snap.berserk;
        icon(g, sprites, berserk ? "PSTR" : "MEDI", fsX(20, guiW, guiH), fsY(-2, guiH), 16,
            guiW, guiH, true);
        numRight(g, "sttnum", health, fsX(44 + 3 * cell, guiW, guiH), fsY(-20, guiH),
            guiW, guiH);

        // armour sits ABOVE health, not beside it
        if (snap.armor > 0) {
            icon(g, sprites, snap.armorType == 2 ? "ARM2" : "ARM1",
                fsX(20, guiW, guiH), fsY(-22, guiH), 16, guiW, guiH, true);
            numRight(g, "sttnum", snap.armor, fsX(44 + 3 * cell, guiW, guiH),
                fsY(-40, guiH), guiW, guiH);
        }

        // the ready weapon's ammo, bottom right: the icon CENTRED at -14 so it stays on
        // screen, the number right-aligned at -30, to its left
        if (snap.readyAmmoType >= 0 && snap.readyAmmoType < 4 && snap.ammo != null) {
            icon(g, sprites, AMMO_SPRITES[snap.readyAmmoType], fsX(-14, guiW, guiH),
                fsY(-4, guiH), 16, guiW, guiH, true);
            numRight(g, "sttnum", snap.ammo[snap.readyAmmoType], fsX(-30, guiW, guiH),
                fsY(-20, guiH), guiW, guiH);
        }

        // keys in the top right corner, stacking down, a skull drawn over its own card
        if (snap.cards != null) {
            double keyY = 2;
            for (int k = 0; k < 3; k++) {
                final boolean card = snap.cards[k];
                final boolean skull = snap.cards[k + 3];
                if (card || skull) {
                    patch(g, "stkeys" + (skull ? k + 3 : k), fsX(-10, guiW, guiH), keyY,
                        guiW, guiH);
                    keyY += 10;
                }
            }
        }
    }

    /** The pickup sprite standing for each ammo pool, for the fullscreen readout. */
    private static final String[] AMMO_SPRITES = {"CLIP", "SHEL", "CELL", "ROCK"};

    /** The true left screen edge in canvas units (0 on a 4:3 window, negative on wider). */
    private static double edgeLeft(int guiW, int guiH) {
        return 160.0 - guiW / (2.0 * hudXs(guiH));
    }

    private static double edgeRight(int guiW, int guiH) {
        return 160.0 + guiW / (2.0 * hudXs(guiH));
    }

    /** The bottom screen edge in canvas units — 200 unscaled, further down when smaller. */
    private static double edgeBottom(int guiH) {
        return guiH / hudYs(guiH);
    }

    private static double frozenBobX, frozenBobY;
    private static double smoothBobAmp;
    private static long lastBobNanos;

    /** A world sprite as a small HUD icon: bottom-left anchored at canvas coords,
     * scaled to the given height. */
    private static void icon(GuiGraphicsExtractor g, SpriteSet sprites, String sprName,
                             double canvasX, double canvasBottomY, double targetH,
                             int guiW, int guiH) {
        icon(g, sprites, sprName, canvasX, canvasBottomY, targetH, guiW, guiH, false);
    }

    /**
     * A pickup sprite scaled to {@code targetH} canvas units tall.
     *
     * {@code centred} places the sprite's MIDDLE at canvasX rather than its left edge, which
     * is what UZDoom's inventory-icon coordinates mean. Drawing a right-anchored icon from
     * its left edge pushes it off the screen — that is what clipped the ammo readout.
     */
    private static void icon(GuiGraphicsExtractor g, SpriteSet sprites, String sprName,
                             double canvasX, double canvasBottomY, double targetH,
                             int guiW, int guiH, boolean centred) {
        if (sprites == null) {
            return;
        }
        final SpriteSet.View view = sprites.view(sprName, 0, 0);
        if (view == null) {
            return;
        }
        final String key = "sprites/" + view.lump();
        final int[] size = DoomRuntimeTextures.textureSize(key);
        if (size == null || size[1] <= 0) {
            return;
        }
        final double f = targetH / size[1];
        final double xs = hudXs(guiH);
        final double ys = hudYs(guiH);
        final double drawX = centred ? canvasX - size[0] * f / 2.0 : canvasX;
        final int x = (int) Math.round(guiW / 2.0 + (drawX - 160.0) * xs);
        final int y = (int) Math.round((canvasBottomY - targetH) * ys);
        g.blit(RenderPipelines.GUI_TEXTURED, idOf(key), x, y, 0.0f, 0.0f,
            (int) Math.round(size[0] * f * xs), (int) Math.round(targetH * ys),
            size[0], size[1], size[0], size[1]);
    }

    /**
     * The HUD's own scale, on top of the canvas mapping. UZDoom keeps the status bar on a
     * scale ladder separate from the menu's precisely because a bar is meant to grow with
     * the screen while a settings page is not; this is that control.
     */
    static double hudXs(int guiH) {
        return guiH * (4.0 / 3.0) / 320.0
            * com.blackwithersteve.lattedoom.LatteDoomClient.hudScale();
    }

    static double hudYs(int guiH) {
        return guiH / 200.0 * com.blackwithersteve.lattedoom.LatteDoomClient.hudScale();
    }

    // ------------------------------------------------------------------ the 1:1 STBAR

    private static int lastWeaponMask = -1;
    private static int evilUntilTic;

    /** Classic st_stuff layout on the 320x200 canvas (bar at y=168), the WAD's own art. */
    private static void statusBar(GuiGraphicsExtractor g, WorldSnapshot snap,
                                  Minecraft mc, int guiW, int guiH) {
        patch(g, "stbar", 0, 168, guiW, guiH);
        patch(g, "starms", 104, 168, guiW, guiH);

        // ready-weapon ammo (blank for fist/chainsaw, exactly like vanilla)
        if (snap.readyAmmoType >= 0 && snap.readyAmmoType < 4 && snap.ammo != null) {
            numRight(g, "sttnum", snap.ammo[snap.readyAmmoType], 44, 171, guiW, guiH);
        }
        final int health = Math.min(200, (int) Math.ceil(mc.player.getHealth() * 5.0f));
        numRight(g, "sttnum", health, 90, 171, guiW, guiH);
        patch(g, "sttprcnt", 90, 171, guiW, guiH);
        numRight(g, "sttnum", snap.armor, 221, 171, guiW, guiH);
        patch(g, "sttprcnt", 221, 171, guiW, guiH);

        // ARMS: digits 2-7 light up with owned weapons (pistol..bfg = weaponOwned[1..6])
        if (snap.weaponOwned != null) {
            for (int i = 0; i < 6; i++) {
                final boolean owned = i + 1 < snap.weaponOwned.length && snap.weaponOwned[i + 1];
                patch(g, (owned ? "stysnum" : "stgnum") + (i + 2),
                    111 + (i % 3) * 12, 172 + (i / 3) * 10, guiW, guiH);
            }
        }

        // keys: cards 0-2, skulls 3-5 share the three rows
        if (snap.cards != null && snap.cards.length >= 6) {
            for (int row = 0; row < 3; row++) {
                if (snap.cards[row + 3]) {
                    patch(g, "stkeys" + (row + 3), 239, 171 + 10 * row, guiW, guiH);
                } else if (snap.cards[row]) {
                    patch(g, "stkeys" + row, 239, 171 + 10 * row, guiW, guiH);
                }
            }
        }

        // ammo table (cur/max): rows BULL, SHEL, RCKT, CELL = pools 0, 1, 3, 2
        if (snap.ammo != null && snap.maxAmmo != null) {
            final int[] rows = {0, 1, 3, 2};
            for (int r = 0; r < 4; r++) {
                numRight(g, "stysnum", snap.ammo[rows[r]], 288, 173 + 6 * r, guiW, guiH);
                numRight(g, "stysnum", snap.maxAmmo[rows[r]], 314, 173 + 6 * r, guiW, guiH);
            }
        }

        // the face (simplified st_stuff): pain level by health, hurt grimace while the
        // damage counter runs, evil grin ~2s on a new weapon, dead at zero
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

    /** Vanilla's palette shifts, as overlays: damage reds, pickup golds — over EVERYTHING. */
    private static void flashes(GuiGraphicsExtractor g, WorldSnapshot snap, int guiW, int guiH) {
        if (snap.damageCount > 0) {
            final int a = (int) (Math.min(1.0, snap.damageCount / 32.0) * 0.55 * 255);
            g.fill(0, 0, guiW, guiH, (a << 24) | 0xFF0000);
        } else if (snap.bonusCount > 0) {
            final int a = (int) (Math.min(1.0, snap.bonusCount / 24.0) * 0.35 * 255);
            g.fill(0, 0, guiW, guiH, (a << 24) | 0xD7BA45);
        } else if (snap.radSuit) {
            // ST_doPaletteStuff's RADIATIONPAL: the suit washes the screen green
            g.fill(0, 0, guiW, guiH, 0x2E00C000);
        }
    }

    /** Public draw of a registered DOOM menu/status patch ("gfx/&lt;lump&gt;") at 320x200 canvas
     * coords, V_DrawPatch semantics, 4:3-centered — for DOOM-styled overlays like the volume
     * menu. Silently no-ops if the lump wasn't in the WAD. */
    public static void drawGfx(GuiGraphicsExtractor g, String lump,
                               double canvasX, double canvasY, int guiW, int guiH) {
        patchPlain(g, lump, canvasX, canvasY, guiW, guiH); // menu art, not HUD-scaled
    }

    /** Is a menu/status patch registered (present in the loaded WAD)? */
    public static boolean hasGfx(String lump) {
        return DoomRuntimeTextures.textureSize("gfx/" + lump) != null;
    }

    /**
     * A line of text in the WAD's own small font (the STCFN patches the heads-up messages
     * use), for menu entries that have no ready-made graphic — the way source ports label
     * options the original menu never had.
     */
    public static void drawHudText(GuiGraphicsExtractor g, String text,
                                   double canvasX, double canvasY, int guiW, int guiH) {
        double x = canvasX;
        for (int i = 0; i < text.length(); i++) {
            final char c = Character.toUpperCase(text.charAt(i));
            if (c == ' ') {
                x += 4;
                continue;
            }
            final String lump = String.format("stcfn%03d", (int) c);
            final int[] size = DoomRuntimeTextures.textureSize("gfx/" + lump);
            if (size == null) {
                x += 4;
                continue;
            }
            patchPlain(g, lump, x, canvasY, guiW, guiH); // shared with the menu's pages
            x += size[0] + 1;
        }
    }

    /** {@link #drawHudText} with an ARGB tint multiplied into every glyph. */
    public static void drawHudTextTinted(GuiGraphicsExtractor g, String text,
                                         double canvasX, double canvasY, int guiW, int guiH,
                                         int color) {
        double x = canvasX;
        for (int i = 0; i < text.length(); i++) {
            final char c = Character.toUpperCase(text.charAt(i));
            if (c == ' ') {
                x += 4;
                continue;
            }
            final String lump = String.format("stcfn%03d", (int) c);
            final String key = "gfx/" + lump;
            final int[] size = DoomRuntimeTextures.textureSize(key);
            if (size == null) {
                x += 4;
                continue;
            }
            // the HUD's own scale, so the messages and the stats block track the status
            // bar instead of staying put while everything around them resizes
            final double xs = hudXs(guiH);
            final double ys = hudYs(guiH);
            final int px = (int) Math.round(guiW / 2.0 + (x - 160.0) * xs);
            final int py = (int) Math.round(canvasY * ys);
            g.blit(RenderPipelines.GUI_TEXTURED, idOf(key), px, py, 0.0f, 0.0f,
                (int) Math.round(size[0] * xs), (int) Math.round(size[1] * ys),
                size[0], size[1], size[0], size[1], color);
            x += size[0] + 1;
        }
    }

    /** Height of the WAD's small font, from the 'A' glyph. 0 when no WAD is loaded. */
    public static int fontHeight() {
        final int[] size = DoomRuntimeTextures.textureSize("gfx/stcfn065");
        return size == null ? 0 : size[1];
    }

    /** Width this string occupies in the WAD's small font, in font pixels. */
    public static int textWidth(String text) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = Character.toUpperCase(text.charAt(i));
            if (c == ' ') {
                w += 4;
                continue;
            }
            final int[] size = DoomRuntimeTextures.textureSize(
                "gfx/" + String.format("stcfn%03d", (int) c));
            w += size == null ? 4 : size[0] + 1;
        }
        return w;
    }

    /**
     * A line of small-font text in REAL pixels at an integer scale, optionally tinted.
     *
     * This is the settings-menu path. Text there is laid out in screen pixels at an integer
     * factor rather than on the 320x200 canvas, because a fractional factor resamples the
     * glyph patches unevenly — which is what made the menu look wrong at high resolution.
     * {@code color} is ARGB and multiplies the glyph; 0xFFFFFFFF leaves it alone.
     */
    public static void drawTextPx(GuiGraphicsExtractor g, String text, int px, int py,
                                  int scale, int color) {
        int x = px;
        for (int i = 0; i < text.length(); i++) {
            final char c = Character.toUpperCase(text.charAt(i));
            if (c == ' ') {
                x += 4 * scale;
                continue;
            }
            final String lump = String.format("stcfn%03d", (int) c);
            final String key = "gfx/" + lump;
            final int[] size = DoomRuntimeTextures.textureSize(key);
            if (size == null) {
                x += 4 * scale;
                continue;
            }
            g.blit(RenderPipelines.GUI_TEXTURED, idOf(key), x, py, 0.0f, 0.0f,
                size[0] * scale, size[1] * scale, size[0], size[1], size[0], size[1], color);
            x += (size[0] + 1) * scale;
        }
    }

    /** A WAD graphic in REAL pixels at an integer scale, tinted. Offsets are applied, as
     * V_DrawPatch does. */
    public static void drawGfxPx(GuiGraphicsExtractor g, String lump, int px, int py,
                                 int scale, int color) {
        final String key = "gfx/" + lump;
        final int[] size = DoomRuntimeTextures.textureSize(key);
        if (size == null) {
            return;
        }
        int x = px, y = py;
        final int[] ofs = DoomRuntimeTextures.spriteOffset(key);
        if (ofs != null) {
            x -= ofs[0] * scale;
            y -= ofs[1] * scale;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, idOf(key), x, y, 0.0f, 0.0f,
            size[0] * scale, size[1] * scale, size[0], size[1], size[0], size[1], color);
    }

    /** Blit a status graphic at classic 320x200 canvas coords, V_DrawPatch semantics
     * (the patch's own left/top offsets are subtracted — the face lumps carry big ones). */
    /** A HUD patch: goes through the HUD's own scale, so the status bar and the fullscreen
     * readouts follow the HUD scale setting. */
    private static void patch(GuiGraphicsExtractor g, String lump,
                              double canvasX, double canvasY, int guiW, int guiH) {
        patchAt(g, lump, canvasX, canvasY, guiW, guiH, hudXs(guiH), hudYs(guiH));
    }

    /** A patch at the plain canvas mapping, for callers outside the HUD (menu art). */
    private static void patchPlain(GuiGraphicsExtractor g, String lump,
                                   double canvasX, double canvasY, int guiW, int guiH) {
        patchAt(g, lump, canvasX, canvasY, guiW, guiH,
            guiH * (4.0 / 3.0) / 320.0, guiH / 200.0);
    }

    private static void patchAt(GuiGraphicsExtractor g, String lump, double canvasX,
                                double canvasY, int guiW, int guiH, double xs, double ys) {
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
            return; // -1 = none; 0 = SPR_TROO, which S_NULL wears — the "floating imp"
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
        // R_DrawPSprite's view-height math: with the status bar the 3D view is 168 tall
        // and centers at 84, so psprites sit 16.5 rows above a naive 200-line placement;
        // fullscreen the view is the whole 200 and the naive placement IS the right one.
        // That 16.5 is exactly the vanilla difference between the two views — the gun
        // shows more of itself at larger screen sizes and never moves anywhere else.
        final double statusBarLift =
            com.blackwithersteve.lattedoom.LatteDoomClient.hudSize() == 0 ? 16.5 : 0.0;
        final double canvasY = syPx - ofs[1] - statusBarLift;
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
        // route through the sanitizer: bracket-rotation sprite lumps (BBRN[1) must map to
        // the same safe name DoomRuntimeTextures registered
        return IDS.computeIfAbsent(DoomRuntimeTextures.safe(key),
            k -> Identifier.fromNamespaceAndPath("lattedoom", "textures/doom/" + k + ".png"));
    }

    private LatteHud() {}
}
