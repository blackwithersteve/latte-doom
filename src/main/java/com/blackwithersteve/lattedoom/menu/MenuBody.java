package com.blackwithersteve.lattedoom.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A page that draws and drives its own canvas, for pages whose shape is not a vertical
 * list. {@link LatteMenu} still owns the navigation stack, Back and the screen lifecycle,
 * so such a page pushes and pops like any other.
 */
public interface MenuBody {

    void draw(GuiGraphicsExtractor g, Font font, int guiW, int guiH);

    /** True when the key was consumed. False lets the controller apply Back/close. */
    boolean key(int key);

    /** The mouse moved to this canvas point. */
    void hover(double canvasX, double canvasY);

    /** True when the click was consumed. */
    boolean click(double canvasX, double canvasY);

    /** True when the wheel was consumed. */
    boolean scroll(double canvasX, double canvasY, double amount);
}
