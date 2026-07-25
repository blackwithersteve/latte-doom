/*
 * Copyright (C) 2017 Good Sign
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package mochadoom;

import awt.DoomWindow;
import awt.DoomWindowController;
import awt.EventBase.KeyStateInterest;
import static awt.EventBase.KeyStateSatisfaction.*;
import awt.EventHandler;
import doom.CVarManager;
import doom.CommandVariable;
import doom.ConfigManager;
import doom.DoomMain;
import static g.Signals.ScanCode.*;
import i.Strings;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Engine {

    /** Latte Doom patch: invoked on the engine thread after each completed gametic
     * (TryRunTics), which is the per-tic world-state publish. The client's interpolation
     * needs one keyframe per tic; publishing per display frame instead delivers them in
     * bursts and the resulting motion looks about 15 fps. */
    public static volatile Runnable TIC_TAP;

    /** Latte Doom patch: when false, the software view is not rendered at all. The
     * Minecraft world is the renderer, and the framebuffer is only drawn for the debug
     * screen. This removes most of the engine thread's per-frame cost, which keeps the
     * tic rate at a steady 35 Hz. */
    public static volatile boolean RENDER_VIEW = true;


    /**
     * Latte Doom patch: which player_t objects may use doomguy's voice (A_Pain
     * grunt). A plain (non-marine) possessing player keeps their own Minecraft voice:
     * per player, since players[1..3] are other Minecraft people. Set by the host.
     */
    public static volatile java.util.Set<Object> VOICED_PLAYERS = java.util.Set.of();

    /**
     * Latte Doom patch: the last damage thrust P_DamageMobj applied to a
     * possessed player: direction (unit, doom axes) and magnitude (map units/tic).
     * The mirror wipes engine momentum every frame, so the host reads this instead to
     * translate the hit into Minecraft knockback. Engine thread writes, host drains.
     */
    public static volatile Object HURT_PLAYER;
    public static volatile double HURT_DIR_X, HURT_DIR_Y, HURT_THRUST;

    /**
     * Latte Doom patch: when true (the host always sets it), P_DamageMobj can
     * never kill a player mobj: engine damage floors at 1hp and Minecraft's hearts
     * decide actual death. Closes the PST_DEAD → G_DoReborn → ga_loadlevel path that
     * wedged the tic pipeline when a possessed player got telefragged.
     */
    public static volatile boolean PLAYERS_IMMORTAL = false;

    /**
     * Latte Doom patch: incremented whenever a teleport special moves the local player
     * (players[0], never a voodoo doll), covering vanilla EV_Teleport and both Boom silent
     * variants. The possession mirror overwrites the engine player's position every frame,
     * so an engine-side teleport is undone unless the Minecraft player follows. A distance
     * heuristic misses short hops, where the teleport fog plays but the player does not
     * move. A running count cannot be missed at the 20 Hz read rate, where a boolean can.
     * Written on the engine thread; snapshot capture reads it on the same thread.
     */
    public static volatile int PLAYER_TELEPORT_COUNT;

    /**
     * Latte Doom patch: the engine hit points a lethal blow on the local possessed player
     * (players[0]) would have dealt, but which the immortality floor could not take off
     * engine health, since the engine stops at 1 hp. The host drains this each frame and
     * applies it to Minecraft health, so a low-health player still takes the killing blow
     * and dies. Added on the engine thread, only for players[0] and only at the floor site;
     * drained on the host thread. There is one engine per client.
     */
    public static final java.util.concurrent.atomic.AtomicInteger LETHAL_OVERFLOW =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Latte Doom patch: every S_StartSoundAtVolume call is reported here
     * (origin ISoundOrigin-or-null, sfx id) so the host can broadcast sound EVENTS to
     * other Minecraft players. Ids and coordinates only: never audio content.
     */
    public static volatile java.util.function.BiConsumer<Object, Integer> SOUND_TAP;

    public static void recordPlayerThrust(Object player, double dirX, double dirY, double thrust) {
        HURT_DIR_X = dirX;
        HURT_DIR_Y = dirY;
        HURT_THRUST = thrust;
        HURT_PLAYER = player; // written last: the reader keys on it
    }

    private static volatile Engine instance;

    /**
     * Latte Doom patch: when embedded in a host (Minecraft), this hook replaces the AWT
     * window's page flip. It runs on the engine thread once per rendered frame.
     */
    private static volatile Runnable frameHook = null;

    /**
     * Mocha Doom engine entry point
     */
    public static void main(final String[] argv) throws IOException {
        final Engine local;
        synchronized (Engine.class) {
            local = new Engine(argv);
        }

        // never returns
        try {
            local.DOOM.setupLoop();
        } catch(Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Latte Doom patch: construct the engine without an AWT window. The host reads pixels
     * through getDOOM().graphicSystem and posts input through getDOOM().PostEvent.
     * Must be called before any engine class statically initializes (they lazily
     * spawn a windowed Engine through getEngine() otherwise).
     */
    public static Engine initEmbedded(final Runnable hook, final String... argv) throws IOException {
        synchronized (Engine.class) {
            frameHook = hook;
            return new Engine(true, argv);
        }
    }

    public final CVarManager cvm;
    public final ConfigManager cm;
    public final DoomWindowController<?, EventHandler> windowController;
    private final DoomMain<?, ?> DOOM;

    /**
     * Latte Doom patch: headless/embedded construction, everything but the window.
     */
    private Engine(final boolean embedded, final String... argv) throws IOException {
        instance = this;

        // reads command line arguments
        this.cvm = new CVarManager(Arrays.asList(argv));

        // reads default.cfg and mochadoom.cfg
        this.cm = new ConfigManager();

        // intiializes stuff
        this.DOOM = new DoomMain<>();

        // no window: the embedding host owns display and input
        this.windowController = null;
    }

    @SuppressWarnings("unchecked")
    private Engine(final String... argv) throws IOException {
        instance = this;

        // reads command line arguments
        this.cvm = new CVarManager(Arrays.asList(argv));

        // reads default.cfg and mochadoom.cfg
        this.cm = new ConfigManager();

        // intiializes stuff
        this.DOOM = new DoomMain<>();

        // opens a window
        this.windowController = DoomWindow.createCanvasWindowController(
            DOOM.graphicSystem::getScreenImage,
            DOOM::PostEvent,
            DOOM.graphicSystem.getScreenWidth(),
            DOOM.graphicSystem.getScreenHeight()
        );

        windowController.getObserver().addInterest(
            new KeyStateInterest<>(obs -> {
                EventHandler.fullscreenChanges(windowController.getObserver(), windowController.switchFullscreen());
                return WANTS_MORE_ATE;
            }, SC_LALT, SC_ENTER)
        ).addInterest(
            new KeyStateInterest<>(obs -> {
                if (!windowController.isFullscreen()) {
                    if (DOOM.menuactive || DOOM.paused || DOOM.demoplayback) {
                        EventHandler.menuCaptureChanges(obs, DOOM.mousecaptured = !DOOM.mousecaptured);
                    } else { // can also work when not DOOM.mousecaptured
                        EventHandler.menuCaptureChanges(obs, DOOM.mousecaptured = true);
                    }
                }
                return WANTS_MORE_PASS;
            }, SC_LALT)
        ).addInterest(
            new KeyStateInterest<>(obs -> {
                if (!windowController.isFullscreen() && !DOOM.mousecaptured && DOOM.menuactive) {
                    EventHandler.menuCaptureChanges(obs, DOOM.mousecaptured = true);
                }

                return WANTS_MORE_PASS;
            }, SC_ESCAPE)
        ).addInterest(
            new KeyStateInterest<>(obs -> {
                if (!windowController.isFullscreen() && !DOOM.mousecaptured && DOOM.paused) {
                    EventHandler.menuCaptureChanges(obs, DOOM.mousecaptured = true);
                }
                return WANTS_MORE_PASS;
            }, SC_PAUSE)
        );
    }

    /**
     * Latte Doom patch: the host needs the engine object to pump events and read pixels.
     */
    public DoomMain<?, ?> getDOOM() {
        return DOOM;
    }

    /**
     * Temporary solution. Will be later moved in more detalied place
     */
    public static void updateFrame() {
        final Runnable hook = frameHook;
        if (hook != null) {
            hook.run();
            return;
        }
        instance.windowController.updateFrame();
    }

    public String getWindowTitle(double frames) {
        if (cvm.bool(CommandVariable.SHOWFPS)) {
            return String.format("%s - %s FPS: %.2f", Strings.MOCHA_DOOM_TITLE, DOOM.bppMode, frames);
        } else {
            return String.format("%s - %s", Strings.MOCHA_DOOM_TITLE, DOOM.bppMode);
        }
    }

    public static Engine getEngine() {
        Engine local = Engine.instance;
        if (local == null) {
            synchronized (Engine.class) {
                local = Engine.instance;
                if (local == null) {
                    try {
                        Engine.instance = local = new Engine();
                    } catch (IOException ex) {
                        Logger.getLogger(Engine.class.getName()).log(Level.SEVERE, null, ex);
                        throw new Error("This launch is DOOMed");
                    }
                }
            }
        }

        return local;
    }

    public static CVarManager getCVM() {
        return getEngine().cvm;
    }

    public static ConfigManager getConfig() {
        return getEngine().cm;
    }
}
