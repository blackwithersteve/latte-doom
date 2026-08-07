package com.blackwithersteve.lattedoom.menu;

import com.blackwithersteve.lattedoom.render.DoomSfx;
import com.blackwithersteve.lattedoom.render.LatteHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A page of the menu: some fixed art, a list of {@link MenuItem}s laid out down the
 * canvas, and an optional footer.
 *
 * The page owns the layout. {@link #rowY} is used by the drawing and by {@link #rowAt},
 * so a row is hit where it was drawn.
 *
 * A page may instead be an art scroller: a sequence of full-canvas graphics that a press
 * steps through, which is what Read This! is.
 */
public final class MenuPage {

    /**
     * Which coordinate space this page is laid out in. UZDoom declares the same choice per
     * menu (MENUDEF's {@code Size clean | optclean | w,h}); no item ever picks its own.
     */
    public enum Scaling {
        /** DOOM's 320x200 canvas, aspect-corrected — the art pages. */
        FIT43,
        /** Real pixels at an integer factor — the settings pages. */
        SETTINGS
    }

    /** A fixed graphic, or line of text, drawn at a canvas position. */
    private record Deco(String art, String text, double x, double y) { }

    private final List<Deco> decos;
    private final double itemX, firstY, valueX;
    private final List<MenuItem> items;
    private final List<String> scrollerArt;
    private final Supplier<String> footer;
    private final MenuBody body;
    private final int visibleRows;
    private final boolean optionStyle;
    private final Scaling scaling;
    private int cursor;
    private int artIndex;
    private int scroll;

    private MenuPage(Builder b) {
        this.decos = List.copyOf(b.decos);
        this.itemX = b.itemX;
        this.firstY = b.firstY;
        this.valueX = b.valueX;
        this.items = List.copyOf(b.items);
        this.scrollerArt = List.copyOf(b.scrollerArt);
        this.footer = b.footer;
        this.body = b.body;
        this.visibleRows = b.visibleRows;
        this.optionStyle = b.optionStyle;
        // a settings-style page is laid out in real pixels; art pages keep the canvas
        this.scaling = b.optionStyle ? Scaling.SETTINGS : Scaling.FIT43;
        this.cursor = b.cursor >= 0 ? b.cursor : firstSelectable();
        ensureVisible();
    }

    public Scaling scaling() {
        return scaling;
    }

    /** How many rows are actually drawn, given the page's limit. */
    private int shown() {
        return visibleRows <= 0 ? items.size() : Math.min(visibleRows, items.size());
    }

    /** Keep the cursor inside the drawn window. */
    private void ensureVisible() {
        if (visibleRows <= 0) {
            scroll = 0;
            return;
        }
        if (cursor < scroll) {
            scroll = cursor;
        } else if (cursor >= scroll + visibleRows) {
            scroll = cursor - visibleRows + 1;
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - visibleRows)));
    }

    /** A page that draws and drives its own canvas (the WAD picker), or null. */
    public MenuBody body() {
        return body;
    }

    public static Builder builder() {
        return new Builder();
    }

    public double itemX() {
        return itemX;
    }

    public double valueX() {
        return valueX;
    }

    public List<MenuItem> items() {
        return items;
    }

    public int cursor() {
        return cursor;
    }

    /** An art scroller has no rows: a press steps the picture instead of choosing. */
    public boolean isScroller() {
        return !scrollerArt.isEmpty();
    }

    /** Step to the next picture. False when the last one has already been shown, which
     * the controller turns into a Back. */
    public boolean advanceArt() {
        if (artIndex + 1 >= scrollerArt.size()) {
            return false;
        }
        artIndex++;
        return true;
    }

    /** Canvas y of row {@code index}, accumulated over the rows above it in the drawn
     * window, so a scrolled page's first visible row sits at firstY. */
    public double rowY(int index) {
        double y = firstY;
        for (int i = scroll; i < index && i < items.size(); i++) {
            y += items.get(i).height();
        }
        return y;
    }

    /** The row containing this canvas y, or -1. Captions and rows scrolled out of the
     * window never answer. */
    public int rowAt(double canvasY) {
        double y = firstY;
        final int end = Math.min(items.size(), scroll + shown());
        for (int i = scroll; i < end; i++) {
            final double h = items.get(i).height();
            if (canvasY >= y && canvasY < y + h) {
                return items.get(i).selectable() ? i : -1;
            }
            y += h;
        }
        return -1;
    }

    private int firstSelectable() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).selectable()) {
                return i;
            }
        }
        return 0;
    }

    /** Move the cursor, skipping headers and wrapping, with the vanilla blip. */
    public void move(int delta) {
        if (items.isEmpty()) {
            return;
        }
        int i = cursor;
        for (int guard = 0; guard < items.size(); guard++) {
            i = Math.floorMod(i + delta, items.size());
            if (items.get(i).selectable()) {
                break;
            }
        }
        if (i != cursor) {
            cursor = i;
            ensureVisible();
            DoomSfx.play(MenuItem.SND_PSTOP, false, 0, 0);
        }
    }

    /** Point the cursor at a row the mouse found, blipping only on a real change. */
    public void pointAt(int index) {
        if (index < 0 || index >= items.size() || index == cursor
            || !items.get(index).selectable()) {
            return;
        }
        cursor = index;
        ensureVisible();
        DoomSfx.play(MenuItem.SND_PSTOP, false, 0, 0);
    }

    public MenuItem selected() {
        return items.isEmpty() ? null : items.get(Math.min(cursor, items.size() - 1));
    }

    /** Right edge of an option row's highlight, and of the page's own column. */
    private static final double ROW_RIGHT = 296;

    /** A track down the right margin with a thumb sized to the visible fraction. */
    private void drawScrollbar(GuiGraphicsExtractor g, int end, int guiW, int guiH) {
        double height = 0;
        for (int i = scroll; i < end; i++) {
            height += items.get(i).height();
        }
        final double top = firstY - 3, bottom = firstY + height - 4;
        MenuGeom.bar(g, ROW_RIGHT + 4, top, ROW_RIGHT + 8, bottom, 0x50FFFFFF, guiW, guiH);
        final double span = bottom - top;
        final double thumb = Math.max(6, span * visibleRows / items.size());
        final double travel = (span - thumb) * scroll / (double) (items.size() - visibleRows);
        MenuGeom.bar(g, ROW_RIGHT + 4, top + travel, ROW_RIGHT + 8, top + travel + thumb,
            0xC0FFFFFF, guiW, guiH);
    }

    /** The page's title for the Minecraft-font fallback (see {@link MenuItem#plainText}). */
    public String plainTitle() {
        if (decos.isEmpty()) {
            return "";
        }
        final Deco d = decos.get(0);
        return d.text() != null ? d.text() : (d.art() != null ? d.art() : "");
    }

    // UZDoom's option-menu colours, as close as STCFN's palette allows. The glyphs are
    // multiplied by these, so a colour can darken the source but never brighten it.
    private static final int ROW_NORMAL = 0xFFFFFFFF;
    private static final int ROW_SELECTED = 0xFFFFF0A0;
    private static final int ROW_VALUE = 0xFFB0B0B0;
    private static final int ROW_HEADER = 0xFFFF6040;
    private static final int ROW_GREYED = 0xFF707070;

    /**
     * A settings page: two columns in real pixels at an integer scale, the way UZDoom's
     * OptionMenu lays out. Rows are not on the 320x200 canvas at all, because a fractional
     * canvas factor resamples the glyph patches unevenly.
     */
    /** The settings layout, computed ONCE and used by both the drawing and the hit-test.
     * Keeping these in one place is what stops the two from drifting apart. */
    private record SLayout(int fac, int line, int shown, int top, int labelX, int indent) { }

    private SLayout layout(MenuScale s) {
        final int fac = s.fac();
        final int fh = Math.max(6, LatteHud.fontHeight());
        final int line = (fh + 9) * fac;             // UZDoom uses 17 against an 8px font
        final int top = 40 * fac;
        final int rows = Math.max(1, (s.screenH() - top - 20 * fac) / line);
        final int shown = Math.min(rows, items.size());
        int widest = 0;
        for (MenuItem it : items) {
            final String l = it.label();
            if (l != null) {
                widest = Math.max(widest, LatteHud.textWidth(l));
            }
        }
        // the widest label decides the column, so labels and values never collide
        final int indent = Math.max(s.screenW() / 2, widest * fac + 24 * fac);
        final int labelX = Math.max(8 * fac, indent - widest * fac - 8 * fac);
        return new SLayout(fac, line, shown, top, labelX, indent);
    }

    /** Keep the cursor inside the settings window, using the shared layout. */
    private void clampScroll(SLayout l) {
        if (cursor < scroll) {
            scroll = cursor;
        } else if (cursor >= scroll + l.shown()) {
            scroll = cursor - l.shown() + 1;
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - l.shown())));
    }

    /** The row under a screen point on a settings page, or -1. */
    public int rowAtPx(double screenY, MenuScale s) {
        final SLayout l = layout(s);
        clampScroll(l);
        final int i = scroll + (int) Math.floor((screenY - l.top()) / l.line());
        if (i < scroll || i >= Math.min(items.size(), scroll + l.shown())) {
            return -1;
        }
        return items.get(i).selectable() ? i : -1;
    }

    /** A press or drag on a settings-page slider: the knob goes where the mouse points. */
    public boolean sliderDragPx(int index, double screenX, MenuScale s) {
        if (index < 0 || index >= items.size()
            || !(items.get(index) instanceof MenuItem.Slider sl)) {
            return false;
        }
        final SLayout l = layout(s);
        final int cell = 8 * l.fac();
        final double span = 12.0 * cell;             // matches MenuGeom.sliderPx
        final double rel = screenX - l.indent();
        if (rel < -cell || rel > span + cell) {
            return false;
        }
        sl.setFraction(rel / span);
        return true;
    }

    private void drawSettings(GuiGraphicsExtractor g, MenuScale s) {
        final SLayout l = layout(s);
        clampScroll(l);
        final int fac = l.fac();
        final int fh = Math.max(6, LatteHud.fontHeight());
        final int line = l.line();
        final int shownHere = l.shown();
        final int indent = l.indent();
        final int labelX = l.labelX();
        int y = l.top();

        // the page title, in the WAD's own art where it has one
        for (Deco d : decos) {
            if (d.art() != null && LatteHud.hasGfx(d.art())) {
                final int[] sz = com.blackwithersteve.lattedoom.render.DoomRuntimeTextures
                    .textureSize("gfx/" + d.art());
                final int tw = sz == null ? 0 : sz[0] * fac;
                LatteHud.drawGfxPx(g, d.art(), (s.screenW() - tw) / 2, 10 * fac, fac,
                    ROW_NORMAL);
            } else if (d.text() != null) {
                LatteHud.drawTextPx(g, d.text(),
                    (s.screenW() - LatteHud.textWidth(d.text()) * fac) / 2, 12 * fac, fac,
                    ROW_HEADER);
            }
            break; // settings pages carry one title
        }

        final int end = Math.min(items.size(), scroll + shownHere);
        for (int i = scroll; i < end; i++) {
            final MenuItem it = items.get(i);
            final boolean sel = i == cursor;
            final String lab = it.label();
            final int colour = !it.selectable() ? ROW_HEADER : sel ? ROW_SELECTED : ROW_NORMAL;
            if (lab != null && !lab.isEmpty()) {
                LatteHud.drawTextPx(g, lab, labelX, y, fac, colour);
            }
            if (it instanceof MenuItem.Slider sl) {
                final int after = MenuGeom.sliderPx(g, indent, y + fac, sl.fraction(), fac,
                    s.screenW());
                final String v = sl.valueText();
                if (v != null) {
                    LatteHud.drawTextPx(g, v, after, y, fac, ROW_VALUE);
                }
            } else if (it.value() != null) {
                LatteHud.drawTextPx(g, it.value(), indent, y, fac,
                    sel ? ROW_SELECTED : ROW_VALUE);
            }
            if (sel) {
                // STCFN has no arrow glyph, so the cursor is drawn geometry (blinking, as
                // UZDoom's does at MenuTime()%8 < 6)
                if (MenuGeom.skullFirst()) {
                    MenuGeom.caret(g, labelX - 9 * fac, y + fh * fac / 2, fac, ROW_SELECTED);
                }
            }
            y += line;
        }
        if (items.size() > shownHere) {
            MenuGeom.scrollbarPx(g, s.screenW() - 11 * fac, l.top(),
                shownHere * line, scroll, shownHere, items.size(), fac);
        }
    }

    public void draw(GuiGraphicsExtractor g, int guiW, int guiH) {
        if (scaling == Scaling.SETTINGS && !isScroller() && body == null) {
            drawSettings(g, MenuScale.settings(guiW, guiH));
            return;
        }
        if (isScroller()) {
            final String lump = scrollerArt.get(Math.min(artIndex, scrollerArt.size() - 1));
            if (LatteHud.hasGfx(lump)) {
                LatteHud.drawGfx(g, lump, 0, 0, guiW, guiH);
            } else if (LatteHud.hasGfx("credit")) {
                LatteHud.drawGfx(g, "credit", 0, 0, guiW, guiH);
            }
            return;
        }
        for (Deco d : decos) {
            MenuItem.label(g, d.art(), d.text(), d.x(), d.y(), guiW, guiH);
        }
        final int end = Math.min(items.size(), scroll + shown());
        // The selected row is marked with a bar behind it rather than a colour, because
        // the WAD's glyphs are patches and the blitter takes no tint.
        if (optionStyle && selected() != null && selected().selectable()) {
            final double y = rowY(cursor);
            MenuGeom.bar(g, itemX - 34, y - 3, ROW_RIGHT, y + selected().height() - 4,
                0x903030A0, guiW, guiH);
        }
        for (int i = scroll; i < end; i++) {
            items.get(i).draw(g, this, rowY(i), i == cursor, guiW, guiH);
        }
        if (optionStyle && visibleRows > 0 && items.size() > visibleRows) {
            drawScrollbar(g, end, guiW, guiH);
        } else if (end < items.size()) {
            LatteHud.drawHudText(g, "...", itemX, rowY(end), guiW, guiH);
        }
        if (selected() != null && selected().selectable()) {
            MenuGeom.skull(g, itemX + MenuGeom.SKULL_DX, rowY(cursor) + MenuGeom.SKULL_DY,
                guiW, guiH);
        }
        if (footer != null) {
            LatteHud.drawHudText(g, footer.get(), 24, 178, guiW, guiH);
        }
    }

    public static final class Builder {
        private final List<Deco> decos = new ArrayList<>();
        private double itemX = 60, firstY = 48, valueX = 200;
        private final List<MenuItem> items = new ArrayList<>();
        private final List<String> scrollerArt = new ArrayList<>();
        private Supplier<String> footer;
        private MenuBody body;
        private int visibleRows;
        private boolean optionStyle;
        private int cursor = -1;

        /** Fixed art, or text when the WAD has no such lump. The first is the page's
         * title for the plain-text fallback. */
        public Builder deco(String art, String text, double x, double y) {
            decos.add(new Deco(art, text, x, y));
            return this;
        }

        /** Left edge of the rows; the skull sits 32px left of it. */
        public Builder at(double x, double y) {
            this.itemX = x;
            this.firstY = y;
            return this;
        }

        /** The right-hand value column, for label/value rows. */
        public Builder valueX(double x) {
            this.valueX = x;
            return this;
        }

        /** Where the cursor starts (the skill page opens on the persisted difficulty). */
        public Builder cursor(int index) {
            this.cursor = index;
            return this;
        }

        public Builder add(MenuItem item) {
            items.add(item);
            return this;
        }

        /** Make this an art scroller over these full-canvas lumps, in order. */
        public Builder scroller(String... lumps) {
            for (String l : lumps) {
                scrollerArt.add(l);
            }
            return this;
        }

        public Builder footer(Supplier<String> text) {
            this.footer = text;
            return this;
        }

        /** A settings page: highlight the selected row and show a scrollbar. The image
         * pages (main, episode, skill) leave this off and keep the skull alone. */
        public Builder optionStyle() {
            this.optionStyle = true;
            return this;
        }

        /** Hand the whole canvas to a self-drawing body (see {@link MenuBody}). */
        public Builder body(MenuBody body) {
            this.body = body;
            return this;
        }

        /** Cap how many rows are drawn at once; the window follows the cursor. A set can
         * register M_EPI1..M_EPI9, and nine rows from y=63 run off the canvas. */
        public Builder visibleRows(int rows) {
            this.visibleRows = rows;
            return this;
        }

        public MenuPage build() {
            return new MenuPage(this);
        }
    }
}
