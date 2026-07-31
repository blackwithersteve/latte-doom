package com.blackwithersteve.lattedoom;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.blackwithersteve.lattedoom.render.DoomRuntimeTextures;
import com.blackwithersteve.lattedoom.render.LatteHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * THE FINALE, rendered natively in Minecraft: the episode-end text typewritten in the
 * WAD's own STCFN small font over the episode's flat (f_finale.c: FLOOR4_8 / SFLR6_1 /
 * MFLR8_4 / MFLR8_3, 1 char per 3 tics), then the end art (CREDIT / VICTORY2 / PFUB1 /
 * ENDPIC). A press skips the typewriter; when the art is up, a press (or M/grave) ends
 * the adventure — back to Minecraft, the engine returns to its title. The finale MUSIC
 * (D_VICTOR) keeps playing from the engine underneath, untouched.
 */
public final class LatteFinaleScreen extends Screen {

    private static final String[] FLATS = {"floor4_8", "sflr6_1", "mflr8_4", "mflr8_3"};
    private static final String[] ART = {"credit", "victory2", "pfub1", "endpic"};

    private final DoomHost host;
    private final String text;
    private final String flat;
    private final String art;
    private int ticks;
    private boolean artStage;

    public LatteFinaleScreen(DoomHost host) {
        super(Component.literal("Latte Doom — finale"));
        this.host = host;
        final int ep = Math.min(4, host != null ? host.episodeNow() : 1);
        this.flat = FLATS[(ep - 1) % FLATS.length];
        this.art = ART[(ep - 1) % ART.length];
        this.text = switch (ep) {
            case 2 -> doom.englsh.E2TEXT;
            case 3 -> doom.englsh.E3TEXT;
            case 4 -> doom.englsh.E4TEXT;
            default -> doom.englsh.E1TEXT;
        };
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
        final int gsk = host != null ? host.gamestateKind() : -1;
        if (gsk == 0) {
            onClose(); // a new level went live under us (shouldn't normally happen)
        }
        // vanilla auto-advance: text done + a wait -> the art stage (engine does the same)
        if (!artStage && shownChars() >= text.length() && ticks > text.length() * 12 / 7 + 140) {
            artStage = true;
        }
    }

    private int shownChars() {
        // 1 char per 3 doom tics = 35/3 chars/s ≈ 7 chars per 12 client ticks
        return Math.min(text.length(), ticks * 7 / 12);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xFF000000);
        if (artStage) {
            if (!LatteHud.hasGfx(art)) {
                return;
            }
            LatteHud.drawGfx(g, art, 0, 0, this.width, this.height);
            return;
        }
        // the episode flat, tiled across the 320x200 canvas at 4:3 like V_DrawPatch space
        final int[] size = DoomRuntimeTextures.textureSize("flats/" + flat);
        if (size == null) {
            return;
        }
        final Identifier id = Identifier.fromNamespaceAndPath("lattedoom",
            "textures/doom/flats/" + flat + ".png");
        final double xs = this.height * (4.0 / 3.0) / 320.0;
        final double ys = this.height / 200.0;
        final int x0 = (int) Math.round(this.width / 2.0 - 160.0 * xs);
        for (int ty = 0; ty < 200; ty += 64) {
            for (int tx = 0; tx < 320; tx += 64) {
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id,
                    x0 + (int) Math.round(tx * xs), (int) Math.round(ty * ys), 0.0f, 0.0f,
                    (int) Math.round(64 * xs), (int) Math.round(64 * ys), 64, 64, 64, 64);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (artStage) {
            return;
        }
        // F_TextWrite: STCFN font, canvas start (10,10), 11px line step
        double x = 10, y = 10;
        final int shown = shownChars();
        for (int i = 0; i < shown; i++) {
            final char c = Character.toUpperCase(text.charAt(i));
            if (c == '\n') {
                x = 10;
                y += 11;
                continue;
            }
            if (c < 33 || c > 95) {
                x += 4;
                continue;
            }
            final String lump = String.format("stcfn%03d", (int) c);
            final int[] size = DoomRuntimeTextures.textureSize("gfx/" + lump);
            if (size == null) {
                x += 4;
                continue;
            }
            LatteHud.drawGfx(g, lump, x, y, this.width, this.height);
            x += size[0];
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT || LatteDoomClient.isBootKey(event)) {
            endAdventure();
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
        if (!artStage && shownChars() < text.length()) {
            ticks = text.length() * 12 / 7 + 1; // skip the typewriter
        } else if (!artStage) {
            artStage = true;
        } else {
            endAdventure();
        }
    }

    /** The episode is over: back to Minecraft (the DOOM session ends, like Quit). */
    private void endAdventure() {
        if (com.blackwithersteve.lattedoom.render.LatteWorld.warpedIn()) {
            // out of the void dimension FIRST — quitting must never strand you in it
            com.blackwithersteve.lattedoom.render.LatteWorld.leaveLevelDim(
                net.minecraft.client.Minecraft.getInstance());
        }
        LatteDoomClient.resetOnDisconnect();
        onClose();
    }
}
