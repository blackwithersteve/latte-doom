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
 * The DOOM display: a fullscreen-ish 4:3 view of the engine's framebuffer,
 * cursor captured, every key forwarded into the engine — except grave (`),
 * which returns to Minecraft. Closing the screen freezes the engine at the
 * next frame; reopening resumes exactly where you left it.
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
    /** Auto-opened intermission/finale view: shows the engine's own WI tally (kills/items/
     * secrets, par time, "entering...") and closes ITSELF the moment the next level starts —
     * the level-advance delivery then places the player at the new map's start. */
    private final boolean autoIntermission;
    /** In-level "menu only" view (the boot key must never dump the player into playing
     * flat-screen DOOM — the WORLD is the renderer): opens with the engine's own Esc menu
     * already up, and closes itself once the player backs out of the menu. */
    private final boolean menuOnly;
    private int escReleaseIn;   // synthetic Esc key-up countdown (held across a tic boundary)
    private int menuCloseGrace; // ticks !menuactive must persist before self-close (lets a
                                // menu New Game's map switch land first — the warp-in lane)

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
        // the auto view exists only for the between-levels stretch: the next map going
        // live (GS_LEVEL) hands the player back to the world (delivery to the new start)
        if (autoIntermission && host != null && host.gamestateKind() == 0) {
            onClose();
            return;
        }
        if (escReleaseIn > 0 && host != null && --escReleaseIn == 0) {
            host.postKey(ScanCode.SC_ESCAPE, false); // finish the synthetic menu-open press
        }
        if (menuOnly && host != null && escReleaseIn == 0) {
            // back out of the menu = back to the world. The grace window lets a New Game
            // picked in the menu load its map first (the warp-in lane closes us instead).
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
        // Capture the pointer FPS-style. Minecraft released it when this screen
        // opened; DOOM wants raw deltas and no visible cursor.
        GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(),
            GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        lastMouseX = Double.NaN;
        lastMouseY = Double.NaN;
        if (menuOnly && host != null) {
            // open straight INTO the engine's own menu (held across a tic boundary so
            // G_Responder actually sees it — same trick as the weapon-slot keys)
            host.postKey(ScanCode.SC_ESCAPE, true);
            escReleaseIn = 2;
            menuCloseGrace = 10; // don't judge "menu closed" before the engine even saw Esc
        }
    }

    @Override
    public void removed() {
        GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(),
            GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        // The world never auto-freezes — closing the screen just returns you to
        // Minecraft while the level keeps living around you. Only the explicit
        // /doomwatch pauses the engine. But do
        // release any keys DOOM still thinks are held, or the marine walks forever.
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

        // DOOM renders 16:10 pixels that a CRT stretched to 4:3 — honour that.
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

    /** Pull the newest engine frame into the texture; skip if nothing new. */
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
        // grave OR the boot keybind itself closes (grave is a dead key on German QWERTZ —
        // the rebindable boot key is the layout-safe way out)
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
