package com.blackwithersteve.lattedoom;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import doom.event_t;
import g.Signals.ScanCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Diagnostic view of the engine's framebuffer at 4:3, cursor captured, all keys forwarded
 * except grave. Not used in normal play, where the level is world geometry and the menus are
 * Minecraft screens.
 */
public final class LatteDoomScreen extends Screen {

    /** One framebuffer texture per session, re-used across screen opens. */
    private static DynamicTexture texture;
    private static Identifier textureId;
    private static int texW, texH;
    private static int[] frameBuf;
    private static long lastUploaded = -1;

    private final DoomHost host;
    private final LatteDoomConfig config;
    /** Automatically opened intermission or finale view, showing the engine's own tally.
     * It closes itself as soon as the next level starts, at which point the player is
     * delivered to that map's start. */
    private final boolean autoIntermission;
    /** Menu-only view used while a level is standing: it opens with the engine's own
     * menu already showing and closes once the player leaves that menu, so opening the menu
     * never switches the player into the flat framebuffer view of the game. */
    private final boolean menuOnly;
    private int escReleaseIn;   // synthetic Esc key-up countdown (held across a tic boundary)
    private int menuCloseGrace; // ticks the menu must stay closed before this screen closes
                                // itself, which gives a New Game chosen in the menu time to
                                // load its map first

    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;
    private int mouseButtons;

    public LatteDoomScreen(DoomHost host, LatteDoomConfig config) {
        this(host, config, false, false);
    }

    public LatteDoomScreen(DoomHost host, LatteDoomConfig config, boolean autoIntermission) {
        this(host, config, autoIntermission, false);
    }

    public LatteDoomScreen(DoomHost host, LatteDoomConfig config,
                           boolean autoIntermission, boolean menuOnly) {
        super(Component.literal("Latte Doom"));
        this.host = host;
        this.config = config;
        this.autoIntermission = autoIntermission;
        this.menuOnly = menuOnly;
    }

    @Override
    public void tick() {
        // The automatic view exists only for the interval between levels: once the next
        // map is live the player is handed back to the world and delivered to its start.
        if (autoIntermission && host != null && host.gamestateKind() == 0) {
            onClose();
            return;
        }
        if (escReleaseIn > 0 && host != null && --escReleaseIn == 0) {
            host.postKey(ScanCode.SC_ESCAPE, false); // finish the synthetic menu-open press
        }
        if (menuOnly && host != null && escReleaseIn == 0) {
            // Leaving the menu returns to the world. The grace window gives a New Game
            // chosen in the menu time to load its map first.
            if (host.isMenuActive()) {
                menuCloseGrace = 5;
            } else if (--menuCloseGrace <= 0) {
                onClose();
            }
        }
    }

    @Override
    protected void init() {
        if (host != null) {
            host.setFrozen(false);
        }
    }

    @Override
    public void added() {
        // Capture the pointer as a first-person shooter would: Minecraft releases it when
        // this screen opens, while the engine needs raw deltas and no visible cursor.
        GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(),
            GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        lastMouseX = Double.NaN;
        lastMouseY = Double.NaN;
        if (menuOnly && host != null) {
            // Open directly into the engine's menu. The key must be held across a tic
            // boundary for G_Responder to observe it, as with the weapon-slot keys.
            host.postKey(ScanCode.SC_ESCAPE, true);
            escReleaseIn = 2;
            menuCloseGrace = 10; // the engine has not observed the Esc press yet
        }
    }

    @Override
    public void removed() {
        GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(),
            GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        // Closing this screen never freezes the world: the level keeps running while the
        // player returns to Minecraft, and only the explicit watch command pauses the
        // engine. Any keys the engine still believes are held must be released here, or the
        // player keeps moving in the engine's view of the world.
        if (host != null) {
            host.cancelKeys();
        }
        mouseButtons = 0;
    }

    @Override
    public boolean isPauseScreen() {
        return config == null || config.pauseMinecraft;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc belongs to DOOM's own menu
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xFF000000); // pure black, no blur
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);

        if (host == null) {
            g.centeredText(this.font, "No IWAD found.", this.width / 2, this.height / 2 - 12, 0xFFFF5555);
            g.centeredText(this.font, "Drop DOOM.WAD into config/latte-doom/ and press ` to retry.",
                this.width / 2, this.height / 2 + 2, 0xFFFFFFFF);
            return;
        }

        switch (host.state()) {
            case BOOTING -> {
                g.centeredText(this.font, "Booting DOOM...", this.width / 2, this.height / 2, 0xFFFF5555);
                return;
            }
            case CRASHED -> {
                g.centeredText(this.font, "DOOM crashed: " + host.crashCause(),
                    this.width / 2, this.height / 2, 0xFFFF5555);
                return;
            }
            case QUIT -> {
                g.centeredText(this.font, "DOOM has quit. Press ` and reopen to reboot.",
                    this.width / 2, this.height / 2, 0xFFFFFFFF);
                return;
            }
            default -> { }
        }

        uploadLatestFrame();
        if (texture == null) {
            return;
        }

        // The engine renders 16:10 pixels that period displays stretched to 4:3.
        int destH = this.height;
        int destW = destH * 4 / 3;
        if (destW > this.width) {
            destW = this.width;
            destH = destW * 3 / 4;
        }
        final int x = (this.width - destW) / 2;
        final int y = (this.height - destH) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, textureId, x, y, 0.0f, 0.0f,
            destW, destH, texW, texH, texW, texH);
    }

    /** Uploads the newest engine frame into the texture, skipping unchanged frames. */
    private void uploadLatestFrame() {
        final int w = host.width(), h = host.height();
        if (texture == null || texW != w || texH != h) {
            if (texture != null) {
                texture.close();
            }
            texW = w;
            texH = h;
            frameBuf = new int[w * h];
            texture = new DynamicTexture("lattedoom framebuffer", w, h, true);
            textureId = Identifier.fromNamespaceAndPath("lattedoom", "framebuffer");
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            lastUploaded = -1;
        }
        final long n = host.copyFrame(frameBuf);
        if (n == lastUploaded) {
            return;
        }
        lastUploaded = n;
        final var pixels = texture.getPixels();
        if (pixels == null) {
            return;
        }
        for (int py = 0; py < h; py++) {
            final int rowOff = py * w;
            for (int px = 0; px < w; px++) {
                final int argb = frameBuf[rowOff + px];
                final int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
                pixels.setPixelABGR(px, py, abgr);
            }
        }
        texture.upload();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Either grave or the configured menu key closes the screen. Grave is a dead key
        // on several keyboard layouts, so the rebindable key is the layout-independent way
        // out.
        if (event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT || LatteDoomClient.isBootKey(event)) {
            onClose();
            return true;
        }
        if (host != null) {
            final ScanCode sc = DoomKeyMap.get(event.key());
            if (sc != null) {
                host.postKey(sc, true);
            }
        }
        return true; // nothing leaks through to Minecraft
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (host != null && event.key() != GLFW.GLFW_KEY_GRAVE_ACCENT) {
            final ScanCode sc = DoomKeyMap.get(event.key());
            if (sc != null) {
                host.postKey(sc, false);
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return true; // swallowed; DOOM works on key events alone
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (host == null) {
            return;
        }
        if (Double.isNaN(lastMouseX)) {
            lastMouseX = x;
            lastMouseY = y;
            return;
        }
        final double scale = Minecraft.getInstance().getWindow().getGuiScale();
        final double dx = (x - lastMouseX) * scale;
        final double dy = (y - lastMouseY) * scale;
        lastMouseX = x;
        lastMouseY = y;
        if (dx != 0 || dy != 0) {
            host.postMouse(dx, dy, mouseButtons);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        mouseButtons |= doomButtonBit(event.button());
        if (host != null) {
            host.postMouse(0, 0, mouseButtons);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        mouseButtons &= ~doomButtonBit(event.button());
        if (host != null) {
            host.postMouse(0, 0, mouseButtons);
        }
        return true;
    }

    private static int doomButtonBit(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> event_t.MOUSE_LEFT;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> event_t.MOUSE_RIGHT;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> event_t.MOUSE_MID;
            default -> 0;
        };
    }
}
