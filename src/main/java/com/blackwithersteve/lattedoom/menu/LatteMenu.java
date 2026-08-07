package com.blackwithersteve.lattedoom.menu;

import com.blackwithersteve.lattedoom.LatteDoomClient;
import com.blackwithersteve.lattedoom.render.DoomSfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The menu screen. It renders whatever {@link MenuPage} is on top of the navigation stack
 * and drives it — cursor, keyboard, mouse, back — without knowing what the page contains.
 *
 * Because it is a stack, Back is generic: a page pushed from anywhere returns to whatever
 * pushed it.
 */
public final class LatteMenu extends Screen {

    private static final int SND_SWTCHX = data.sounds.sfxenum_t.sfx_swtchx.ordinal();

    private final Screen parent;
    private final Deque<MenuPage> stack = new ArrayDeque<>();

    public LatteMenu(Screen parent, MenuPage root) {
        super(Component.literal("Latte Doom — menu"));
        this.parent = parent;
        this.stack.push(root);
    }

    private MenuPage page() {
        return stack.peek();
    }

    /** Minecraft keeps rendering; the ENGINE freeze in LatteDoomClient is the real pause. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc is BACK inside the menu
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // wherever you stand stays visible behind the menu, dimmed — never a backdrop
        g.fill(0, 0, this.width, this.height, 0xA0000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        final MenuPage p = page();
        if (p == null) {
            return;
        }
        if (p.body() != null) {
            p.body().draw(g, this.font, this.width, this.height);
            return;
        }
        // Labels are drawn from the WAD's STCFN glyphs, so with no game data loaded the
        // page would render as an empty rectangle.
        if (!com.blackwithersteve.lattedoom.render.LatteHud.hasGfx("stcfn065")) {
            drawTextFallback(g, p);
            return;
        }
        p.draw(g, this.width, this.height);
    }

    private void drawTextFallback(GuiGraphicsExtractor g, MenuPage p) {
        final int cx = this.width / 2;
        int y = this.height / 2 - 20 - p.items().size() * 7;
        g.centeredText(this.font, p.plainTitle(), cx, y, 0xFFFF5555);
        for (int i = 0; i < p.items().size(); i++) {
            y += 14;
            final boolean sel = i == p.cursor();
            g.centeredText(this.font, (sel ? "> " : "") + p.items().get(i).plainText(),
                cx, y, sel ? 0xFFFFFF55 : 0xFFAAAAAA);
        }
        g.centeredText(this.font, "arrows adjust   -   ESC goes back", cx, y + 24, 0xFF888888);
    }

    private void push(MenuPage next) {
        if (next != null) {
            stack.push(next);
        }
    }

    /** Pop one page; leaving the root hands the screen back to whoever opened us. */
    private void back() {
        DoomSfx.play(SND_SWTCHX, false, 0, 0);
        stack.pop();
        if (stack.isEmpty()) {
            Minecraft.getInstance().gui.setScreen(parent);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (LatteDoomClient.isBootKey(event) || event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT) {
            DoomSfx.play(SND_SWTCHX, false, 0, 0);
            onClose(); // M opens and closes the whole menu, wherever you are in it
            return true;
        }
        final MenuPage p = page();
        if (p == null) {
            return true;
        }
        if (p.body() != null) {
            if (!p.body().key(event.key())) {
                back();
            }
            return true;
        }
        if (p.isScroller()) {
            // any key but Back steps to the next picture; running out of pictures leaves
            if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE
                || !p.advanceArt()) {
                back();
            }
            return true;
        }
        switch (event.key()) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> p.move(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> p.move(1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (p.selected() != null) {
                    push(p.selected().activate());
                }
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                if (p.selected() != null && !p.selected().adjust(1)) {
                    push(p.selected().activate());
                }
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                if (p.selected() == null || !p.selected().adjust(-1)) {
                    back();
                }
            }
            case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_BACKSPACE -> back();
            default -> { }
        }
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        final MenuPage p = page();
        if (p == null) {
            return;
        }
        if (p.body() == null && p.scaling() == MenuPage.Scaling.SETTINGS) {
            // settings rows live in real pixels, so the mouse is not converted at all
            p.pointAt(p.rowAtPx(mouseY, MenuScale.settings(this.width, this.height)));
            return;
        }
        final double[] c = MenuGeom.toCanvas(this.width, this.height, mouseX, mouseY);
        if (p.body() != null) {
            p.body().hover(c[0], c[1]);
            return;
        }
        p.pointAt(p.rowAt(c[1]));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        final MenuPage p = page();
        if (p == null) {
            return true;
        }
        if (event.button() == 1) { // right button walks back, like Esc
            back();
            return true;
        }
        if (p.body() != null) {
            final double[] bc = MenuGeom.toCanvas(this.width, this.height,
                event.x(), event.y());
            p.body().click(bc[0], bc[1]);
            return true;
        }
        if (p.isScroller()) {
            if (!p.advanceArt()) {
                back();
            }
            return true;
        }
        if (p.scaling() == MenuPage.Scaling.SETTINGS) {
            final MenuScale s = MenuScale.settings(this.width, this.height);
            final int r = p.rowAtPx(event.y(), s);
            if (r < 0) {
                return true;
            }
            p.pointAt(r);
            if (!p.sliderDragPx(r, event.x(), s)) {
                final MenuItem it = p.items().get(r);
                if (!it.click(p, 0, 0, 0)) {
                    push(it.activate());
                }
            }
            return true;
        }
        final double[] c = MenuGeom.toCanvas(this.width, this.height, event.x(), event.y());
        final int row = p.rowAt(c[1]);
        if (row < 0) {
            return true;
        }
        p.pointAt(row);
        final MenuItem item = p.items().get(row);
        if (!item.click(p, c[0], c[1], p.rowY(row))) {
            push(item.activate());
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        final MenuPage p = page();
        if (p == null || p.selected() == null) {
            return true;
        }
        if (p.scaling() == MenuPage.Scaling.SETTINGS) {
            p.sliderDragPx(p.cursor(), event.x(),
                MenuScale.settings(this.width, this.height));
            return true;
        }
        final double[] c = MenuGeom.toCanvas(this.width, this.height, event.x(), event.y());
        p.selected().drag(p, c[0], c[1], p.rowY(p.cursor()));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        final MenuPage p = page();
        if (p == null) {
            return true;
        }
        final double[] c = MenuGeom.toCanvas(this.width, this.height, mouseX, mouseY);
        if (p.body() != null) {
            p.body().scroll(c[0], c[1], scrollY);
            return true;
        }
        p.pointAt(p.rowAt(c[1]));
        if (p.selected() != null) {
            p.selected().adjust(scrollY > 0 ? 1 : -1);
        }
        return true;
    }
}
