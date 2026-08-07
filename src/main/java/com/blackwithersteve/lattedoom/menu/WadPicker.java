package com.blackwithersteve.lattedoom.menu;

import com.blackwithersteve.lattedoom.LatteDoomClient;
import com.blackwithersteve.lattedoom.render.DoomSfx;
import com.blackwithersteve.lattedoom.render.LatteHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The WAD picker: three columns left to right, WADS | PATCHES | DEHACKED. Arrows move,
 * left and right switch columns, Enter toggles a selection, and Down past the last row
 * lands on LOAD. The mouse does all of it too.
 *
 * A {@link MenuBody} rather than a list of rows, because selection here is multiple and
 * sticky rather than a cursor choosing one thing. It renders in the WAD's small font, or
 * Minecraft's before any WAD is loaded, which is the case where this page is the whole
 * menu.
 *
 * Selections are re-validated at load time by LatteDoomClient.loadSelection; the display
 * lists are not an authority.
 */
public final class WadPicker implements MenuBody {

    private static final int SND_PSTOP = data.sounds.sfxenum_t.sfx_pstop.ordinal();
    private static final int SND_SWTCHN = data.sounds.sfxenum_t.sfx_swtchn.ordinal();
    private static final int SND_PISTOL = data.sounds.sfxenum_t.sfx_pistol.ordinal();
    private static final int SND_OOF = data.sounds.sfxenum_t.sfx_oof.ordinal();

    private static final String[] TITLES = {"WADS", "PATCHES", "DEHACKED"};
    private static final int[] COL_X = {10, 114, 218};
    private static final int TOP = 44, STEP = 12, ROWS = 10;

    private List<List<String>> cols = List.of(List.of(), List.of(), List.of());
    private final int[] scrolls = new int[3];
    private int col;
    private int row; // == the column's size means the LOAD row
    private String game;
    private final LinkedHashSet<String> patches = new LinkedHashSet<>();
    private final LinkedHashSet<String> dehs = new LinkedHashSet<>();

    public WadPicker() {
        cols = LatteDoomClient.pickerCategories();
        // whatever is loaded starts selected, so LOAD with nothing touched re-loads it
        final var cfg = LatteDoomClient.configView();
        if (cfg != null && cfg.iwadPath != null) {
            final String cur = cfg.iwadPath.getFileName().toString();
            if (cols.get(0).contains(cur)) {
                game = cur;
            }
            for (java.nio.file.Path p : cfg.pwads) {
                final String n = p.getFileName().toString();
                if (cols.get(1).contains(n)) {
                    patches.add(n);
                }
            }
            for (java.nio.file.Path p : cfg.dehs) {
                final String n = p.getFileName().toString();
                if (cols.get(2).contains(n)) {
                    dehs.add(n);
                }
            }
        }
    }

    private boolean isSelected(int c, String name) {
        return c == 0 ? name.equals(game) : c == 1 ? patches.contains(name) : dehs.contains(name);
    }

    private boolean empty() {
        return cols.get(0).isEmpty() && cols.get(1).isEmpty() && cols.get(2).isEmpty();
    }

    @Override
    public void draw(GuiGraphicsExtractor g, Font font, int guiW, int guiH) {
        final boolean mcFont = !LatteHud.hasGfx("stcfn065");
        if (empty()) {
            if (mcFont) {
                g.centeredText(font, "Nothing in config/latte-doom",
                    guiW / 2, MenuGeom.screenY(guiH, 90), 0xFFFFFFFF);
                g.centeredText(font, "Put your DOOM WADs there",
                    guiW / 2, MenuGeom.screenY(guiH, 104), 0xFFAAAAAA);
            } else {
                LatteHud.drawHudText(g, "NOTHING IN CONFIG/LATTE-DOOM", 60, 90, guiW, guiH);
                LatteHud.drawHudText(g, "PUT YOUR DOOM WADS THERE", 60, 104, guiW, guiH);
            }
            return;
        }
        for (int c = 0; c < 3; c++) {
            final List<String> rows = cols.get(c);
            if (mcFont) {
                g.centeredText(font, TITLES[c], MenuGeom.screenX(guiW, guiH, COL_X[c] + 46),
                    MenuGeom.screenY(guiH, 28), 0xFFFF5555);
            } else {
                LatteHud.drawHudText(g, TITLES[c], COL_X[c] + 12, 28, guiW, guiH);
            }
            for (int r = 0; r < ROWS && scrolls[c] + r < rows.size(); r++) {
                final int i = scrolls[c] + r;
                final String name = rows.get(i);
                // extensions off and a hard cap, so a long name can never reach the
                // next column over
                final int dot = name.lastIndexOf('.');
                String text = dot > 0 ? name.substring(0, dot) : name;
                if (text.length() > 12) {
                    text = text.substring(0, 12);
                }
                final boolean cursor = col == c && row == i;
                final boolean sel = isSelected(c, name);
                final int y = TOP + r * STEP;
                // bars behind the row rather than marks inside it, so the text keeps its
                // width and nothing shifts or collides
                if (sel || cursor) {
                    g.fill(MenuGeom.screenX(guiW, guiH, COL_X[c] - 3),
                        MenuGeom.screenY(guiH, y - 2),
                        MenuGeom.screenX(guiW, guiH, COL_X[c] + 97),
                        MenuGeom.screenY(guiH, y + 9),
                        cursor ? (sel ? 0x90A03020 : 0x903030A0) : 0x70806010);
                }
                if (mcFont) {
                    g.centeredText(font, text, MenuGeom.screenX(guiW, guiH, COL_X[c] + 46),
                        MenuGeom.screenY(guiH, y),
                        cursor ? 0xFFFF5555 : sel ? 0xFFFFC060 : 0xFFFFFFFF);
                } else {
                    LatteHud.drawHudText(g, text, COL_X[c], y, guiW, guiH);
                }
            }
            if (scrolls[c] + ROWS < rows.size()) {
                if (mcFont) {
                    g.centeredText(font, "...", MenuGeom.screenX(guiW, guiH, COL_X[c] + 44),
                        MenuGeom.screenY(guiH, TOP + ROWS * STEP), 0xFF808080);
                } else {
                    LatteHud.drawHudText(g, "...", COL_X[c], TOP + ROWS * STEP, guiW, guiH);
                }
            }
        }
        if (mcFont) {
            g.centeredText(font, "WAD SELECTION", guiW / 2, MenuGeom.screenY(guiH, 10),
                0xFFFF5555);
        } else {
            LatteHud.drawHudText(g, "WAD SELECTION", 118, 10, guiW, guiH);
        }
        final int chosen = (game != null ? 1 : 0) + patches.size() + dehs.size();
        final String summary = chosen == 0 ? "NOTHING SELECTED" : chosen + " SELECTED";
        if (mcFont) {
            g.centeredText(font, summary, guiW / 2, MenuGeom.screenY(guiH, 170), 0xFFAAAAAA);
        } else {
            LatteHud.drawHudText(g, summary, 134, 170, guiW, guiH);
        }
        if (onLoadRow()) {
            g.fill(MenuGeom.screenX(guiW, guiH, 138), MenuGeom.screenY(guiH, 179),
                MenuGeom.screenX(guiW, guiH, 182), MenuGeom.screenY(guiH, 191), 0x903030A0);
        }
        if (mcFont) {
            g.centeredText(font, "LOAD", guiW / 2, MenuGeom.screenY(guiH, 182),
                onLoadRow() ? 0xFFFF5555 : 0xFFFFFFFF);
        } else {
            LatteHud.drawHudText(g, "LOAD", 150, 182, guiW, guiH);
        }
    }

    private boolean onLoadRow() {
        return row >= cols.get(col).size();
    }

    @Override
    public boolean key(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                moveRow(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                moveRow(1);
                return true;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                shiftCol(-1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                shiftCol(1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                activate();
                return true;
            }
            default -> {
                return false; // Esc and Backspace fall through to the controller's Back
            }
        }
    }

    private void moveRow(int d) {
        final int size = cols.get(col).size();
        row = Math.floorMod(row + d, size + 1); // the extra stop is LOAD
        DoomSfx.play(SND_PSTOP, false, 0, 0);
        if (row < size) {
            if (row < scrolls[col]) {
                scrolls[col] = row;
            }
            if (row >= scrolls[col] + ROWS) {
                scrolls[col] = row - ROWS + 1;
            }
        }
    }

    private void shiftCol(int d) {
        DoomSfx.play(SND_PSTOP, false, 0, 0);
        col = Math.floorMod(col + d, 3);
        final int size = cols.get(col).size();
        if (row > size) {
            row = size; // land on LOAD if the row does not exist here
        }
    }

    private void activate() {
        if (onLoadRow()) {
            doLoad();
            return;
        }
        toggle(col, cols.get(col).get(row));
    }

    /** Select or unselect one entry. One game at a time: picking a second game simply
     * replaces the first, which is the only combination that could conflict. */
    private void toggle(int c, String name) {
        DoomSfx.play(SND_SWTCHN, false, 0, 0);
        switch (c) {
            case 0 -> game = name.equals(game) ? null : name;
            case 1 -> {
                if (!patches.remove(name)) {
                    patches.add(name);
                    // a patch without a game selects the game it is for, the same
                    // choice /load would make
                    if (game == null) {
                        game = LatteDoomClient.suggestedBaseFor(name);
                    }
                }
            }
            default -> {
                if (!dehs.remove(name)) {
                    dehs.add(name);
                }
            }
        }
    }

    private void doLoad() {
        if (game == null && !LatteDoomClient.hasGameData()) {
            DoomSfx.play(SND_OOF, false, 0, 0);
            return; // nothing to stand on: a game must be chosen first
        }
        if (game == null && patches.isEmpty() && dehs.isEmpty()) {
            DoomSfx.play(SND_OOF, false, 0, 0);
            return;
        }
        DoomSfx.play(SND_PISTOL, false, 0, 0);
        final var player = Minecraft.getInstance().player;
        LatteDoomClient.loadSelection(
            msg -> { if (player != null) { player.sendSystemMessage(msg); } },
            game, patches, dehs);
        // this page was built from the previous set's art and episode list, so close
        // outright and let the next open build the menu from the loaded one
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public void hover(double cx, double cy) {
        if (cy >= 176 && cy <= 192 && cx >= 130 && cx <= 190) {
            final int size = cols.get(col).size();
            if (row != size) {
                row = size; // the LOAD stop
                DoomSfx.play(SND_PSTOP, false, 0, 0);
            }
            return;
        }
        for (int c = 0; c < 3; c++) {
            if (cx < COL_X[c] - 10 || cx > COL_X[c] + 96) {
                continue;
            }
            final int r = (int) Math.floor((cy - TOP + 3) / STEP);
            final int i = scrolls[c] + r;
            if (r >= 0 && r < ROWS && i < cols.get(c).size() && (col != c || row != i)) {
                col = c;
                row = i;
                DoomSfx.play(SND_PSTOP, false, 0, 0);
            }
            return;
        }
    }

    @Override
    public boolean click(double cx, double cy) {
        if (cy >= 176 && cy <= 192 && cx >= 130 && cx <= 190) {
            doLoad();
            return true;
        }
        for (int c = 0; c < 3; c++) {
            if (cx < COL_X[c] - 10 || cx > COL_X[c] + 96) {
                continue;
            }
            final int r = (int) Math.floor((cy - TOP + 3) / STEP);
            final int i = scrolls[c] + r;
            if (r >= 0 && r < ROWS && i < cols.get(c).size()) {
                col = c;
                row = i;
                toggle(c, cols.get(c).get(i));
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean scroll(double cx, double cy, double amount) {
        // the wheel scrolls the column under the mouse
        int target = col;
        for (int c = 0; c < 3; c++) {
            if (cx >= COL_X[c] - 10 && cx <= COL_X[c] + 96) {
                target = c;
            }
        }
        final int max = Math.max(0, cols.get(target).size() - ROWS);
        scrolls[target] = Math.max(0, Math.min(max,
            scrolls[target] - (int) Math.signum(amount)));
        return true;
    }
}
