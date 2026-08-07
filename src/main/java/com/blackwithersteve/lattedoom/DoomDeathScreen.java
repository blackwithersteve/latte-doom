package com.blackwithersteve.lattedoom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The marine's death screen, following DOOM rather than Minecraft: no death text and no
 * buttons. The world stays visible under Minecraft's own death camera, DOOM's red death
 * fade rolls in as the software renderer's damage palettes do, and FIRE or USE rises the
 * player again. The respawn feeds the map-restart lane: the level restarts fresh and the
 * player is reborn at its start with fist and pistol. No death message reaches chat
 * (MarineDeathMixin) and the marine's inventory never scatters.
 */
public final class DoomDeathScreen extends Screen {

    private int ticks;

    public DoomDeathScreen() {
        super(Component.literal("Latte Doom — death"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the engine and the world keep living around your corpse, like 1993
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
            onClose(); // respawned (or gone) — the world takes over again
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // DOOM's death fade: the software renderer stepped the red damage palettes up as
        // you fell. Ramp alpha over ~1.5s to a heavy-but-not-opaque red, world beneath.
        final int alpha = Math.min(0xB4, ticks * 12);
        g.fill(0, 0, this.width, this.height, (alpha << 24) | 0xFF0000);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // DOOM's own contract: use/fire = rise again. Space, E (use), Enter all count.
        if (event.key() == GLFW.GLFW_KEY_SPACE || event.key() == GLFW.GLFW_KEY_ENTER
            || event.key() == GLFW.GLFW_KEY_KP_ENTER || event.key() == GLFW.GLFW_KEY_E) {
            respawn();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        respawn(); // the trigger finger works too
        return true;
    }

    private void respawn() {
        final Minecraft mc = Minecraft.getInstance();
        if (ticks < 10 || mc.player == null) {
            return; // half a second of lying there — no accidental instant skips
        }
        mc.player.respawn();
    }
}
