package com.blackwithersteve.lattedoom.menu;

import com.blackwithersteve.lattedoom.render.DoomSfx;
import com.blackwithersteve.lattedoom.render.LatteHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One row of a menu page: how it draws, and what left/right and enter do to it.
 *
 * An item never positions itself vertically. {@link MenuPage} lays the rows out from the
 * declared heights and hands each item its row's y, which is the same y the mouse
 * hit-test uses.
 */
public abstract class MenuItem {

    static final int SND_PSTOP = data.sounds.sfxenum_t.sfx_pstop.ordinal();
    static final int SND_SWTCHN = data.sounds.sfxenum_t.sfx_swtchn.ordinal();
    static final int SND_STNMOV = data.sounds.sfxenum_t.sfx_stnmov.ordinal();
    static final int SND_OOF = data.sounds.sfxenum_t.sfx_oof.ordinal();
    static final int SND_PISTOL = data.sounds.sfxenum_t.sfx_pistol.ordinal();

    /** Canvas height of this row; the next row starts this far down. */
    public int height() {
        return MenuGeom.LINEHEIGHT;
    }

    /** Whether the cursor may rest here (headers and spacers say no). */
    public boolean selectable() {
        return true;
    }

    public abstract void draw(GuiGraphicsExtractor g, MenuPage page, double y, boolean selected,
                              int guiW, int guiH);

    /** Left/right. True when this item consumed the key. */
    public boolean adjust(int delta) {
        return false;
    }

    /** Enter. Returns a page to push, or null to stay. */
    public MenuPage activate() {
        return null;
    }

    /** A click inside this row, in canvas coordinates. True when consumed. */
    public boolean click(MenuPage page, double cx, double cy, double rowY) {
        return false;
    }

    /** A drag inside this row. Sliders track it; everything else ignores it. */
    public boolean drag(MenuPage page, double cx, double cy, double rowY) {
        return false;
    }

    /**
     * This row as plain text, for the Minecraft-font fallback. Labels are drawn from the
     * WAD's STCFN glyphs, so before a WAD is loaded a page has nothing to render with.
     */
    public String plainText() {
        return "";
    }

    // ---- what a settings page needs to lay a row out in two columns ----

    /** The row's left-hand label. */
    public String label() {
        return plainText();
    }

    /** The row's right-hand value, or null when the row has none (submenus, commands). */
    public String value() {
        return null;
    }

    /** Whether this row draws a slider bar instead of a value. */
    public boolean slider() {
        return false;
    }

    /** A WAD graphic when the lump exists, else the same string in the WAD's small font.
     * Rows this port adds (WADS, CRISPNESS, CREDITS) have no menu graphic of their own. */
    static void label(GuiGraphicsExtractor g, String art, String text, double x, double y,
                      int guiW, int guiH) {
        if (art != null && LatteHud.hasGfx(art)) {
            LatteHud.drawGfx(g, art, x, y, guiW, guiH);
        } else if (text != null) {
            LatteHud.drawHudText(g, text, x, y, guiW, guiH);
        }
    }

    // ---- concrete items ----

    /** A caption the cursor skips. */
    public static final class Header extends MenuItem {
        private final String text;
        private final int rowHeight;

        public Header(String text) {
            this(text, MenuGeom.LINEHEIGHT);
        }

        public Header(String text, int rowHeight) {
            this.text = text;
            this.rowHeight = rowHeight;
        }

        @Override
        public int height() {
            return rowHeight;
        }

        @Override
        public boolean selectable() {
            return false;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, MenuPage page, double y, boolean selected,
                         int guiW, int guiH) {
            LatteHud.drawHudText(g, text, page.itemX(), y, guiW, guiH);
        }

        @Override
        public String plainText() {
            return text;
        }
    }

    /** A plain row that runs something (New Game, Read This!, a Load slot). */
    public static final class Action extends MenuItem {
        private final String art;
        private final String text;
        private final Runnable action;
        private final BooleanSupplier enabled;
        private final int sound;

        public Action(String art, String text, Runnable action) {
            this(art, text, action, () -> true, SND_SWTCHN);
        }

        /** Picking a skill fires the pistol rather than the switch, as vanilla does. */
        public Action(String art, String text, Runnable action, BooleanSupplier enabled,
                      int sound) {
            this.art = art;
            this.text = text;
            this.action = action;
            this.enabled = enabled;
            this.sound = sound;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, MenuPage page, double y, boolean selected,
                         int guiW, int guiH) {
            label(g, art, text, page.itemX(), y, guiW, guiH);
        }

        @Override
        public MenuPage activate() {
            if (!enabled.getAsBoolean()) {
                DoomSfx.play(SND_OOF, false, 0, 0);
                return null;
            }
            DoomSfx.play(sound, false, 0, 0);
            action.run();
            return null;
        }

        @Override
        public boolean click(MenuPage page, double cx, double cy, double rowY) {
            activate();
            return true;
        }

        @Override
        public String plainText() {
            return text != null ? text : art;
        }
    }

    /** A row that opens another page. The stack makes Back generic. */
    public static final class Sub extends MenuItem {
        private final String art;
        private final String text;
        private final Supplier<MenuPage> page;
        private final BooleanSupplier enabled;
        private final int rowHeight;

        public Sub(String art, String text, Supplier<MenuPage> page) {
            this(art, text, page, () -> true, MenuGeom.LINEHEIGHT);
        }

        /** A disabled row grunts instead of opening, the way Save refuses with no level
         * standing. rowHeight lets a row carry a gap below it. */
        public Sub(String art, String text, Supplier<MenuPage> page, BooleanSupplier enabled,
                   int rowHeight) {
            this.art = art;
            this.text = text;
            this.page = page;
            this.enabled = enabled;
            this.rowHeight = rowHeight;
        }

        @Override
        public int height() {
            return rowHeight;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, MenuPage parent, double y, boolean selected,
                         int guiW, int guiH) {
            label(g, art, text, parent.itemX(), y, guiW, guiH);
        }

        @Override
        public MenuPage activate() {
            if (!enabled.getAsBoolean()) {
                DoomSfx.play(SND_OOF, false, 0, 0);
                return null;
            }
            DoomSfx.play(SND_SWTCHN, false, 0, 0);
            return page.get();
        }

        @Override
        public boolean click(MenuPage parent, double cx, double cy, double rowY) {
            return false; // the controller pushes what activate() returns
        }

        @Override
        public String plainText() {
            return text != null ? text : art;
        }

        @Override
        public String label() {
            return text != null ? text : art;
        }
    }

    /** Label on the left, one of a fixed set of values on the right. Left/right cycle. */
    public static final class Choice extends MenuItem {
        private final String text;
        private final String[] values;
        private final IntSupplier get;
        private final IntConsumer set;
        private final int rowHeight;

        public Choice(String text, String[] values, IntSupplier get, IntConsumer set,
                      int rowHeight) {
            this.text = text;
            this.values = values;
            this.get = get;
            this.set = set;
            this.rowHeight = rowHeight;
        }

        @Override
        public int height() {
            return rowHeight;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, MenuPage page, double y, boolean selected,
                         int guiW, int guiH) {
            LatteHud.drawHudText(g, text, page.itemX(), y, guiW, guiH);
            final int v = Math.floorMod(get.getAsInt(), values.length);
            LatteHud.drawHudText(g, values[v], page.valueX(), y, guiW, guiH);
        }

        @Override
        public boolean adjust(int delta) {
            set.accept(Math.floorMod(get.getAsInt() + delta, values.length));
            DoomSfx.play(SND_STNMOV, false, 0, 0);
            return true;
        }

        @Override
        public MenuPage activate() {
            adjust(1);
            return null;
        }

        @Override
        public boolean click(MenuPage page, double cx, double cy, double rowY) {
            adjust(1);
            return true;
        }

        @Override
        public String plainText() {
            return text + "   " + values[Math.floorMod(get.getAsInt(), values.length)];
        }

        @Override
        public String label() {
            return text;
        }

        @Override
        public String value() {
            return values[Math.floorMod(get.getAsInt(), values.length)];
        }
    }

    /** An on/off row. Kept distinct from Choice so callers read as what they mean. */
    public static final class Toggle extends MenuItem {
        private final String text;
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;
        private final int rowHeight;

        public Toggle(String text, BooleanSupplier get, Consumer<Boolean> set, int rowHeight) {
            this.text = text;
            this.get = get;
            this.set = set;
            this.rowHeight = rowHeight;
        }

        @Override
        public int height() {
            return rowHeight;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, MenuPage page, double y, boolean selected,
                         int guiW, int guiH) {
            LatteHud.drawHudText(g, text, page.itemX(), y, guiW, guiH);
            LatteHud.drawHudText(g, get.getAsBoolean() ? "ON" : "OFF", page.valueX(), y,
                guiW, guiH);
        }

        @Override
        public boolean adjust(int delta) {
            set.accept(!get.getAsBoolean());
            DoomSfx.play(SND_STNMOV, false, 0, 0);
            return true;
        }

        @Override
        public MenuPage activate() {
            adjust(1);
            return null;
        }

        @Override
        public boolean click(MenuPage page, double cx, double cy, double rowY) {
            adjust(1);
            return true;
        }

        @Override
        public String plainText() {
            return text + "   " + (get.getAsBoolean() ? "ON" : "OFF");
        }

        @Override
        public String label() {
            return text;
        }

        @Override
        public String value() {
            return get.getAsBoolean() ? "ON" : "OFF";
        }
    }

    /**
     * A label with a thermometer on the line beneath it, as vanilla lays sliders out.
     *
     * The value's own range and the trough's 0-15 notches are not the same scale, so the
     * two mappings are explicit: a 0-2 interface size sits on notches 0/7/14 and a 0-4
     * light boost on 1/4/7/10/13. Guessing a linear mapping instead would move both
     * knobs.
     */
    public static final class Slider extends MenuItem {
        private final String art;
        private final String text;
        private final java.util.function.DoubleSupplier get;
        private final java.util.function.DoubleConsumer set;
        private final double min, max, step;
        private final int fracDigits;

        /**
         * A continuous setting. {@code fracDigits} is how many decimals the numeric readout
         * shows, or -1 for no readout — UZDoom's own DrawSlider takes the same argument and
         * prints the value beside the bar, which is what makes a wide range usable.
         */
        public Slider(String art, String text, java.util.function.DoubleSupplier get,
                      java.util.function.DoubleConsumer set,
                      double min, double max, double step, int fracDigits) {
            this.art = art;
            this.text = text;
            this.get = get;
            this.set = set;
            this.min = min;
            this.max = max;
            this.step = step;
            this.fracDigits = fracDigits;
        }

        /** Where the knob sits, 0 to 1. */
        public double fraction() {
            final double range = max - min;
            return range <= 0 ? 0 : (clampValue(get.getAsDouble()) - min) / range;
        }

        private double clampValue(double v) {
            return Math.max(min, Math.min(max, v));
        }

        /** The number printed beside the bar, or null when the slider has no readout. */
        public String valueText() {
            if (fracDigits < 0) {
                return null;
            }
            final double v = clampValue(get.getAsDouble());
            return fracDigits == 0
                ? Integer.toString((int) Math.round(v))
                : String.format(java.util.Locale.ROOT, "%." + fracDigits + "f", v);
        }

        /** Set from a fraction of the trough, for mouse drags. */
        public void setFraction(double f) {
            final double v = min + Math.max(0, Math.min(1, f)) * (max - min);
            final double snapped = step > 0 ? Math.round(v / step) * step : v;
            apply(snapped);
        }

        private void apply(double v) {
            final double next = clampValue(v);
            if (Math.abs(next - get.getAsDouble()) > 1.0e-9) {
                set.accept(next);
                DoomSfx.play(SND_STNMOV, false, 0, 0);
            }
        }

        @Override
        public int height() {
            return MenuGeom.SLIDER_HEIGHT;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, MenuPage page, double y, boolean selected,
                         int guiW, int guiH) {
            // only reached on a canvas page; settings pages draw sliders themselves
            label(g, art, text, page.itemX(), y, guiW, guiH);
        }

        @Override
        public boolean adjust(int delta) {
            apply(get.getAsDouble() + delta * (step > 0 ? step : 1));
            return true;
        }

        @Override
        public MenuPage activate() {
            adjust(1);
            return null;
        }

        @Override
        public boolean click(MenuPage page, double cx, double cy, double rowY) {
            return true; // a press on the label must not nudge the knob
        }

        @Override
        public String plainText() {
            final String v = valueText();
            return (text != null ? text : art) + (v == null ? "" : "   " + v);
        }

        @Override
        public String label() {
            return text != null ? text : art;
        }

        @Override
        public boolean slider() {
            return true;
        }
    }

    /**
     * A save/load slot: the M_LS* trough with the slot's own description over it. The
     * description is read per frame rather than captured, so a save writes through to the
     * row the player is looking at.
     */
    public static final class Slot extends MenuItem {
        private final int index;
        private final Supplier<String> desc;
        private final java.util.function.IntPredicate action;

        public Slot(int index, Supplier<String> desc, java.util.function.IntPredicate action) {
            this.index = index;
            this.desc = desc;
            this.action = action;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, MenuPage page, double y, boolean selected,
                         int guiW, int guiH) {
            MenuGeom.slotTrough(g, page.itemX(), y + 7, guiW, guiH);
            final String name = desc.get();
            LatteHud.drawHudText(g, name != null ? name : "EMPTY SLOT", page.itemX(), y,
                guiW, guiH);
        }

        @Override
        public MenuPage activate() {
            DoomSfx.play(action.test(index) ? SND_PISTOL : SND_OOF, false, 0, 0);
            return null;
        }

        @Override
        public boolean click(MenuPage page, double cx, double cy, double rowY) {
            activate();
            return true;
        }

        @Override
        public String plainText() {
            final String name = desc.get();
            return (name != null ? name : "EMPTY SLOT");
        }
    }
}
