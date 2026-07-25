package com.blackwithersteve.lattedoom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Death overlay for a transformed player. Draws a red fade over the world; no text and no
 * buttons. Fire or use respawns after a 10-tick delay. The level restart is handled by the
 * death path in {@code LatteWorld}; {@code MarineDeathMixin} suppresses the Minecraft death
 * message and the item drop.
 */
public final class DoomDeathScreen extends Screen {

    private int ticks;

    public DoomDeathScreen() {
        super(Component.literal("Latte Doom: death"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // must not pause: the engine keeps ticking while the player is dead
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void tick() {
        ticks++;
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isDeadOrDying()) {
            onClose();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // Alpha ramps to 0xB4 over ~1.5 s. Not opaque, so the world stays readable.
        final int alpha = Math.min(0xB4, ticks * 12);
        g.fill(0, 0, this.width, this.height, (alpha << 24) | 0xFF0000);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Use and fire both respawn.
        if (event.key() == GLFW.GLFW_KEY_SPACE || event.key() == GLFW.GLFW_KEY_ENTER
            || event.key() == GLFW.GLFW_KEY_KP_ENTER || event.key() == GLFW.GLFW_KEY_E) {
            respawn();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        respawn();
        return true;
    }

    private void respawn() {
        final Minecraft mc = Minecraft.getInstance();
        if (ticks < 10 || mc.player == null) {
            return; // 10-tick guard so the killing-blow click does not skip the screen
        }
        mc.player.respawn();
    }
}
