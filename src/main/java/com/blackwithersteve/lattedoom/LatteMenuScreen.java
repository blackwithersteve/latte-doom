package com.blackwithersteve.lattedoom;

import com.blackwithersteve.lattedoom.render.DoomSfx;
import com.blackwithersteve.lattedoom.render.LatteHud;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Main menu, drawn from the WAD's own art with the geometry and flow of {@code m_menu.c}:
 * New Game to episode to skill, then the level is raised. Episodes appear for whichever
 * {@code M_EPIn} patches the loaded WAD provides, so a patch WAD's extra episode shows up
 * on its own. Load and Save are absent until savegames exist. Falls back to plain text if
 * the menu lumps cannot be loaded.
 */
public final class LatteMenuScreen extends Screen {

    private static final int SND_PSTOP = data.sounds.sfxenum_t.sfx_pstop.ordinal();
    private static final int SND_PISTOL = data.sounds.sfxenum_t.sfx_pistol.ordinal();
    private static final int SND_SWTCHN = data.sounds.sfxenum_t.sfx_swtchn.ordinal();
    private static final int SND_SWTCHX = data.sounds.sfxenum_t.sfx_swtchx.ordinal();
    private static final int SND_OOF = data.sounds.sfxenum_t.sfx_oof.ordinal();

    private static final int LINEHEIGHT = 16;

    private static final int MAIN = 0, EPISODE = 1, SKILL = 2, READ1 = 3, READ2 = 4;

    // There is no Quit entry: closing the menu returns to Minecraft, and a play session
    // ends either at the episode finale or by leaving marine form. Load and Save are
    // omitted until savegames are implemented.
    private static final String[] MAIN_ITEMS = {
        "m_ngame", "m_option", "m_rdthis"};
    private static final String[] SKILL_ITEMS = {
        "m_jkill", "m_rough", "m_hurt", "m_ultra", "m_nmare"};

    private int menu = MAIN;
    private int mainOn;
    private int epiOn;
    private int skillOn = Math.max(0, Math.min(4,
        LatteDoomClient.doomSkill() - 1)); // cursor starts on the persisted difficulty
    private int chosenEpisode = 1;
    private final List<String> episodes = new ArrayList<>();

    public LatteMenuScreen() {
        super(Component.literal("Latte Doom: menu"));
        for (int e = 1; e <= 9; e++) {
            if (LatteHud.hasGfx("m_epi" + e)) {
                episodes.add("m_epi" + e); // an extra episode appears if its patch exists
            }
        }
        DoomSfx.play(SND_SWTCHN, false, 0, 0);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the engine (and the world) keep running under the menu
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc goes back a page; only the main page closes the screen
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (LatteWorld.warpedIn() && LatteWorld.map() != null) {
            g.fill(0, 0, this.width, this.height, 0xA0000000); // in-level: dim the world
        } else {
            g.fill(0, 0, this.width, this.height, 0xFF000000);
            LatteHud.drawGfx(g, "titlepic", 0, 0, this.width, this.height);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        final int guiW = this.width, guiH = this.height;
        if (!LatteHud.hasGfx("m_doom")) {
            drawTextFallback(g);
            return;
        }
        switch (menu) {
            case MAIN -> {
                LatteHud.drawGfx(g, "m_doom", 94, 2, guiW, guiH);
                for (int i = 0; i < MAIN_ITEMS.length; i++) {
                    LatteHud.drawGfx(g, MAIN_ITEMS[i], 97, 64 + i * LINEHEIGHT, guiW, guiH);
                }
                skull(g, 97, 64, mainOn, guiW, guiH);
            }
            case EPISODE -> {
                LatteHud.drawGfx(g, "m_episod", 54, 38, guiW, guiH);
                for (int i = 0; i < episodes.size(); i++) {
                    LatteHud.drawGfx(g, episodes.get(i), 48, 63 + i * LINEHEIGHT, guiW, guiH);
                }
                skull(g, 48, 63, epiOn, guiW, guiH);
            }
            case SKILL -> {
                LatteHud.drawGfx(g, "m_newg", 96, 14, guiW, guiH);
                LatteHud.drawGfx(g, "m_skill", 54, 38, guiW, guiH);
                for (int i = 0; i < SKILL_ITEMS.length; i++) {
                    LatteHud.drawGfx(g, SKILL_ITEMS[i], 48, 63 + i * LINEHEIGHT, guiW, guiH);
                }
                skull(g, 48, 63, skillOn, guiW, guiH);
            }
            case READ1 -> LatteHud.drawGfx(g, LatteHud.hasGfx("help1") ? "help1" : "credit",
                0, 0, guiW, guiH);
            case READ2 -> LatteHud.drawGfx(g, LatteHud.hasGfx("help2") ? "help2" : "credit",
                0, 0, guiW, guiH);
            default -> { }
        }
    }

    private void skull(GuiGraphicsExtractor g, int x, int y, int item, int guiW, int guiH) {
        final boolean first = (System.currentTimeMillis() / 233L) % 2L == 0L;
        LatteHud.drawGfx(g, first ? "m_skull1" : "m_skull2",
            x - 32, y - 5 + item * LINEHEIGHT, guiW, guiH);
    }

    private void drawTextFallback(GuiGraphicsExtractor g) {
        final int cx = this.width / 2;
        int y = this.height / 2 - 40;
        g.centeredText(this.font, "DOOM", cx, y, 0xFFFF5555);
        final String[] items = {"New Game", "Options", "Read This!"};
        for (int i = 0; i < items.length; i++) {
            y += 14;
            g.centeredText(this.font, (menu == MAIN && mainOn == i ? "▶ " : "") + items[i],
                cx, y, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (LatteDoomClient.isBootKey(event) || event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT) {
            onClose(); // the menu key both opens and closes the screen
            return true;
        }
        switch (event.key()) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> move(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> move(1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE,
                 GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> activate();
            case GLFW.GLFW_KEY_ESCAPE -> { // Escape closes the menu
                DoomSfx.play(SND_SWTCHX, false, 0, 0);
                onClose();
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_BACKSPACE -> back();
            default -> { }
        }
        return true;
    }

    private void move(int d) {
        DoomSfx.play(SND_PSTOP, false, 0, 0);
        switch (menu) {
            case MAIN -> mainOn = Math.floorMod(mainOn + d, MAIN_ITEMS.length);
            case EPISODE -> epiOn = Math.floorMod(epiOn + d, Math.max(1, episodes.size()));
            case SKILL -> skillOn = Math.floorMod(skillOn + d, SKILL_ITEMS.length);
            case READ1, READ2 -> { }
            default -> { }
        }
    }

    private void activate() {
        switch (menu) {
            case MAIN -> {
                switch (mainOn) {
                    case 0 -> { // New Game
                        DoomSfx.play(SND_SWTCHN, false, 0, 0);
                        menu = episodes.size() > 1 ? EPISODE : SKILL;
                        chosenEpisode = 1;
                    }
                    case 1 -> { // Options -> the DOOM Sound Volume screen
                        DoomSfx.play(SND_SWTCHN, false, 0, 0);
                        LatteDoomClient.openVolume(Minecraft.getInstance());
                    }
                    case 2 -> { // Read This!
                        DoomSfx.play(SND_SWTCHN, false, 0, 0);
                        menu = READ1;
                    }
                    default -> { }
                }
            }
            case EPISODE -> {
                DoomSfx.play(SND_SWTCHN, false, 0, 0);
                chosenEpisode = epiOn + 1;
                menu = SKILL;
            }
            case SKILL -> {
                // Choosing a skill starts the game immediately, as in the original.
                DoomSfx.play(SND_PISTOL, false, 0, 0);
                LatteDoomClient.startNewGame(chosenEpisode, skillOn + 1);
                onClose();
            }
            case READ1 -> menu = READ2;
            case READ2 -> menu = MAIN;
            default -> { }
        }
    }

    private void back() {
        DoomSfx.play(SND_SWTCHX, false, 0, 0);
        switch (menu) {
            case MAIN -> onClose();
            case EPISODE, READ1 -> menu = MAIN;
            case SKILL -> menu = episodes.size() > 1 ? EPISODE : MAIN;
            case READ2 -> menu = READ1;
            default -> { }
        }
    }
}
