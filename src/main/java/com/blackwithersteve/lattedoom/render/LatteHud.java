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
                gunShade = LatteMesh.shadeByte((lc >> 4) * 255 / 15);
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
        // the engine's heads-up line, top left in the small font, ~4 seconds like HU
        if (snap.message != null && !snap.message.equals(lastMessage)) {
            lastMessage = snap.message;
            messageUntil = System.currentTimeMillis() + 4000;
        }
        if (lastMessage != null && System.currentTimeMillis() < messageUntil && !automap) {
            drawHudText(g, lastMessage.toUpperCase(java.util.Locale.ROOT),
                edgeLeft(guiW, guiH) + 2, 2, guiW, guiH);
        }
        if (!automap && !dead) {
            crosshair(g, mc, guiW, guiH);
        }
        if (com.blackwithersteve.lattedoom.LatteDoomClient.levelStats()
            && LatteWorld.map() != null) {
            statsWidget(g, snap, guiW, guiH);
        }
        flashes(g, snap, guiW, guiH);
    }

    /** Crispy Doom's crosshair: a small cross at the view center, optionally colored by
     * health the way Crispy colors it (green, then yellow under 2/3, red under 1/3). */
    private static void crosshair(GuiGraphicsExtractor g, Minecraft mc, int guiW, int guiH) {
        final int mode = com.blackwithersteve.lattedoom.LatteDoomClient.crosshair();
        if (mode == 0 || mc.player == null) {
            return;
        }
        int color = 0xFFDADADA;
        if (mode == 2) {
            final int health = (int) Math.ceil(mc.player.getHealth() * 5.0f);
            color = health > 66 ? 0xFF40C040 : health > 33 ? 0xFFD0C020 : 0xFFD03030;
        }
        final int cx = guiW / 2, cy = guiH / 2;
        g.fill(cx - 3, cy, cx + 4, cy + 1, color);
        g.fill(cx, cy - 3, cx + 1, cy + 4, color);
    }

    /** Crispy Doom's level stats: kills, items, secrets and the level time, top left,
     * in the WAD's small font. */
    private static void statsWidget(GuiGraphicsExtractor g, WorldSnapshot snap,
                                    int guiW, int guiH) {
        final double left = edgeLeft(guiW, guiH);
        drawHudText(g, String.format("K %d/%d  I %d/%d  S %d/%d",
            snap.killCount, snap.totalKills, snap.itemCount, snap.totalItems,
            snap.secretCount, snap.totalSecrets), left + 2, 12, guiW, guiH);
        final int seconds = snap.levelTime / 35;
        drawHudText(g, String.format("TIME %d:%02d", seconds / 60, seconds % 60),
            left + 2, 22, guiW, guiH);
    }

    /**
     * The simplified fullscreen interface source ports show at larger screen sizes:
     * health at the left edge, the ready weapon's ammo at the right, owned keys stacked
     * above it — all from the status bar's own art, nothing modern.
     */
    private static void minimalHud(GuiGraphicsExtractor g, WorldSnapshot snap,
                                   SpriteSet sprites, Minecraft mc, int guiW, int guiH) {
        // widescreen: the readouts hug the TRUE screen edges, the way widescreen source
        // ports place their fullscreen huds; on a 4:3 window the edges are 0 and 320 and
        // nothing changes. Room for three digits everywhere, so 200% cannot reach an icon.
        final double left = edgeLeft(guiW, guiH);
        final double right = edgeRight(guiW, guiH);
        final int health = Math.min(200, (int) Math.ceil(mc.player.getHealth() * 5.0f));
        // the medikit in front of health and the armor in front of armor, so the two
        // numbers read at a glance — the WAD's own pickup sprites, scaled to the digits
        icon(g, sprites, "MEDI", left + 2, 196, 13, guiW, guiH);
        numRight(g, "sttnum", health, left + 74, 184, guiW, guiH);
        patch(g, "sttprcnt", left + 74, 184, guiW, guiH);
        icon(g, sprites, snap.armorType == 2 ? "ARM2" : "ARM1", left + 90, 196, 13,
            guiW, guiH);
        numRight(g, "sttnum", snap.armor, left + 162, 184, guiW, guiH);
        patch(g, "sttprcnt", left + 162, 184, guiW, guiH);
        if (snap.readyAmmoType >= 0 && snap.readyAmmoType < 4 && snap.ammo != null) {
            numRight(g, "sttnum", snap.ammo[snap.readyAmmoType], right - 4, 184,
                guiW, guiH);
        }
        // keys in fixed slots above the ammo, blue/yellow/red top to bottom; a skull
        // draws over its card the way the status bar's slots resolve them
        if (snap.cards != null) {
            for (int k = 0; k < 3; k++) {
                final int y = 150 + k * 10;
                if (snap.cards[k]) {
                    patch(g, "stkeys" + k, right - 12, y, guiW, guiH);
                }
                if (snap.cards[k + 3]) {
                    patch(g, "stkeys" + (k + 3), right - 12, y, guiW, guiH);
                }
            }
        }
    }

    /** The true left screen edge in canvas units (0 on a 4:3 window, negative on wider). */
    private static double edgeLeft(int guiW, int guiH) {
        final double xs = guiH * (4.0 / 3.0) / 320.0;
        return 160.0 - guiW / (2.0 * xs);
    }

    private static double edgeRight(int guiW, int guiH) {
        final double xs = guiH * (4.0 / 3.0) / 320.0;
        return 160.0 + guiW / (2.0 * xs);
    }

    private static double frozenBobX, frozenBobY;
    private static double smoothBobAmp;
    private static long lastBobNanos;
    private static String lastMessage;
    private static long messageUntil;

    /** A world sprite as a small HUD icon: bottom-left anchored at canvas coords,
     * scaled to the given height. */
    private static void icon(GuiGraphicsExtractor g, SpriteSet sprites, String sprName,
                             double canvasX, double canvasBottomY, double targetH,
                             int guiW, int guiH) {
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
        final double xs = guiH * (4.0 / 3.0) / 320.0;
        final double ys = guiH / 200.0;
        final int x = (int) Math.round(guiW / 2.0 + (canvasX - 160.0) * xs);
        final int y = (int) Math.round((canvasBottomY - targetH) * ys);
        g.blit(RenderPipelines.GUI_TEXTURED, idOf(key), x, y, 0.0f, 0.0f,
            (int) Math.round(size[0] * f * xs), (int) Math.round(targetH * ys),
            size[0], size[1], size[0], size[1]);
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
        }
    }

    /** Public draw of a registered DOOM menu/status patch ("gfx/&lt;lump&gt;") at 320x200 canvas
     * coords, V_DrawPatch semantics, 4:3-centered — for DOOM-styled overlays like the volume
     * menu. Silently no-ops if the lump wasn't in the WAD. */
    public static void drawGfx(GuiGraphicsExtractor g, String lump,
                               double canvasX, double canvasY, int guiW, int guiH) {
        patch(g, lump, canvasX, canvasY, guiW, guiH);
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
            patch(g, lump, x, canvasY, guiW, guiH);
            x += size[0] + 1;
        }
    }

    /** Blit a status graphic at classic 320x200 canvas coords, V_DrawPatch semantics
     * (the patch's own left/top offsets are subtracted — the face lumps carry big ones). */
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
