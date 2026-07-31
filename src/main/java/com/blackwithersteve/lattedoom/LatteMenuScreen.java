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
 * THE DOOM MENU, rendered natively in Minecraft from the WAD's own art — never the
 * engine framebuffer.
 * Vanilla m_menu.c geometry and flow: M_DOOM logo, New Game -> Episode (M_EPI1..5, SIGIL
 * appears automatically when its patch exists) -> Skill (M_JKILL..M_NMARE) -> the world
 * materializes at E?M1 on the chosen difficulty. Read This! pages HELP1/HELP2. Options
 * opens the DOOM Sound Volume screen. Quit ends the DOOM session. Skull cursor blinks,
 * pstop/pistol/swtchn/swtchx sounds fire exactly where 1993 fired them.
 *
 * Outside a level the TITLEPIC backs the menu; inside, the world dims behind it. Load /
 * Save are drawn (authentic layout) but answer with the oof grunt — savegames are a
 * later milestone. Text fallback if the WAD's menu lumps aren't loadable.
 */
public final class LatteMenuScreen extends Screen {

    private static final int SND_PSTOP = data.sounds.sfxenum_t.sfx_pstop.ordinal();
    private static final int SND_PISTOL = data.sounds.sfxenum_t.sfx_pistol.ordinal();
    private static final int SND_SWTCHN = data.sounds.sfxenum_t.sfx_swtchn.ordinal();
    private static final int SND_SWTCHX = data.sounds.sfxenum_t.sfx_swtchx.ordinal();
    private static final int SND_OOF = data.sounds.sfxenum_t.sfx_oof.ordinal();

    private static final int LINEHEIGHT = 16;

    private static final int MAIN = 0, EPISODE = 1, SKILL = 2, READ1 = 3, READ2 = 4,
        OPTIONS = 5, WADS = 6, CRISP = 7, LOADP = 8, SAVEP = 9;

    /** Crispness rows, ported from Crispy Doom (credited in the README). */
    private static final int CR_CROSSHAIR = 0, CR_STATS = 1, CR_BOB = 2, CR_FREELOOK = 3,
        CR_COUNT = 4;
    private int crispOn;

    /** The picker: three columns side by side, a cursor, per-column scroll, and the
     * current selection. LOAD sits below the columns as its own cursor stop. */
    private List<List<String>> wadCols = List.of(List.of(), List.of(), List.of());
    private static final String[] WAD_TITLES = {"WADS", "PATCHES", "DEHACKED"};
    private static final int[] WAD_X = {10, 114, 218};
    private static final int WAD_Y = 44, WAD_STEP = 12, WAD_ROWS = 10;
    private int wadCol;
    private int wadRow; // == the column's size means the LOAD row
    private final int[] wadScrolls = new int[3];
    private String selGame;
    private final java.util.LinkedHashSet<String> selPatches = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> selDehs = new java.util.LinkedHashSet<>();

    private static final int SND_STNMOV = data.sounds.sfxenum_t.sfx_stnmov.ordinal();

    /** Options rows: screen size, light boost, sound volume, the Crispness page. */
    private static final int OPT_SIZE = 0, OPT_LIGHT = 1, OPT_SVOL = 2, OPT_CRISP = 3,
        OPT_COUNT = 4;
    private int optOn;

    // no Quit entry: this is Minecraft, so closing the menu (Esc/M) IS the quit;
    // ending a DOOM session = the finale or leaving the level
    private static final String[] MAIN_ITEMS = {
        "m_ngame", "m_option", "m_loadg", "m_saveg", "m_rdthis"};
    private static final String[] SKILL_ITEMS = {
        "m_jkill", "m_rough", "m_hurt", "m_ultra", "m_nmare"};

    private int menu = MAIN;
    private int mainOn;
    private int slotOn;
    private String[] slotDescs = new String[6];
    private int epiOn;
    private int skillOn = Math.max(0, Math.min(4,
        LatteDoomClient.doomSkill() - 1)); // cursor starts on the persisted difficulty
    private int chosenEpisode = 1;
    private final List<String> episodes = new ArrayList<>();

    public LatteMenuScreen() {
        super(Component.literal("Latte Doom — menu"));
        for (int e = 1; e <= 9; e++) {
            if (LatteHud.hasGfx("m_epi" + e)) {
                episodes.add("m_epi" + e); // SIGIL's M_EPI5 joins by existing
            }
        }
        DoomSfx.play(SND_SWTCHN, false, 0, 0);
        // With no game data there is nothing behind any other page: open ON the picker,
        // the way a source port's launcher asks for an IWAD first.
        if (!LatteDoomClient.hasGameData()) {
            menu = WADS;
            refreshWads();
        }
        // the singleplayer pause is tick-driven in LatteDoomClient, which also covers
        // Minecraft's own Esc menu — one owner, no screen-lifecycle races
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Minecraft keeps rendering; the ENGINE freeze above is the pause
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc is BACK inside the menu, close only from MAIN
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // wherever you stand stays visible behind the menu, dimmed — never a backdrop
        g.fill(0, 0, this.width, this.height, 0xA0000000);
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
                // the fourth entry has no 1993 lump: the picker is this port's own
                LatteHud.drawHudText(g, "WADS", 97,
                    64 + MAIN_ITEMS.length * LINEHEIGHT + 4, guiW, guiH);
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
            case WADS -> {
                drawPicker(g, false, guiW, guiH);
            }
            case LOADP, SAVEP -> drawLoadSave(g, menu == LOADP, guiW, guiH);
            case OPTIONS -> {
                LatteHud.drawGfx(g, "m_optttl", 108, 15, guiW, guiH);
                // vanilla options geometry: items at x=60, sliders on the following line
                LatteHud.drawGfx(g, "m_scrnsz", 60, 48, guiW, guiH);
                thermo(g, 60, 64, hudDot(), guiW, guiH);
                // no 1993 lump exists for this one — the WAD's own small font labels it
                LatteHud.drawHudText(g, "LIGHT BOOST", 60, 84, guiW, guiH);
                thermo(g, 60, 96, LatteDoomClient.lightBoost() * 3 + 1, guiW, guiH);
                LatteHud.drawGfx(g, "m_svol", 60, 116, guiW, guiH);
                LatteHud.drawHudText(g, "CRISPNESS", 60, 136, guiW, guiH);
                final int[] rows = {48, 84, 116, 136};
                skullAt(g, 60 - 32, rows[optOn] - 5, guiW, guiH);
            }
            case CRISP -> {
                LatteHud.drawHudText(g, "CRISPNESS", 124, 24, guiW, guiH);
                final String cross = switch (LatteDoomClient.crosshair()) {
                    case 1 -> "ON";
                    case 2 -> "HEALTH";
                    default -> "OFF";
                };
                final String bob = switch (LatteDoomClient.bobScale()) {
                    case 1 -> "75%";
                    case 2 -> "OFF";
                    default -> "100%";
                };
                LatteHud.drawHudText(g, "CROSSHAIR", 60, 56, guiW, guiH);
                LatteHud.drawHudText(g, cross, 200, 56, guiW, guiH);
                LatteHud.drawHudText(g, "LEVEL STATS", 60, 76, guiW, guiH);
                LatteHud.drawHudText(g, LatteDoomClient.levelStats() ? "ON" : "OFF",
                    200, 76, guiW, guiH);
                LatteHud.drawHudText(g, "WEAPON BOB", 60, 96, guiW, guiH);
                LatteHud.drawHudText(g, bob, 200, 96, guiW, guiH);
                LatteHud.drawHudText(g, "FREE LOOK", 60, 116, guiW, guiH);
                LatteHud.drawHudText(g, LatteDoomClient.freelook() ? "ON" : "OFF",
                    200, 116, guiW, guiH);
                skullAt(g, 60 - 32, 56 + crispOn * 20 - 5, guiW, guiH);
            }
            default -> { }
        }
    }

    /** The screen-size slider's 0-15 knob position for the three interface sizes. */
    private static int hudDot() {
        return LatteDoomClient.hudSize() * 7; // 0, 7, 14
    }

    /** Vanilla M_DrawLoad/M_DrawSave: title at (72,28), six slot troughs at x=80
     * from y=54, the saved names in the HUD font, the skull cursor at x-32. The
     * trough centre pieces go through thermPiece so scale rounding cannot open
     * seams (the thermometer lesson). */
    private void drawLoadSave(GuiGraphicsExtractor g, boolean load, int guiW, int guiH) {
        LatteHud.drawGfx(g, load ? "m_loadg" : "m_saveg", 72, 28, guiW, guiH);
        final double xs = guiH * (4.0 / 3.0) / 320.0;
        final double ys = guiH / 200.0;
        for (int i = 0; i < 6; i++) {
            final int y = 54 + i * LINEHEIGHT;
            thermPiece(g, "m_lsleft", 72, y + 7, xs, ys, guiW, false);
            int xx = 80;
            for (int p = 0; p < 24; p++) {
                thermPiece(g, "m_lscntr", xx, y + 7, xs, ys, guiW, true);
                xx += 8;
            }
            thermPiece(g, "m_lsrght", xx, y + 7, xs, ys, guiW, false);
            final String name = slotDescs[i];
            LatteHud.drawHudText(g, name != null ? name : "EMPTY SLOT", 80, y, guiW, guiH);
        }
        skullAt(g, 80 - 32, 54 - 5 + slotOn * LINEHEIGHT, guiW, guiH);
        // whose saves these are: the loaded WAD set names the folder (GZDoom's rule)
        String set = "SET " + LatteDoomClient.saveSetKey().toUpperCase(java.util.Locale.ROOT);
        if (set.length() > 36) {
            set = set.substring(0, 36);
        }
        LatteHud.drawHudText(g, set, 24, 178, guiW, guiH);
    }

    /** The volume screen's thermometer, same art, same V_DrawPatch math — middle
     * pieces widen to the next pen stop, so scale rounding can never leave the black
     * seams between tiles that a naive per-piece blit shows. */
    private void thermo(GuiGraphicsExtractor g, int x, int y, int dot, int guiW, int guiH) {
        final double xs = guiH * (4.0 / 3.0) / 320.0;
        final double ys = guiH / 200.0;
        int xx = x;
        thermPiece(g, "m_therml", xx, y, xs, ys, guiW, false);
        xx += 8;
        for (int i = 0; i < 8; i++) {
            thermPiece(g, "m_thermm", xx, y, xs, ys, guiW, true);
            xx += 8;
        }
        thermPiece(g, "m_thermr", xx, y, xs, ys, guiW, false);
        thermPiece(g, "m_thermo", x + 8 + Math.max(0, Math.min(15, dot)) * 4, y,
            xs, ys, guiW, false);
    }

    private static void thermPiece(GuiGraphicsExtractor g, String lump, double cx2,
                                   double cy2, double xs, double ys, int guiW,
                                   boolean tile) {
        final String key = "gfx/" + lump;
        final int[] size =
            com.blackwithersteve.lattedoom.render.DoomRuntimeTextures.textureSize(key);
        if (size == null) {
            return;
        }
        final int[] ofs =
            com.blackwithersteve.lattedoom.render.DoomRuntimeTextures.spriteOffset(key);
        if (ofs != null) {
            cx2 -= ofs[0];
            cy2 -= ofs[1];
        }
        final int px = (int) Math.round(guiW / 2.0 + (cx2 - 160.0) * xs);
        final int py = (int) Math.round(cy2 * ys);
        // width = the patch's projected right edge, through the SAME rounding as the
        // left edge — round(size*xs) drifts ±1px against the neighbor's start at
        // fractional gui scales (the windowed-mode vertical seams)
        int w = Math.max(1, (int) Math.round(guiW / 2.0 + (cx2 + size[0] - 160.0) * xs) - px);
        if (tile) {
            final int next = (int) Math.round(guiW / 2.0 + (cx2 + 8 - 160.0) * xs);
            w = Math.max(w, next - px);
        }
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "lattedoom", "textures/doom/gfx/" + lump + ".png"),
            px, py, 0.0f, 0.0f, w, Math.max(1, (int) Math.round(size[1] * ys)),
            size[0], size[1], size[0], size[1]);
    }

    private void skullAt(GuiGraphicsExtractor g, int x, int y, int guiW, int guiH) {
        final boolean first = (System.currentTimeMillis() / 233L) % 2L == 0L;
        LatteHud.drawGfx(g, first ? "m_skull1" : "m_skull2", x, y, guiW, guiH);
    }

    private void skull(GuiGraphicsExtractor g, int x, int y, int item, int guiW, int guiH) {
        final boolean first = (System.currentTimeMillis() / 233L) % 2L == 0L;
        LatteHud.drawGfx(g, first ? "m_skull1" : "m_skull2",
            x - 32, y - 5 + item * LINEHEIGHT, guiW, guiH);
    }

    private void drawTextFallback(GuiGraphicsExtractor g) {
        final int cx = this.width / 2;
        if (menu == WADS) {
            // no WAD, no art: the picker is the one page that must work anyway
            drawPicker(g, true, this.width, this.height);
            return;
        }
        int y = this.height / 2 - 40;
        g.centeredText(this.font, "DOOM", cx, y, 0xFFFF5555);
        final String[] items = {"New Game", "Options", "Load Game", "Save Game",
            "Read This!", "WADs"};
        for (int i = 0; i < items.length; i++) {
            y += 14;
            g.centeredText(this.font, (menu == MAIN && mainOn == i ? "▶ " : "") + items[i],
                cx, y, 0xFFFFFFFF);
        }
    }

    private void refreshWads() {
        wadCols = LatteDoomClient.pickerCategories();
        wadCol = 0;
        wadRow = 0;
        java.util.Arrays.fill(wadScrolls, 0);
        selGame = null;
        selPatches.clear();
        selDehs.clear();
        // whatever is loaded right now starts selected, so LOAD with nothing touched
        // simply re-loads the current set
        final var cfg = LatteDoomClient.configView();
        if (cfg != null && cfg.iwadPath != null) {
            final String cur = cfg.iwadPath.getFileName().toString();
            if (wadCols.get(0).contains(cur)) {
                selGame = cur;
            }
            for (java.nio.file.Path pth : cfg.pwads) {
                final String n = pth.getFileName().toString();
                if (wadCols.get(1).contains(n)) {
                    selPatches.add(n);
                }
            }
            for (java.nio.file.Path pth : cfg.dehs) {
                final String n = pth.getFileName().toString();
                if (wadCols.get(2).contains(n)) {
                    selDehs.add(n);
                }
            }
        }
    }

    /** Canvas x to screen x, the same 4:3 mapping the patch blitter uses. */
    private int sx(double canvasX, int guiW, int guiH) {
        return (int) Math.round(guiW / 2.0 + (canvasX - 160.0) * (guiH * (4.0 / 3.0) / 320.0));
    }

    private int sy(double canvasY, int guiH) {
        return (int) Math.round(canvasY * (guiH / 200.0));
    }

    private boolean selected(int col, String name) {
        return col == 0 ? name.equals(selGame)
            : col == 1 ? selPatches.contains(name) : selDehs.contains(name);
    }

    /** The three columns, the cursor, the selection marks and the LOAD row — in the
     * WAD's small font, or Minecraft's while no WAD is loaded yet. */
    private void drawPicker(GuiGraphicsExtractor g, boolean mcFont, int guiW, int guiH) {
        if (wadCols.get(0).isEmpty() && wadCols.get(1).isEmpty()
            && wadCols.get(2).isEmpty()) {
            if (mcFont) {
                g.centeredText(this.font, "Nothing in config/latte-doom",
                    guiW / 2, sy(90, guiH), 0xFFFFFFFF);
                g.centeredText(this.font, "Put your DOOM WADs there",
                    guiW / 2, sy(104, guiH), 0xFFAAAAAA);
            } else {
                LatteHud.drawHudText(g, "NOTHING IN CONFIG/LATTE-DOOM", 60, 90, guiW, guiH);
                LatteHud.drawHudText(g, "PUT YOUR DOOM WADS THERE", 60, 104, guiW, guiH);
            }
            return;
        }
        for (int c = 0; c < 3; c++) {
            final List<String> rows = wadCols.get(c);
            if (mcFont) {
                g.centeredText(this.font, WAD_TITLES[c], sx(WAD_X[c] + 46, guiW, guiH),
                    sy(28, guiH), 0xFFFF5555);
            } else {
                LatteHud.drawHudText(g, WAD_TITLES[c], WAD_X[c] + 12, 28, guiW, guiH);
            }
            for (int r = 0; r < WAD_ROWS && wadScrolls[c] + r < rows.size(); r++) {
                final int i = wadScrolls[c] + r;
                final String name = rows.get(i);
                // extensions off and a hard cap, so a long name can never reach the
                // next column over
                final int dot = name.lastIndexOf('.');
                String text = dot > 0 ? name.substring(0, dot) : name;
                if (text.length() > 12) {
                    text = text.substring(0, 12);
                }
                final boolean cursor = wadCol == c && wadRow == i;
                final boolean sel = selected(c, name);
                final int y = WAD_Y + r * WAD_STEP;
                // selection and cursor are bars behind the row, not marks inside it —
                // the row text keeps its width, nothing shifts, nothing collides
                if (sel || cursor) {
                    g.fill(sx(WAD_X[c] - 3, guiW, guiH), sy(y - 2, guiH),
                        sx(WAD_X[c] + 97, guiW, guiH), sy(y + 9, guiH),
                        cursor ? (sel ? 0x90A03020 : 0x903030A0) : 0x70806010);
                }
                if (mcFont) {
                    g.centeredText(this.font, text, sx(WAD_X[c] + 46, guiW, guiH),
                        sy(y, guiH),
                        cursor ? 0xFFFF5555 : sel ? 0xFFFFC060 : 0xFFFFFFFF);
                } else {
                    LatteHud.drawHudText(g, text, WAD_X[c], y, guiW, guiH);
                }
            }
            if (wadScrolls[c] + WAD_ROWS < rows.size()) {
                if (mcFont) {
                    g.centeredText(this.font, "...", sx(WAD_X[c] + 44, guiW, guiH),
                        sy(WAD_Y + WAD_ROWS * WAD_STEP, guiH), 0xFF808080);
                } else {
                    LatteHud.drawHudText(g, "...", WAD_X[c],
                        WAD_Y + WAD_ROWS * WAD_STEP, guiW, guiH);
                }
            }
        }
        if (mcFont) {
            g.centeredText(this.font, "WAD SELECTION", guiW / 2, sy(10, guiH), 0xFFFF5555);
        } else {
            LatteHud.drawHudText(g, "WAD SELECTION", 118, 10, guiW, guiH);
        }
        int chosen = (selGame != null ? 1 : 0) + selPatches.size() + selDehs.size();
        final String summary = chosen == 0 ? "NOTHING SELECTED" : chosen + " SELECTED";
        if (mcFont) {
            g.centeredText(this.font, summary, guiW / 2, sy(170, guiH), 0xFFAAAAAA);
        } else {
            LatteHud.drawHudText(g, summary, 134, 170, guiW, guiH);
        }
        final boolean onLoad = wadRow >= wadCols.get(wadCol).size();
        if (onLoad) {
            g.fill(sx(138, guiW, guiH), sy(179, guiH), sx(182, guiW, guiH),
                sy(191, guiH), 0x903030A0);
        }
        if (mcFont) {
            g.centeredText(this.font, "LOAD", guiW / 2, sy(182, guiH),
                onLoad ? 0xFFFF5555 : 0xFFFFFFFF);
        } else {
            LatteHud.drawHudText(g, "LOAD", 150, 182, guiW, guiH);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (LatteDoomClient.isBootKey(event) || event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT) {
            onClose(); // M is open AND close
            return true;
        }
        switch (event.key()) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> move(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> move(1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> activate();
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                if (menu == WADS) {
                    wadColShift(1);
                    return true;
                }
                if (menu == CRISP) {
                    crispAdjust(1);
                    return true;
                }
                if (menu == OPTIONS && adjust(1)) {
                    return true;
                }
                activate();
            }
            case GLFW.GLFW_KEY_ESCAPE -> { // Esc simply closes
                DoomSfx.play(SND_SWTCHX, false, 0, 0);
                onClose();
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_BACKSPACE -> {
                if (menu == WADS && event.key() != GLFW.GLFW_KEY_BACKSPACE) {
                    wadColShift(-1);
                    return true;
                }
                if (menu == CRISP && event.key() != GLFW.GLFW_KEY_BACKSPACE) {
                    crispAdjust(-1);
                    return true;
                }
                if (menu == OPTIONS && adjust(-1)) {
                    return true;
                }
                back();
            }
            default -> { }
        }
        return true;
    }

    /** Left/right on a Crispness row: every row cycles through its values. */
    private void crispAdjust(int d) {
        DoomSfx.play(SND_STNMOV, false, 0, 0);
        switch (crispOn) {
            case CR_CROSSHAIR -> LatteDoomClient.setCrosshair(
                Math.floorMod(LatteDoomClient.crosshair() + d, 3));
            case CR_STATS -> LatteDoomClient.setLevelStats(!LatteDoomClient.levelStats());
            case CR_BOB -> LatteDoomClient.setBobScale(
                Math.floorMod(LatteDoomClient.bobScale() + d, 3));
            default -> LatteDoomClient.setFreelook(!LatteDoomClient.freelook());
        }
    }

    /** Left/right on an options slider row. True when the key was a slider adjustment. */
    private boolean adjust(int d) {
        switch (optOn) {
            case OPT_SIZE -> {
                final int now = LatteDoomClient.hudSize();
                final int next = Math.max(0, Math.min(2, now + d));
                if (next != now) {
                    LatteDoomClient.setHudSize(next);
                    DoomSfx.play(SND_STNMOV, false, 0, 0);
                }
                return true;
            }
            case OPT_LIGHT -> {
                final int now = LatteDoomClient.lightBoost();
                final int next = Math.max(0, Math.min(4, now + d));
                if (next != now) {
                    LatteDoomClient.setLightBoost(next);
                    DoomSfx.play(SND_STNMOV, false, 0, 0);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void move(int d) {
        DoomSfx.play(SND_PSTOP, false, 0, 0);
        switch (menu) {
            case MAIN -> mainOn = Math.floorMod(mainOn + d, MAIN_ITEMS.length + 1);
            case WADS -> {
                final int size = wadCols.get(wadCol).size();
                wadRow = Math.floorMod(wadRow + d, size + 1); // the extra stop is LOAD
                if (wadRow < size) {
                    if (wadRow < wadScrolls[wadCol]) {
                        wadScrolls[wadCol] = wadRow;
                    }
                    if (wadRow >= wadScrolls[wadCol] + WAD_ROWS) {
                        wadScrolls[wadCol] = wadRow - WAD_ROWS + 1;
                    }
                }
            }
            case EPISODE -> epiOn = Math.floorMod(epiOn + d, Math.max(1, episodes.size()));
            case SKILL -> skillOn = Math.floorMod(skillOn + d, SKILL_ITEMS.length);
            case OPTIONS -> optOn = Math.floorMod(optOn + d, OPT_COUNT);
            case CRISP -> crispOn = Math.floorMod(crispOn + d, CR_COUNT);
            case LOADP, SAVEP -> slotOn = Math.floorMod(slotOn + d, 6);
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
                        menu = com.blackwithersteve.lattedoom.render.LatteWorld.hasEpisodes()
                            && episodes.size() > 1 ? EPISODE : SKILL;
                        chosenEpisode = 1;
                    }
                    case 1 -> { // Options
                        DoomSfx.play(SND_SWTCHN, false, 0, 0);
                        menu = OPTIONS;
                        optOn = 0;
                    }
                    case 2 -> { // Load Game — the current WAD set's slots
                        DoomSfx.play(SND_SWTCHN, false, 0, 0);
                        slotDescs = LatteDoomClient.saveSlots();
                        slotOn = 0;
                        menu = LOADP;
                    }
                    case 3 -> { // Save Game — needs to be standing in a level
                        if (!LatteDoomClient.canSave()) {
                            DoomSfx.play(SND_OOF, false, 0, 0);
                        } else {
                            DoomSfx.play(SND_SWTCHN, false, 0, 0);
                            slotDescs = LatteDoomClient.saveSlots();
                            slotOn = 0;
                            menu = SAVEP;
                        }
                    }
                    case 4 -> { // Read This!
                        DoomSfx.play(SND_SWTCHN, false, 0, 0);
                        menu = READ1;
                    }
                    case 5 -> { // WADs — the in-menu picker
                        DoomSfx.play(SND_SWTCHN, false, 0, 0);
                        menu = WADS;
                        refreshWads();
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
                // the 1993 contract: pick the skill and the world IS the game
                DoomSfx.play(SND_PISTOL, false, 0, 0);
                LatteDoomClient.startNewGame(chosenEpisode, skillOn + 1);
                onClose();
            }
            case READ1 -> menu = READ2;
            case READ2 -> menu = MAIN;
            case OPTIONS -> {
                if (optOn == OPT_SVOL) {
                    DoomSfx.play(SND_SWTCHN, false, 0, 0);
                    LatteDoomClient.openVolume(Minecraft.getInstance());
                } else if (optOn == OPT_CRISP) {
                    DoomSfx.play(SND_SWTCHN, false, 0, 0);
                    menu = CRISP;
                    crispOn = 0;
                } else {
                    adjust(1); // enter nudges a slider the way right does
                }
            }
            case CRISP -> crispAdjust(1);
            case LOADP -> {
                // loads through the engine (running: in-place G_LoadGame; cold: a
                // boot straight into the save) and re-delivers like a death restart
                if (LatteDoomClient.loadGame(slotOn)) {
                    DoomSfx.play(SND_PISTOL, false, 0, 0);
                    onClose();
                } else {
                    DoomSfx.play(SND_OOF, false, 0, 0);
                }
            }
            case SAVEP -> {
                // the engine writes on its next unfrozen tic (closing the menu is
                // the unfreeze) and prints its own "game saved." HUD message
                if (LatteDoomClient.saveGame(slotOn)) {
                    DoomSfx.play(SND_PISTOL, false, 0, 0);
                    onClose();
                } else {
                    DoomSfx.play(SND_OOF, false, 0, 0);
                }
            }
            case WADS -> {
                final List<String> rows = wadCols.get(wadCol);
                if (wadRow >= rows.size()) {
                    doWadLoad();
                    return;
                }
                toggleWad(wadCol, rows.get(wadRow));
            }
            default -> { }
        }
    }

    private void wadColShift(int d) {
        DoomSfx.play(SND_PSTOP, false, 0, 0);
        wadCol = Math.floorMod(wadCol + d, 3);
        final int size = wadCols.get(wadCol).size();
        if (wadRow > size) {
            wadRow = size; // land on LOAD if the row does not exist here
        }
    }

    /** Select or unselect one entry. One game at a time: picking a second game simply
     * replaces the first, which is the only combination that could conflict. */
    private void toggleWad(int col, String name) {
        DoomSfx.play(SND_SWTCHN, false, 0, 0);
        switch (col) {
            case 0 -> selGame = name.equals(selGame) ? null : name;
            case 1 -> {
                if (!selPatches.remove(name)) {
                    selPatches.add(name);
                    // a patch without a game selects the game it is for, the same
                    // choice /load would make
                    if (selGame == null) {
                        selGame = LatteDoomClient.suggestedBaseFor(name);
                    }
                }
            }
            default -> {
                if (!selDehs.remove(name)) {
                    selDehs.add(name);
                }
            }
        }
    }

    private void doWadLoad() {
        if (selGame == null && !LatteDoomClient.hasGameData()) {
            DoomSfx.play(SND_OOF, false, 0, 0);
            return; // nothing to stand on: a game must be chosen first
        }
        if (selGame == null && selPatches.isEmpty() && selDehs.isEmpty()) {
            DoomSfx.play(SND_OOF, false, 0, 0);
            return;
        }
        DoomSfx.play(SND_PISTOL, false, 0, 0);
        final var player = Minecraft.getInstance().player;
        LatteDoomClient.loadSelection(
            msg -> { if (player != null) { player.sendSystemMessage(msg); } },
            selGame, selPatches, selDehs);
        // close outright: this screen was built from the OLD wad's art and episode
        // list — the next M press constructs the menu from the freshly loaded set
        onClose();
    }

    /** Screen to canvas, the inverse of the patch blitter's 4:3 mapping. */
    private double[] canvasAt(double mx, double my) {
        final double xs = this.height * (4.0 / 3.0) / 320.0;
        return new double[]{160.0 + (mx - this.width / 2.0) / xs,
            my / (this.height / 200.0)};
    }

    /** The row of the current page under a canvas point, or -1. Pages are lists on
     * known vanilla geometry; rows answer to the full menu width, like the ports. */
    private int rowAt(double cx, double cy) {
        switch (menu) {
            case MAIN -> {
                final int i = (int) Math.floor((cy - 62.0) / LINEHEIGHT);
                return i >= 0 && i <= MAIN_ITEMS.length ? i : -1;
            }
            case EPISODE -> {
                final int i = (int) Math.floor((cy - 61.0) / LINEHEIGHT);
                return i >= 0 && i < episodes.size() ? i : -1;
            }
            case SKILL -> {
                final int i = (int) Math.floor((cy - 61.0) / LINEHEIGHT);
                return i >= 0 && i < SKILL_ITEMS.length ? i : -1;
            }
            case OPTIONS -> {
                if (cy >= 44 && cy < 82) {
                    return OPT_SIZE;
                }
                if (cy >= 82 && cy < 114) {
                    return OPT_LIGHT;
                }
                if (cy >= 114 && cy < 134) {
                    return OPT_SVOL;
                }
                if (cy >= 134 && cy < 154) {
                    return OPT_CRISP;
                }
                return -1;
            }
            case CRISP -> {
                final int i = (int) Math.floor((cy - 52.0) / 20.0);
                return i >= 0 && i < CR_COUNT ? i : -1;
            }
            case LOADP, SAVEP -> {
                final int i = (int) Math.floor((cy - 49.0) / LINEHEIGHT);
                return i >= 0 && i < 6 ? i : -1;
            }
            default -> {
                return -1;
            }
        }
    }

    /** The skull follows the mouse: hovering a row selects it, with the cursor blip. */
    private void hoverTo(int row) {
        if (row < 0) {
            return;
        }
        final int cur = switch (menu) {
            case MAIN -> mainOn;
            case EPISODE -> epiOn;
            case SKILL -> skillOn;
            case OPTIONS -> optOn;
            case CRISP -> crispOn;
            case LOADP, SAVEP -> slotOn;
            default -> -1;
        };
        if (cur == row) {
            return;
        }
        switch (menu) {
            case MAIN -> mainOn = row;
            case EPISODE -> epiOn = row;
            case SKILL -> skillOn = row;
            case OPTIONS -> optOn = row;
            case CRISP -> crispOn = row;
            case LOADP, SAVEP -> slotOn = row;
            default -> { }
        }
        DoomSfx.play(SND_PSTOP, false, 0, 0);
    }

    /** Hover in the picker: the cursor bar tracks the mouse over rows and LOAD. */
    private void hoverWads(double cx, double cy) {
        if (cy >= 176 && cy <= 192 && cx >= 130 && cx <= 190) {
            final int size = wadCols.get(wadCol).size();
            if (wadRow != size) {
                wadRow = size; // the LOAD stop
                DoomSfx.play(SND_PSTOP, false, 0, 0);
            }
            return;
        }
        for (int c = 0; c < 3; c++) {
            if (cx < WAD_X[c] - 10 || cx > WAD_X[c] + 96) {
                continue;
            }
            final int r = (int) Math.floor((cy - WAD_Y + 3) / WAD_STEP);
            final int i = wadScrolls[c] + r;
            if (r >= 0 && r < WAD_ROWS && i < wadCols.get(c).size()
                && (wadCol != c || wadRow != i)) {
                wadCol = c;
                wadRow = i;
                DoomSfx.play(SND_PSTOP, false, 0, 0);
            }
            return;
        }
    }

    /** A press or drag on an options thermometer: the knob goes to the mouse.
     * Dot geometry matches thermo(): knob steps 4 canvas px starting at x+8. */
    private boolean thermSet(double cx, double cy) {
        if (cx < 56 || cx > 148) {
            return false;
        }
        final int dot = (int) Math.max(0, Math.min(15, Math.round((cx - 68.0) / 4.0)));
        if (cy >= 58 && cy <= 80) { // the screen-size strip (thermo at y=64)
            optOn = OPT_SIZE;
            final int next = Math.max(0, Math.min(2, Math.round(dot / 7.0f)));
            if (next != LatteDoomClient.hudSize()) {
                LatteDoomClient.setHudSize(next);
                DoomSfx.play(SND_STNMOV, false, 0, 0);
            }
            return true;
        }
        if (cy >= 90 && cy <= 112) { // the light-boost strip (thermo at y=96)
            optOn = OPT_LIGHT;
            final int next = Math.max(0, Math.min(4, Math.round((dot - 1) / 3.0f)));
            if (next != LatteDoomClient.lightBoost()) {
                LatteDoomClient.setLightBoost(next);
                DoomSfx.play(SND_STNMOV, false, 0, 0);
            }
            return true;
        }
        return false;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        final double[] c = canvasAt(mx, my);
        if (menu == WADS) {
            hoverWads(c[0], c[1]);
        } else {
            hoverTo(rowAt(c[0], c[1]));
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,
                                boolean doubleClick) {
        if (event.button() == 1) { // right button walks back, like Backspace
            back();
            return true;
        }
        final double[] c = canvasAt(event.x(), event.y());
        final double cx = c[0], cy = c[1];
        switch (menu) {
            case READ1, READ2 -> activate();
            case MAIN, EPISODE, SKILL, LOADP, SAVEP -> {
                final int row = rowAt(cx, cy);
                if (row >= 0) {
                    hoverTo(row);
                    activate();
                }
            }
            case OPTIONS -> {
                if (!thermSet(cx, cy)) {
                    final int row = rowAt(cx, cy);
                    if (row >= 0) {
                        optOn = row;
                        if (row == OPT_SVOL || row == OPT_CRISP) {
                            activate();
                        }
                    }
                }
            }
            case CRISP -> {
                final int row = rowAt(cx, cy);
                if (row >= 0) {
                    crispOn = row;
                    crispAdjust(1);
                }
            }
            case WADS -> {
                if (cy >= 176 && cy <= 192 && cx >= 130 && cx <= 190) {
                    doWadLoad();
                    return true;
                }
                for (int c2 = 0; c2 < 3; c2++) {
                    if (cx < WAD_X[c2] - 10 || cx > WAD_X[c2] + 96) {
                        continue;
                    }
                    final int r = (int) Math.floor((cy - WAD_Y + 3) / WAD_STEP);
                    final int i = wadScrolls[c2] + r;
                    if (r >= 0 && r < WAD_ROWS && i < wadCols.get(c2).size()) {
                        wadCol = c2;
                        wadRow = i;
                        toggleWad(c2, wadCols.get(c2).get(i));
                        return true;
                    }
                }
            }
            default -> { }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,
                                double dragX, double dragY) {
        if (menu == OPTIONS) {
            final double[] c = canvasAt(event.x(), event.y());
            thermSet(c[0], c[1]);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        final double[] c = canvasAt(mx, my);
        if (menu == WADS) {
            // the wheel scrolls the column under the mouse
            int col = wadCol;
            for (int c2 = 0; c2 < 3; c2++) {
                if (c[0] >= WAD_X[c2] - 10 && c[0] <= WAD_X[c2] + 96) {
                    col = c2;
                }
            }
            final int max = Math.max(0, wadCols.get(col).size() - WAD_ROWS);
            wadScrolls[col] = Math.max(0, Math.min(max,
                wadScrolls[col] - (int) Math.signum(scrollY)));
            return true;
        }
        if (menu == OPTIONS || menu == CRISP) {
            // the wheel adjusts the hovered row like left/right
            final int row = rowAt(c[0], c[1]);
            if (row >= 0) {
                if (menu == OPTIONS) {
                    optOn = row;
                    adjust(scrollY > 0 ? 1 : -1);
                } else {
                    crispOn = row;
                    crispAdjust(scrollY > 0 ? 1 : -1);
                }
                return true;
            }
        }
        move(scrollY > 0 ? -1 : 1); // elsewhere the wheel walks the cursor
        return true;
    }

    private void back() {
        DoomSfx.play(SND_SWTCHX, false, 0, 0);
        switch (menu) {
            case MAIN -> onClose();
            case EPISODE, READ1, OPTIONS, WADS, LOADP, SAVEP -> menu = MAIN;
            case CRISP -> menu = OPTIONS;
            case SKILL -> menu = episodes.size() > 1 ? EPISODE : MAIN;
            case READ2 -> menu = READ1;
            default -> { }
        }
    }
}
