package com.blackwithersteve.lattedoom.menu;

import com.blackwithersteve.lattedoom.LatteDoomClient;
import com.blackwithersteve.lattedoom.render.LatteHud;
import com.blackwithersteve.lattedoom.render.LatteWorld;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu's pages. Each is some art and a list of rows; {@link LatteMenu} owns the
 * cursor, the mouse and Back.
 *
 * Geometry follows m_menu.c: M_DOOM at (94,2) with rows at x=97 from y=64, episode and
 * skill at x=48 from y=63, load and save slots at x=80 from y=54. A plain row is one
 * LINEHEIGHT, a slider row two, with the thermometer on the second.
 */
public final class Pages {

    /** Chosen on the episode page, read by the skill page. */
    private static int chosenEpisode = 1;

    // ---- the image pages ----

    public static MenuPage main() {
        return MenuPage.builder()
            .deco("m_doom", "DOOM", 94, 2)
            .at(97, 64)
            .add(new MenuItem.Sub("m_ngame", "NEW GAME", Pages::newGame))
            .add(new MenuItem.Sub("m_option", "OPTIONS", Pages::options))
            .add(new MenuItem.Sub("m_loadg", "LOAD GAME", Pages::load))
            .add(new MenuItem.Sub("m_saveg", "SAVE GAME", Pages::save,
                LatteDoomClient::canSave, MenuGeom.LINEHEIGHT))
            // 4px of gap before the rows this port adds, which have no menu graphic
            .add(new MenuItem.Sub("m_rdthis", "READ THIS!", Pages::readThis,
                () -> true, MenuGeom.LINEHEIGHT + 4))
            .add(new MenuItem.Sub(null, "WADS", Pages::wads))
            .build();
    }

    /** The three-column multi-select picker, a grid rather than a list, so it draws and
     * drives itself as a {@link MenuBody}. */
    public static MenuPage wads() {
        return MenuPage.builder()
            .deco(null, "WAD SELECTION", 118, 10)
            .body(new WadPicker())
            .build();
    }

    /** New Game goes straight to skill when the set has no episodes to choose between. */
    private static MenuPage newGame() {
        chosenEpisode = 1;
        return LatteWorld.hasEpisodes() && episodeArt().size() > 1 ? episode() : skill();
    }

    /** An episode joins the list by having its M_EPI graphic in the loaded WAD set. */
    private static List<String> episodeArt() {
        final List<String> eps = new ArrayList<>();
        for (int e = 1; e <= 9; e++) {
            if (LatteHud.hasGfx("m_epi" + e)) {
                eps.add("m_epi" + e);
            }
        }
        return eps;
    }

    public static MenuPage episode() {
        final MenuPage.Builder b = MenuPage.builder()
            .deco("m_episod", "WHICH EPISODE?", 54, 38)
            .at(48, 63)
            // nine registered M_EPI lumps would run off the bottom of the canvas
            .visibleRows(8);
        final List<String> eps = episodeArt();
        for (int i = 0; i < eps.size(); i++) {
            final int number = i + 1;
            b.add(new MenuItem.Sub(eps.get(i), "EPISODE " + number, () -> {
                chosenEpisode = number;
                return skill();
            }));
        }
        return b.build();
    }

    public static MenuPage skill() {
        final String[] art = {"m_jkill", "m_rough", "m_hurt", "m_ultra", "m_nmare"};
        final String[] names = {"I'M TOO YOUNG TO DIE", "HEY, NOT TOO ROUGH",
            "HURT ME PLENTY", "ULTRA-VIOLENCE", "NIGHTMARE!"};
        final MenuPage.Builder b = MenuPage.builder()
            .deco("m_newg", "NEW GAME", 96, 14)
            .deco("m_skill", "CHOOSE SKILL LEVEL", 54, 38)
            .at(48, 63)
            // opens on the persisted difficulty
            .cursor(Math.max(0, Math.min(4, LatteDoomClient.doomSkill() - 1)));
        for (int i = 0; i < art.length; i++) {
            final int skill = i + 1;
            b.add(new MenuItem.Action(art[i], names[i], () -> {
                LatteDoomClient.startNewGame(chosenEpisode, skill);
                close();
            }, () -> true, MenuItem.SND_PISTOL));
        }
        return b.build();
    }

    /** HELP1 then HELP2, stepped by a press. Running out returns to the menu. */
    public static MenuPage readThis() {
        return MenuPage.builder()
            .deco(null, "READ THIS!", 0, 0)
            .scroller("help1", "help2")
            .build();
    }

    // ---- load and save ----

    public static MenuPage load() {
        return slots("m_loadg", "LOAD GAME", slot -> {
            if (!LatteDoomClient.loadGame(slot)) {
                return false;
            }
            close();
            return true;
        });
    }

    public static MenuPage save() {
        return slots("m_saveg", "SAVE GAME", slot -> {
            if (!LatteDoomClient.saveGame(slot)) {
                return false;
            }
            close(); // the engine writes on its next unfrozen tic, and closing unfreezes it
            return true;
        });
    }

    private static MenuPage slots(String titleArt, String titleText,
                                  java.util.function.IntPredicate action) {
        // read once when the page opens; the descriptions come from the .dsg headers
        final String[] descs = LatteDoomClient.saveSlots();
        final MenuPage.Builder b = MenuPage.builder()
            .deco(titleArt, titleText, 72, 28)
            .at(80, 54)
            // the loaded WAD set names the save folder
            .footer(() -> {
                final String set = "SET "
                    + LatteDoomClient.saveSetKey().toUpperCase(java.util.Locale.ROOT);
                return set.length() > 36 ? set.substring(0, 36) : set;
            });
        for (int i = 0; i < 6; i++) {
            final int slot = i;
            b.add(new MenuItem.Slot(slot, () -> descs[slot], action));
        }
        return b.build();
    }

    // ---- the option pages ----

    /**
     * Sound, laid out like UZDoom's page: continuous volume sliders reading 0.00 to 1.00
     * rather than DOOM's sixteen notches, driving this port's own audio rather than
     * Minecraft's.
     */
    public static MenuPage volume() {
        return MenuPage.builder()
            .deco("m_svol", "SOUND OPTIONS", 60, 20)
            .optionStyle()
            .add(new MenuItem.Slider(null, "SOUND VOLUME",
                LatteDoomClient::doomSfxVolume,
                v -> LatteDoomClient.setDoomVolume(false, (float) v), 0, 1, 0.05, 2))
            .add(new MenuItem.Slider(null, "MUSIC VOLUME",
                LatteDoomClient::doomMusicVolume,
                v -> LatteDoomClient.setDoomVolume(true, (float) v), 0, 1, 0.05, 2))
            .build();
    }

    /** The options tree, in UZDoom's shape: each row opens a page of related settings. */
    public static MenuPage options() {
        return MenuPage.builder()
            .deco("m_optttl", "OPTIONS", 108, 15)
            .optionStyle()
            .add(new MenuItem.Sub(null, "DISPLAY", Pages::display))
            .add(new MenuItem.Sub(null, "HUD", Pages::hud))
            .add(new MenuItem.Sub("m_svol", "SOUND", Pages::volume))
            .add(new MenuItem.Sub(null, "MESSAGES", Pages::messages))
            .add(new MenuItem.Sub(null, "MOUSE", Pages::mouse))
            .add(new MenuItem.Sub(null, "GAMEPLAY", Pages::gameplay))
            .add(new MenuItem.Sub(null, "MISCELLANEOUS", Pages::misc))
            .add(new MenuItem.Sub(null, "CREDITS", Pages::credits))
            .build();
    }

    /** What the HUD shows and how big it is. */
    public static MenuPage hud() {
        return MenuPage.builder()
            .deco(null, "HUD", 140, 20)
            .optionStyle()
            .add(new MenuItem.Choice("SCREEN SIZE",
                new String[]{"STATUS BAR", "FULLSCREEN", "NO HUD"},
                LatteDoomClient::hudSize, LatteDoomClient::setHudSize, MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Slider(null, "HUD SCALE",
                LatteDoomClient::hudScale, LatteDoomClient::setHudScale, 0.4, 2.0, 0.05, 2))
            .add(new MenuItem.Choice("CROSSHAIR",
                com.blackwithersteve.lattedoom.render.LatteHud.CROSSHAIR_NAMES,
                LatteDoomClient::crosshair, LatteDoomClient::setCrosshair,
                MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Slider(null, "CROSSHAIR SCALE",
                LatteDoomClient::crosshairScale, LatteDoomClient::setCrosshairScale,
                0.2, 4.0, 0.1, 1))
            .add(new MenuItem.Toggle("CROSSHAIR SHOWS HEALTH",
                LatteDoomClient::crosshairHealth, LatteDoomClient::setCrosshairHealth,
                MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Toggle("LEVEL STATS",
                LatteDoomClient::levelStats, LatteDoomClient::setLevelStats,
                MenuGeom.LINEHEIGHT))
            .build();
    }

    /** The engine's own heads-up messages — pickups, keys, secrets. */
    public static MenuPage messages() {
        return MenuPage.builder()
            .deco(null, "MESSAGES", 124, 20)
            .optionStyle()
            .add(new MenuItem.Toggle("SHOW MESSAGES",
                LatteDoomClient::showMessages, LatteDoomClient::setShowMessages,
                MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Slider(null, "MESSAGE TIME",
                LatteDoomClient::messageTime, LatteDoomClient::setMessageTime,
                1.0, 10.0, 0.5, 1))
            .add(new MenuItem.Slider(null, "LINES ON SCREEN",
                () -> LatteDoomClient.messageLines(),
                v -> LatteDoomClient.setMessageLines((int) v), 1, 8, 1, 0))
            .build();
    }

    /** Mouse behaviour. */
    public static MenuPage mouse() {
        return MenuPage.builder()
            .deco(null, "MOUSE", 132, 20)
            .optionStyle()
            .add(new MenuItem.Toggle("FREE LOOK",
                LatteDoomClient::freelook, LatteDoomClient::setFreelook,
                MenuGeom.LINEHEIGHT))
            // the source-port rule: the mouse turns you but never walks you forward
            .add(new MenuItem.Toggle("NO VERTICAL MOVEMENT",
                LatteDoomClient::novert, LatteDoomClient::setNovert,
                MenuGeom.LINEHEIGHT))
            .build();
    }

    /** Things that change how the port behaves rather than how it looks. */
    public static MenuPage misc() {
        return MenuPage.builder()
            .deco(null, "MISCELLANEOUS", 110, 20)
            .optionStyle()
            .add(new MenuItem.Toggle("PAUSE WITH MENU",
                LatteDoomClient::pauseMinecraft, LatteDoomClient::setPauseMinecraft,
                MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Toggle("PLACE BLOCKS IN LEVELS",
                LatteDoomClient::placeBlocks, LatteDoomClient::setPlaceBlocks,
                MenuGeom.LINEHEIGHT))
            .build();
    }

    /** Everything that changes how the world is drawn. */
    public static MenuPage display() {
        return MenuPage.builder()
            .deco(null, "DISPLAY", 132, 20)
            .at(40, 44)
            .valueX(230)
            .optionStyle()
            // light units added to every sector, 0 to 128 in steps of 1 rather than the
            // four coarse notches it used to be
            .add(new MenuItem.Slider(null, "LIGHT BOOST",
                () -> LatteDoomClient.lightBoost(), v -> LatteDoomClient.setLightBoost((int) v),
                0, 128, 1, 0))
            // id's own five gamma tables; the light chain reads them directly, so the
            // steps are DOOM's rather than an arbitrary limit
            .add(new MenuItem.Slider(null, "GAMMA",
                () -> LatteDoomClient.gamma(), v -> LatteDoomClient.setGamma((int) v),
                0, 4, 1, 0))
            // setDoomLight disposes the persistent buffers, so encodings never mix
            .add(new MenuItem.Toggle("DOOM LIGHTING",
                com.blackwithersteve.lattedoom.render.LatteMesh::doomLight,
                com.blackwithersteve.lattedoom.render.LatteMesh::setDoomLight,
                MenuGeom.LINEHEIGHT))
            // NOT a menu row: the BSP mesh is an unfinished renderer with measured geometry
            // divergences (bspProbe: E3M2 sector 43 off by 92%), which show up in play as
            // missing triangles. A row in the options makes it look like a supported
            // setting. It stays on /bsp, where turning it on is a deliberate act.
            .build();
    }

    /** How the game plays and what the HUD reports. The four rows below come from
     * Crispy Doom's crispness page. */
    public static MenuPage gameplay() {
        return MenuPage.builder()
            .deco(null, "GAMEPLAY", 128, 20)
            .optionStyle()
            // the skill every warp and suit boot runs on; New Game also sets it
            .add(new MenuItem.Choice("DEFAULT SKILL",
                new String[]{"I'M TOO YOUNG TO DIE", "HEY, NOT TOO ROUGH",
                    "HURT ME PLENTY", "ULTRA-VIOLENCE", "NIGHTMARE!"},
                () -> Math.max(0, Math.min(4, LatteDoomClient.doomSkill() - 1)),
                v -> LatteDoomClient.setDoomSkill(v + 1), MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Choice("WEAPON BOB", new String[]{"100%", "75%", "OFF"},
                LatteDoomClient::bobScale, LatteDoomClient::setBobScale,
                MenuGeom.LINEHEIGHT))
            // Minecraft melee against engine monsters, as a percentage of the default
            .add(new MenuItem.Slider(null, "MELEE DAMAGE",
                () -> LatteDoomClient.meleeScalePercent(),
                v -> LatteDoomClient.setMeleeScalePercent((int) v), 25, 400, 5, 0))
            .build();
    }

    /** The sources this port builds on. Captions only, so the page has no cursor. */
    public static MenuPage credits() {
        return MenuPage.builder()
            .deco(null, "CREDITS", 130, 20)
            .optionStyle()
            .add(new MenuItem.Header("ENGINE", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("MOCHA DOOM", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("CHOCOLATE DOOM", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("ID SOFTWARE", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("MENU AND HUD", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("GZDOOM AND UZDOOM", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("CRISPY DOOM", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("COMPATIBILITY", MenuGeom.LINEHEIGHT))
            .add(new MenuItem.Header("WOOF AND NUGGET DOOM", MenuGeom.LINEHEIGHT))
            .build();
    }

    private static void close() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    private Pages() {
    }
}
