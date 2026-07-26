package com.blackwithersteve.lattedoom;

import com.blackwithersteve.lattedoom.engine.DoomHost;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Client entry point: registers the key bindings, commands, network handlers and the
 * per-tick update that keeps the engine, the world and the HUD in step.
 */
public class LatteDoomClient implements ClientModInitializer {

    public static final String MOD_ID = "lattedoom";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "lattedoom"));

    private static KeyMapping bootKey;
    private static KeyMapping useKey;
    private static KeyMapping volumeKey;
    private static KeyMapping automapKey;
    private static LatteDoomConfig config;

    /** Hands one {@code /lgive} request to the engine. {@code all} matches IDKFA's set. */
    private static void lgive(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src,
            String what, int amount) {
        if (host == null) {
            src.sendFeedback(Component.literal("§cNo engine running."));
            return;
        }
        final String w = what.toLowerCase(java.util.Locale.ROOT);
        switch (w) {
            case "all", "weapons" -> {
                for (String g : new String[]{"fist", "chainsaw", "pistol", "shotgun",
                    "supershotgun", "chaingun", "rocket", "plasma", "bfg"}) {
                    host.grant(g, 1);
                }
                if (w.equals("all")) {
                    host.grant("backpack", 1);
                    host.grant("keys", 1);
                    for (String a : new String[]{"bullets", "shells", "cells", "rockets"}) {
                        host.grant(a, -1);
                    }
                }
            }
            default -> host.grant(w, amount);
        }
    }

    private static void help(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src,
            boolean advanced) {
        final String[][] basic = {
            {"/load <wad>", "set the game data to play"},
            {"/pwad <wads|none>", "add patch WADs on top of it"},
            {"/warp <map>", "go to a map"},
            {"/doommarine", "become the marine, or change back"},
            {"/doomleave", "return to the overworld"},
            {"/doomvolume", "the mod's own sound and music levels"},
            {"/lgive <what>", "give yourself weapons, ammo or keys"},
        };
        final String[][] adv = {
            {"/doomcheat <code>", "type a cheat into the engine"},
            {"/doomstart", "teleport to the map's own start"},
            {"/doomdemo [1-3]", "play a recorded attract demo"},
            {"/doomwatch", "freeze or resume the engine"},
            {"/doomscreen", "the engine's own framebuffer"},
            {"/doomdiag", "write the diagnostic log"},
            {"/cull, /persist", "renderer toggles"},
        };
        src.sendFeedback(Component.literal(advanced
            ? "§6Latte Doom§r advanced commands:"
            : "§6Latte Doom§r commands (§e/lattedoom advanced§r for the rest):"));
        for (String[] row : (advanced ? adv : basic)) {
            src.sendFeedback(Component.literal("  §e" + row[0] + "§r  " + row[1]));
        }
        if (!advanced && !haveWad()) {
            src.sendFeedback(Component.literal(
                "§7No game data yet. Put a WAD in config/latte-doom/ and run /load.§r"));
        }
    }

    /**
     * Why this file cannot be used at all, or null when it is a WAD worth trying. Catches the
     * things players reach for that are not WADs, so both load commands answer the same way
     * instead of one refusing and the other accepting and breaking the whole set.
     */
    private static String wadRejection(java.nio.file.Path p) {
        final String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".deh") || n.endsWith(".bex")) {
            return "§e" + p.getFileName() + "§r is a DEHACKED patch, not a WAD."
                + " Load the WAD that contains it instead.";
        }
        if (n.endsWith(".zip") || n.endsWith(".pk3") || n.endsWith(".pke")
            || n.endsWith(".7z") || n.endsWith(".rar")) {
            return "§e" + p.getFileName() + "§r is an archive, not a WAD."
                + " Unzip it and load the §e.wad§r inside.";
        }
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(p)) {
            final byte[] magic = in.readNBytes(4);
            if (magic.length < 4) {
                return "§e" + p.getFileName() + "§r is too small to be a WAD.";
            }
            final String m = new String(magic, java.nio.charset.StandardCharsets.US_ASCII);
            if (!m.equals("IWAD") && !m.equals("PWAD")) {
                return "§e" + p.getFileName() + "§r is not a WAD file.";
            }
        } catch (java.io.IOException e) {
            return "§e" + p.getFileName() + "§r could not be read.";
        }
        return null;
    }

    /** Whether the running engine was booted only to supply the weapon and status bar. Such
     * an engine runs its map without monsters, so it must be rebooted before the level is
     * played rather than entered as it stands. */
    private static boolean suitEngine;

    /** Whether a base WAD is configured and present. Everything else needs one. */
    private static boolean haveWad() {
        return config != null && config.iwadPath != null
            && java.nio.file.Files.exists(config.iwadPath);
    }

    /** Refuses a command that needs game data, naming the step that provides it. */
    private static boolean needWad(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src) {
        if (haveWad()) {
            return false;
        }
        src.sendFeedback(Component.literal(
            "§cNo game data.§r Put a DOOM or DOOM II WAD in §econfig/latte-doom/§r,"
            + " then run §e/load <wad>§r."));
        return true;
    }

    /** Stops this client's engine and waits for its audio to go with it. */
    public static void stopEngine() {
        if (host != null) {
            host.terminateAndAwait(1500);
            host = null;
        }
    }

    /** The loaded settings, or null before the first load. */
    public static LatteDoomConfig config() {
        return config;
    }
    private static DoomHost host;
    private static boolean capsWasDown;
    private static int tickCount;

    /** This client's own engine instance, or null when none is running. */
    public static DoomHost host() {
        return host;
    }

    /** The mod's own sound-effect level, 0 to 1. Sounds played for a spectator read the
     * same value, so remote and local audio follow one setting. */
    public static float doomSfxVolume() {
        return config != null ? config.doomSfxVolume : 1f;
    }

    /** The persisted skill level, 1 to 5, used by every warp and every engine boot. */
    public static int doomSkill() {
        return config != null ? config.doomSkill : 3;
    }

    /** The mod's own music level, 0 to 1. */
    public static float doomMusicVolume() {
        return config != null ? config.doomMusicVolume : 1f;
    }

    /** Sets an audio level, 0 to 1, persists it and applies it to the running engine.
     * Returns the clamped value that was applied. */
    public static float setDoomVolume(boolean music, float v) {
        final float clamped = Math.max(0f, Math.min(1f, v));
        if (config == null) {
            return clamped;
        }
        if (music) {
            config.doomMusicVolume = clamped;
        } else {
            config.doomSfxVolume = clamped;
        }
        config.save();
        // Push immediately so the change is audible at once; the periodic update would
        // otherwise apply it only on the next tick.
        if (host != null) {
            host.setVolumes(Math.round(config.doomSfxVolume * 15f),
                Math.round(config.doomMusicVolume * 15f));
        }
        return clamped;
    }

    /** Opens the sound volume menu, from the command, the key binding or the menu. */
    static void openVolume(Minecraft client) {
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        client.gui.setScreen(new LatteVolumeScreen(config));
    }

    static void diagCmd(String cmd) {
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("cmd", cmd);
    }

    /** /load by map name ("e1m1", "map07", or a bare warp number). */
    private static void loadByName(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src,
            String name, boolean nomonsters) {
        diagCmd("/warp " + name + (nomonsters ? " nomonsters" : ""));
        if (needWad(src)) {
            return;
        }
        final String want = name.trim().toLowerCase(java.util.Locale.ROOT);
        final int num = parseMapName(want);
        if (num < 0) {
            src.sendFeedback(Component.literal("§c§e" + name.trim()
                + "§c is not a map name.§r Use §eE1M1§r for DOOM, §eMAP01§r for DOOM II."));
            return;
        }
        // The two games name their maps differently, and typing the other game's format is
        // the commonest mistake. The WAD itself says which scheme applies, so say so and
        // offer the equivalent rather than warping somewhere the player did not ask for.
        // A bare number is exempt: it is the engine's own warp argument and means the same
        // thing in both schemes. The scheme itself is read from the configured WAD rather
        // than from a level that happens to be raised, because asking the loaded level meant
        // the first warp of a session defaulted to the DOOM 1 layout even with a DOOM II WAD.
        final boolean bareNumber = want.matches("[0-9]{1,2}");
        final boolean episodes = LatteDoomConfig.hasEpisodes(config.iwadPath);
        final boolean gaveEpisode = want.startsWith("e");
        if (!bareNumber && gaveEpisode != episodes) {
            src.sendFeedback(Component.literal(episodes
                ? "§cThis WAD uses §eE1M1§c style names.§r Try §e/warp e1m1§r."
                : "§cThis WAD uses §eMAP01§c style names.§r Try §e/warp map01§r."));
            return;
        }
        warp(num, nomonsters);
    }

    /** "e1m5" -> 15, "map07" -> 7, "23" -> 23, anything else -> -1. */
    private static int parseMapName(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^e(\\d)m(\\d)$").matcher(s);
        if (m.matches()) {
            return Integer.parseInt(m.group(1)) * 10 + Integer.parseInt(m.group(2));
        }
        m = java.util.regex.Pattern.compile("^map(\\d{1,2})$").matcher(s);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        if (s.matches("\\d{1,2}")) {
            return Integer.parseInt(s);
        }
        return -1;
    }

    /** Completes map names from the loaded WAD set's own map lumps. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestMaps(com.mojang.brigadier.context.CommandContext<?> ctx,
                        com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        final com.blackwithersteve.lattedoom.render.WadFile wad = com.blackwithersteve.lattedoom.render.WadFile
            .cached(com.blackwithersteve.lattedoom.render.LatteWorld.wadId());
        if (wad != null) {
            final String typed = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            final java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("^(E\\dM\\d|MAP\\d\\d)$");
            final java.util.Set<String> seen = new java.util.TreeSet<>();
            for (com.blackwithersteve.lattedoom.render.WadFile.Lump l : wad.lumps) {
                if (p.matcher(l.name()).matches()) {
                    seen.add(l.name().toLowerCase(java.util.Locale.ROOT));
                }
            }
            for (String name : seen) {
                if (name.startsWith(typed)) {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
    }

    /** Completes WAD file names, one token at a time within the patch-WAD list. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestWads(com.mojang.brigadier.context.CommandContext<?> ctx,
                        com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        final String remaining = builder.getRemaining();
        // Complete the final token, preserving whatever precedes it.
        final int cut = Math.max(remaining.lastIndexOf(' '), remaining.lastIndexOf(','));
        final String prefix = cut >= 0 ? remaining.substring(0, cut + 1) : "";
        final String token = remaining.substring(cut + 1).toLowerCase(java.util.Locale.ROOT);
        try {
            final java.util.Set<String> names = new java.util.TreeSet<>();
            for (java.nio.file.Path dir : List.of(config.dataDir, config.dataDir.resolve("pwads"))) {
                if (java.nio.file.Files.isDirectory(dir)) {
                    try (var s = java.nio.file.Files.list(dir)) {
                        s.map(pth -> pth.getFileName().toString())
                            .filter(n -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".wad"))
                            .forEach(names::add);
                    }
                }
            }
            names.add("none");
            for (String name : names) {
                if (name.toLowerCase(java.util.Locale.ROOT).startsWith(token)) {
                    builder.suggest(prefix + name);
                }
            }
        } catch (Exception ignored) {
        }
        return builder.buildFuture();
    }

    /** {@code /load}: set the base WAD. Validated by {@link LatteDoomConfig#isIwadFile},
     * which requires both the {@code IWAD} header magic and a {@code PLAYPAL} lump. */
    private static void setIwad(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src,
            String name) {
        diagCmd("/load " + name);
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        final java.nio.file.Path p = findWad(name.trim());
        if (p == null) {
            src.sendFeedback(Component.literal("§cNo file named §e" + name.trim()
                + "§c.§r Put the WAD in §econfig/latte-doom/§r, or give a full path."));
            return;
        }
        final String why = wadRejection(p);
        if (why != null) {
            src.sendFeedback(Component.literal("§c" + why));
            return;
        }
        if (!LatteDoomConfig.isIwadFile(p)) {
            // A real WAD, but not one that can stand alone: either a patch WAD or a
            // resource WAD with no levels. Both are usable, through the other command.
            src.sendFeedback(Component.literal("§e" + p.getFileName()
                + "§c is not a full game.§r It has no levels of its own, or it is a patch."
                + " Load §eDOOM.WAD§r or §eDOOM2.WAD§r first, then add this with §e/pwad "
                + p.getFileName() + "§r."));
            return;
        }
        config.iwadPath = p;
        config.save();
        applyWadSet();
    }

    /** /pwad: set the PWAD list ("none" clears). Output stays one line. */
    private static void setPwads(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src,
            String list) {
        diagCmd("/pwad " + list);
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        // Patch WADs are layered on top of a base WAD. Without one there is nothing to
        // layer them onto, and they would be silently accepted and then fail at boot.
        if (!haveWad()) {
            src.sendFeedback(Component.literal(
                "§cNo base WAD set.§r Patch WADs load on top of one."
                + " Run §e/load <wad>§r first."));
            return;
        }
        final java.util.List<java.nio.file.Path> wads = new ArrayList<>();
        if (!list.trim().equalsIgnoreCase("none")) {
            // Split on commas only. Splitting on whitespace as well would make a WAD whose
            // name contains a space impossible to load, while tab completion went on
            // suggesting it.
            for (String name : list.split(",")) {
                if (name.isBlank()) {
                    continue;
                }
                final java.nio.file.Path p = findWad(name.trim());
                if (p == null) {
                    src.sendFeedback(Component.literal("§cNo file named §e" + name.trim()
                        + "§c.§r Separate several with commas, since a name may contain"
                        + " spaces: §e/pwad first.wad, second.wad§r"));
                    return;
                }
                final String why = wadRejection(p);
                if (why != null) {
                    src.sendFeedback(Component.literal("§c" + why
                        + "§r The patch list is unchanged."));
                    return;
                }
                if (LatteDoomConfig.isIwadFile(p)) {
                    // A full game cannot be layered on another: its own maps, palette and
                    // textures would override the base WAD's and leave an unplayable mix.
                    src.sendFeedback(Component.literal("§e" + p.getFileName()
                        + "§c is a full game, not a patch.§r Run §e/load " + p.getFileName()
                        + "§r to play it. The patch list is unchanged."));
                    return;
                }
                wads.add(p);
            }
        }
        config.pwads.clear();
        config.pwads.addAll(wads);
        if (wads.size() > 1) {
            // Later entries override earlier ones, which is the opposite of the common
            // assumption when a mapset and a texture pack disagree.
            src.sendFeedback(Component.literal("§7Loaded in order; later WADs override"
                + " earlier ones.§r"));
        }
        config.save();
        applyWadSet();
        final StringBuilder sb = new StringBuilder("§6pwad: §f");
        if (wads.isEmpty()) {
            sb.append("none");
        } else {
            for (int i = 0; i < wads.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(wads.get(i).getFileName());
            }
        }
        src.sendFeedback(Component.literal(sb.toString()));
    }

    /** A WAD-set change: flush the render caches, retire the engine; the menu (M) or
     * /warp boots the new set. */
    private static void applyWadSet() {
        com.blackwithersteve.lattedoom.render.LatteWorld.reloadWadSet(config.pwads);
        if (host != null) {
            host.terminateAndAwait(1500);
            host = null;
        }
    }

    /** Name resolution: absolute path, data dir, pwads/, with .wad appended if missing. */
    private static java.nio.file.Path findWad(String rawName) {
        // A pasted Windows path usually arrives wrapped in quotes, and Path.of rejects them
        // with an exception that Brigadier prints raw, naming Java rather than this mod.
        String name = rawName.trim();
        if (name.length() > 1 && ((name.startsWith("\"") && name.endsWith("\""))
            || (name.startsWith("'") && name.endsWith("'")))) {
            name = name.substring(1, name.length() - 1).trim();
        }
        try {
            return findWadUnquoted(name);
        } catch (java.nio.file.InvalidPathException bad) {
            return null;
        }
    }

    private static java.nio.file.Path findWadUnquoted(String name) {
        final java.nio.file.Path direct = java.nio.file.Path.of(name);
        if (direct.isAbsolute() && java.nio.file.Files.exists(direct)) {
            return direct;
        }
        for (java.nio.file.Path base : List.of(config.dataDir, config.dataDir.resolve("pwads"))) {
            for (String n : List.of(name, name + ".wad", name + ".WAD")) {
                final java.nio.file.Path p = base.resolve(n);
                if (java.nio.file.Files.exists(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    private static void reportDoomVolume(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src) {
        src.sendFeedback(Component.literal(String.format("§6sfx %d%%  music %d%%",
            Math.round(doomSfxVolume() * 100f), Math.round(doomMusicVolume() * 100f))));
    }


    /** Tears everything down on world exit. The engine, the raised level and the
     * transformed form must not carry into the next world, where the player would otherwise
     * arrive transformed with a level standing around them. */
    public static void resetOnDisconnect() {
        if (host != null) {
            // Await the unwind rather than firing terminate() and moving on. The engine runs
            // on its own thread and owns its audio, so an asynchronous terminate leaves that
            // thread alive across the world change and its sounds keep playing over the menu.
            host.terminateAndAwait(1500);
            host = null;
        }
        com.blackwithersteve.lattedoom.render.LatteWorld.engineQuit();
        com.blackwithersteve.lattedoom.render.LatteWorld.setMarineForm(false);
        com.blackwithersteve.lattedoom.render.LatteWorld.fullSessionReset();
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Latte Doom initializing");

        // The first MIDI device open in the process can stall for ten seconds or more,
        // and an engine booting into that stall never reaches its level. One open and close
        // cycle in the background at startup leaves the path warm before any engine boots.
        final Thread midiWarm = new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            try {
                javax.sound.midi.MidiSystem.getMidiDeviceInfo();
                final javax.sound.midi.Sequencer seq = javax.sound.midi.MidiSystem.getSequencer();
                seq.open();
                seq.close();
            } catch (Throwable ignored) {
            }
            LOGGER.info("MIDI warmed (device open/close) in {} ms", System.currentTimeMillis() - t0);
        }, "LatteDoom-MidiWarmup");
        midiWarm.setDaemon(true);
        midiWarm.start();

        // Clear all session state when leaving a world. Without this, a new world joined
        // in the same Minecraft run inherits the previous world's entire session: the
        // engine, the level origin, the warp flags and the mirrored positions, which then
        // apply to a world they do not describe.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
            .register((handler, client) -> client.execute(LatteDoomClient::resetOnDisconnect));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN
            .register((handler, sender, client) ->
                client.execute(() -> {
                    com.blackwithersteve.lattedoom.render.LatteWorld.fullSessionReset();
                    // Re-read the settings on every join, not only the first. The
                    // no-game-data notice asks the player to add a WAD and rejoin, so the
                    // folder has to be scanned again for that instruction to hold; loading
                    // only while the settings were still null left the same notice printing
                    // forever.
                    final boolean hadWad = haveWad();
                    config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
                    com.blackwithersteve.lattedoom.render.LatteWorld.setPwads(config.pwads);
                    if (!hadWad && haveWad() && client.player != null) {
                        client.player.sendSystemMessage(Component.literal(
                            "§6Latte Doom§r found §e"
                            + config.iwadPath.getFileName() + "§r. Run §e/warp e1m1§r"
                            + " or press §eM§r for the menu."));
                    }
                    // Scavenging table: (re)read per world join so config edits apply
                    // without a game restart
                    PickupConfig.reload(config.dataDir);
                }));
        // Placing blocks against the level's rendered geometry. Minecraft only reaches this
        // callback when its own ray hit no real block, which is always the case inside a
        // level, since the level is rendered geometry in an otherwise empty dimension and
        // offers no block face to click. The level mesh is therefore ray-marched for a
        // target cell and the server performs the placement.
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
            (player, world, hand) -> {
                if (!world.isClientSide()
                    || !(player instanceof net.minecraft.client.player.LocalPlayer)
                    || !world.dimension().equals(com.blackwithersteve.lattedoom.net.LatteNet.DOOM_LEVEL_DIM)
                    || !(player.getItemInHand(hand).getItem()
                        instanceof net.minecraft.world.item.BlockItem)) {
                    return net.minecraft.world.InteractionResult.PASS;
                }
                // Past this point the player is holding a block inside the level dimension,
                // so any refusal is a real one and is recorded with the gate that caused it.
                if (config == null || !config.placeBlocks) {
                    com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("place",
                        "refused: place-blocks disabled");
                    return net.minecraft.world.InteractionResult.PASS;
                }
                if (!com.blackwithersteve.lattedoom.render.LatteWorld.insideLevel(
                        player.getX(), player.getY(), player.getZ())) {
                    com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("place",
                        "refused: outside the level envelope, "
                        + com.blackwithersteve.lattedoom.render.LatteWorld.levelStateForLog());
                    return net.minecraft.world.InteractionResult.PASS;
                }
                final net.minecraft.core.BlockPos cell =
                    com.blackwithersteve.lattedoom.render.LatteWorld.raycastPlaceCell(
                        Minecraft.getInstance());
                if (cell == null) {
                    com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("place",
                        "refused: no surface within reach along the view ray");
                    return net.minecraft.world.InteractionResult.PASS;
                }
                com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("place",
                    "sent " + cell.getX() + "," + cell.getY() + "," + cell.getZ());
                com.blackwithersteve.lattedoom.net.LatteNet.sendPlaceBlock(cell,
                    hand == net.minecraft.world.InteractionHand.OFF_HAND);
                player.swing(hand);
                return net.minecraft.world.InteractionResult.SUCCESS;
            });

        // Eager WAD scan: the join handshake needs this client's base WAD before any
        // command has had a chance to run.
        config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        com.blackwithersteve.lattedoom.render.LatteWorld.setPwads(config.pwads); // SIGIL etc. merge
        com.blackwithersteve.lattedoom.net.LatteNet.init(
            () -> config != null ? config.iwadPath : null); // marine sync + WAD handshake

        // The menu key defaults to M: a plain letter occupies the same position on every
        // keyboard layout, whereas punctuation keys may be dead keys on some of them. All
        // of these bindings can be changed under Options, Controls, Key Binds, in the
        // "Latte Doom" category.
        bootKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.boot", GLFW.GLFW_KEY_M, CATEGORY));
        useKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.use", GLFW.GLFW_KEY_R, CATEGORY));
        // Sound volume menu, also reachable through /doomvolume. Unbound by default, so no
        // vanilla binding is taken; a key can be assigned under Controls.
        volumeKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.volume", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        // The automap uses the original's Tab key. It coexists with the player list, which
        // is shown while held, because this binding toggles instead.
        automapKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.automap", GLFW.GLFW_KEY_TAB, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Alt-tabbing must not pause the world: the engine keeps running regardless, and
            // a paused client against a live engine desynchronises the two.
            if (client.options.pauseOnLostFocus) {
                client.options.pauseOnLostFocus = false;
            }
            // Keyboard rules for a transformed player: Caps Lock toggles always-run (Shift is
            // hold-to-run), and the Minecraft inventory is unavailable, so E closes itself.
            final boolean caps = org.lwjgl.glfw.GLFW.glfwGetKey(client.getWindow().handle(),
                GLFW.GLFW_KEY_CAPS_LOCK) == GLFW.GLFW_PRESS;
            if (caps && !capsWasDown && com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()) {
                com.blackwithersteve.lattedoom.play.DoomMovement.toggleAutorun();
            }
            capsWasDown = caps;
            if (com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()
                && client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
                client.gui.setScreen(null);
            }
            // A transformed player gets the DOOM death screen instead of Minecraft's: a red
            // fade over the sinking view, with fire or use to respawn. The swap runs exactly
            // once per death, so the death sound (DSPLDETH, from the player's own WAD, on the
            // mod's own sfx level) is played here as well.
            if (com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()
                && client.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) {
                client.gui.setScreen(new DoomDeathScreen());
                com.blackwithersteve.lattedoom.render.DoomSfx.play(
                    data.sounds.sfxenum_t.sfx_pldeth.ordinal(), false, 0, 0, 0.4);
            }
            while (bootKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    openDoom(client);
                }
            }
            while (useKey.consumeClick()) {
                // The use action: the engine's own P_UseLines at the mirrored position.
                // On someone else's level the press ships upstream to their engine.
                if (com.blackwithersteve.lattedoom.render.LatteWorld.worldIsRemoteNow(client)) {
                    com.blackwithersteve.lattedoom.render.LatteWorld.queueRemoteUse();
                } else if (com.blackwithersteve.lattedoom.render.LatteWorld.playMode() && host != null) {
                    host.requestUse();
                }
            }
            while (volumeKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    openVolume(client);
                }
            }
            while (automapKey.consumeClick()) {
                // The automap requires both a standing level and a transformed player
                if (client.gui.screen() == null
                    && com.blackwithersteve.lattedoom.render.LatteWorld.map() != null
                    && com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()) {
                    com.blackwithersteve.lattedoom.render.DoomAutomap.toggle();
                }
            }
            if (com.blackwithersteve.lattedoom.render.DoomAutomap.active()) {
                // Vanilla zoom: keypad +/- (layout-safe), 1.02x per tic while held
                final long win = client.getWindow().handle();
                if (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_KP_ADD) == GLFW.GLFW_PRESS) {
                    com.blackwithersteve.lattedoom.render.DoomAutomap.zoom(true);
                }
                if (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_KP_SUBTRACT) == GLFW.GLFW_PRESS) {
                    com.blackwithersteve.lattedoom.render.DoomAutomap.zoom(false);
                }
                if (com.blackwithersteve.lattedoom.render.LatteWorld.map() == null
                    || !com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()) {
                    com.blackwithersteve.lattedoom.render.DoomAutomap.reset(); // level/form gone
                }
            }
            // Intermission and finale screens. Crossing an exit opens the tally drawn from
            // the WAD's own art, and finishing an episode opens the finale text; neither
            // shows the engine's framebuffer. Each screen forwards key presses to the
            // engine, which remains the authority on advancing, and closes itself once the
            // next state arrives.
            // HadLevel() excludes engine start-up states, and the warped-in flag excludes
            // engines booted only to supply weapons and the HUD.
            if (host != null && client.gui.screen() == null
                && com.blackwithersteve.lattedoom.render.LatteWorld.warpedIn()
                && !com.blackwithersteve.lattedoom.render.LatteWorld.worldIsRemoteNow(client)
                && host.hadLevel()) {
                final int gsk = host.gamestateKind();
                if (gsk == 1) {
                    client.gui.setScreen(new LatteIntermissionScreen(host));
                } else if (gsk == 2) {
                    client.gui.setScreen(new LatteFinaleScreen(host));
                }
            }
            if (host != null) {
                // DOOM audio has its own levels, independent of Minecraft's master and
                // music sliders, so the two can be balanced separately. While spectating
                // another player's world this client's own engine is muted, because the
                // world's sounds arrive over the network instead; its music continues,
                // since it plays the same map.
                final boolean spectating =
                    com.blackwithersteve.lattedoom.render.LatteWorld.worldIsRemoteNow(client);
                // An engine booted only to supply weapons and the HUD stays silent: the
                // level's music plays when the player is in a level session,
                // whether their own or one they are spectating.
                final boolean musicOn =
                    com.blackwithersteve.lattedoom.render.LatteWorld.musicShouldPlay(client);
                host.setVolumes(
                    spectating ? 0 : Math.round(config.doomSfxVolume * 15f),
                    musicOn ? Math.round(config.doomMusicVolume * 15f) : 0);
                // The player's form drives pickups, voice permissions and damage
                // translation, and is set unconditionally so it cannot go stale outside a
                // level.
                host.setMarineMode(com.blackwithersteve.lattedoom.render.LatteWorld.marineForm());
                host.setScavengeSprites(PickupConfig.consumableSprites());
                // The engine's own view is rendered only for the diagnostic framebuffer
                // screen, since the Minecraft world is the renderer. Leaving it off frees
                // the engine thread and keeps its tic rate steady.
                host.setViewRender(client.gui.screen() instanceof LatteDoomScreen);
            }
            // Window title for a second local instance, so the two can be told apart.
            if (tickCount++ % 40 == 0) {
                final String title = System.getProperty("lattedoom.title");
                if (title != null) {
                    client.getWindow().setTitle(title);
                }
            }
            // The world-space level: mirror whatever map the engine is running as true geometry.
            com.blackwithersteve.lattedoom.render.LatteWorld.clientTick(client, host,
                config != null ? config.iwadPath : null);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("doom").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> openDoom(client));
                return Command.SINGLE_SUCCESS;
            }));
            // /cull [on|off]: toggle per-sector frustum culling.
            dispatcher.register(ClientCommands.literal("cull")
                .executes(ctx -> {
                    setCull(!com.blackwithersteve.lattedoom.render.LatteWorldRenderer.CULL_ENABLED);
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    setCull(true);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    setCull(false);
                    return Command.SINGLE_SUCCESS;
                })));
            // /persist [on|off]: experimental path that draws the level from persistent GPU
            // buffers, baked once, instead of re-emitting every vertex each frame. Off by
            // default; the toggle allows the two paths to be compared at runtime.
            dispatcher.register(ClientCommands.literal("persist")
                .executes(ctx -> {
                    setPersist(!com.blackwithersteve.lattedoom.render.LatteSectorBuffers.ENABLED);
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    setPersist(true);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    setPersist(false);
                    return Command.SINGLE_SUCCESS;
                })));
            // WAD loading, following the conventions other source ports use:
            //   /load <wad>            set the base WAD
            //   /pwad <wads...|none>   set the list of patch WADs
            //   /warp <map>            go to a map, with an optional no-monsters flag
            // A full game is started from the menu instead: New Game, episode, skill.
            dispatcher.register(ClientCommands.literal("load")
                .then(ClientCommands.argument("wad",
                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .suggests(LatteDoomClient::suggestWads)
                    .executes(ctx -> {
                        setIwad(ctx.getSource(), com.mojang.brigadier.arguments
                            .StringArgumentType.getString(ctx, "wad"));
                        return Command.SINGLE_SUCCESS;
                    })));
            dispatcher.register(ClientCommands.literal("pwad")
                .then(ClientCommands.argument("wads",
                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .suggests(LatteDoomClient::suggestWads)
                    .executes(ctx -> {
                        setPwads(ctx.getSource(), com.mojang.brigadier.arguments
                            .StringArgumentType.getString(ctx, "wads"));
                        return Command.SINGLE_SUCCESS;
                    })));
            dispatcher.register(ClientCommands.literal("warp")
                .then(ClientCommands.argument("map",
                        com.mojang.brigadier.arguments.StringArgumentType.word())
                    .suggests(LatteDoomClient::suggestMaps)
                    .executes(ctx -> {
                        loadByName(ctx.getSource(), com.mojang.brigadier.arguments
                            .StringArgumentType.getString(ctx, "map"), false);
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(ClientCommands.literal("nomonsters").executes(ctx -> {
                        loadByName(ctx.getSource(), com.mojang.brigadier.arguments
                            .StringArgumentType.getString(ctx, "map"), true);
                        return Command.SINGLE_SUCCESS;
                    }))));
            // /doomvolume: DOOM's own audio sliders (0-100), independent of Minecraft's.
            //   /doomvolume                 report current levels
            //   /doomvolume sfx <0-100>     set the DOOM sound-effects level
            //   /doomvolume music <0-100>   set the music level
            dispatcher.register(ClientCommands.literal("doomvolume")
                .executes(ctx -> {
                    // With no arguments, open the sound volume menu.
                    final Minecraft client = Minecraft.getInstance();
                    client.execute(() -> openVolume(client));
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommands.literal("sfx").then(ClientCommands.argument("pct",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                    .executes(ctx -> {
                        setDoomVolume(false, com.mojang.brigadier.arguments.IntegerArgumentType
                            .getInteger(ctx, "pct") / 100f);
                        reportDoomVolume(ctx.getSource());
                        return Command.SINGLE_SUCCESS;
                    })))
                .then(ClientCommands.literal("music").then(ClientCommands.argument("pct",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                    .executes(ctx -> {
                        setDoomVolume(true, com.mojang.brigadier.arguments.IntegerArgumentType
                            .getInteger(ctx, "pct") / 100f);
                        reportDoomVolume(ctx.getSource());
                        return Command.SINGLE_SUCCESS;
                    }))));
            // /doomdiag: flush the diagnostic ring buffer to logs/lattedoom-diag.log
            dispatcher.register(ClientCommands.literal("doomdiag").executes(ctx -> {
                com.blackwithersteve.lattedoom.diag.DoomDiag.dump("manual /doomdiag");
                return Command.SINGLE_SUCCESS;
            }));
            // Diagnostic only: shows the raw engine framebuffer. The menu, intermission
            // and finale are drawn as Minecraft screens, so this is not part of normal
            // play.
            dispatcher.register(ClientCommands.literal("doomscreen").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    if (host != null && !(client.gui.screen() instanceof LatteDoomScreen)) {
                        client.gui.setScreen(new LatteDoomScreen(host, config));
                    }
                });
                return Command.SINGLE_SUCCESS;
            }));
            // Freezes and unfreezes the engine with no screen open, so the level can be
            // observed running from within the world, or paused and explored.
            dispatcher.register(ClientCommands.literal("doomwatch").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> toggleWatch(client));
                return Command.SINGLE_SUCCESS;
            }));
            // Transforms the player: DOOM weapons, DOOM pickups and the view weapon. When
            // off, monsters still pursue the player, who fights with Minecraft's own means.
            dispatcher.register(ClientCommands.literal("doommarine").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    // Without game data there is no weapon, no status bar and no sprite to
                    // draw, so transforming would only remove the vanilla interface and give
                    // nothing back. Refuse it and say why.
                    if (needWad(ctx.getSource())) {
                        return;
                    }
                    final boolean on = !com.blackwithersteve.lattedoom.render.LatteWorld.marineForm();
                    diagCmd("/doommarine -> " + (on ? "ON" : "OFF"));
                    com.blackwithersteve.lattedoom.render.LatteWorld.setMarineForm(on);
                    if (!on && host != null) {
                        // Select the fist. The engine keeps running its weapon state machine
                        // whether or not the player is transformed, and a held chainsaw loops
                        // its idle sound, which is audible with no weapon on screen.
                        host.selectWeapon("fist");
                    }
                    if (client.player != null) {
                        client.player.refreshDimensions(); // marine eye height with the form
                        // Weapons, ammunition and the status bar are engine state, so
                        // transforming without an engine running starts one. When
                        // spectating another player's world, that engine boots into the
                        // same map, so its music matches the world.
                        if (on && (host == null
                            || host.state() == DoomHost.State.QUIT
                            || host.state() == DoomHost.State.CRASHED)) {
                            // A suit boot supplies the weapon and HUD only, never a level
                            // session: no warp, no delivery and no music until in a level.
                            com.blackwithersteve.lattedoom.render.LatteWorld.suitBoot();
                            // -nomonsters: the suit only needs a level for the weapon and
                            // the status bar. A populated one leaves monsters awake and
                            // audible while the player is standing in the overworld, since
                            // the engine mixes its own sound. Warping in reboots without it.
                            host = bootHost(client, List.of("-warp",
                                Integer.toString(suitWarpNumber()),
                                "-skill", Integer.toString(doomSkill()),
                                "-nomonsters"));
                            suitEngine = true;
                            // The engine boots on its own thread and the status bar cannot
                            // draw until it publishes its first snapshot, which can take a
                            // few seconds. Say so, or the wait reads as a broken HUD.
                            ctx.getSource().sendFeedback(Component.literal(
                                "§6Latte Doom§r starting the engine, one moment."));
                        }
                    }
                });
                return Command.SINGLE_SUCCESS;
            }));
            // /lattedoom lists the basic command set. The diagnostic and experimental
            // commands are registered but left out of that listing, since they exist for
            // development and are not useful to a player.
            dispatcher.register(ClientCommands.literal("lattedoom")
                .executes(ctx -> {
                    help(ctx.getSource(), false);
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommands.literal("help").executes(ctx -> {
                    help(ctx.getSource(), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommands.literal("advanced").executes(ctx -> {
                    help(ctx.getSource(), true);
                    return Command.SINGLE_SUCCESS;
                })));

            // /lgive: engine inventory for a transformed player. Everything is granted
            // through the engine's own player fields, so the status bar, weapon switching
            // and ammunition limits behave as if the items had been picked up.
            final String[] givables = {"all", "weapons", "keys", "backpack", "health", "armor",
                "fist", "chainsaw", "pistol", "shotgun", "supershotgun", "chaingun",
                "rocket", "plasma", "bfg", "bullets", "shells", "cells", "rockets"};
            dispatcher.register(ClientCommands.literal("lgive")
                .then(ClientCommands.argument("what",
                        com.mojang.brigadier.arguments.StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (String g : givables) {
                            b.suggest(g);
                        }
                        return b.buildFuture();
                    })
                    .executes(ctx -> {
                        lgive(ctx.getSource(),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "what"), -1);
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(ClientCommands.argument("amount",
                            com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 999))
                        .executes(ctx -> {
                            lgive(ctx.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "what"),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount"));
                            return Command.SINGLE_SUCCESS;
                        }))));

            // /doomcheat: types a cheat into the engine, which runs its own matcher, so
            // every code the engine supports works without listing them here.
            dispatcher.register(ClientCommands.literal("doomcheat")
                .then(ClientCommands.argument("code",
                        com.mojang.brigadier.arguments.StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (String g : new String[]{"iddqd", "idkfa", "idfa", "idclip",
                            "idbehold", "idchoppers", "idmypos", "idclev01", "idmus01"}) {
                            b.suggest(g);
                        }
                        return b.buildFuture();
                    })
                    .executes(ctx -> {
                        final String code =
                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "code");
                        if (host == null) {
                            ctx.getSource().sendFeedback(
                                Component.literal("§cNo engine running."));
                        } else {
                            host.typeCheat(code);
                        }
                        return Command.SINGLE_SUCCESS;
                    })));

            // /doomleave: back to the overworld. A guest only leaves; the owner also takes
            // the level down for everyone, since its engine is what was driving it.
            dispatcher.register(ClientCommands.literal("doomleave").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    if (!com.blackwithersteve.lattedoom.render.LatteWorld.inLevelDim(client)) {
                        return;
                    }
                    com.blackwithersteve.lattedoom.render.LatteWorld.leaveAndClear(client);
                });
                return Command.SINGLE_SUCCESS;
            }));
            // /doomstart: teleport to the level's own player-1 start.
            dispatcher.register(ClientCommands.literal("doomstart").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    // A suit engine's map has no monsters in it. Entering it would put the
                    // player in an empty level, so reboot into the same map properly first.
                    if (suitEngine) {
                        warp(suitWarpNumber(), doomSkill(), false);
                        com.blackwithersteve.lattedoom.render.LatteWorld.requestStartTeleport();
                        return;
                    }
                    final double[] s = com.blackwithersteve.lattedoom.render.LatteWorld.playerStartWorld();
                    if (s != null && client.player != null) {
                        // Move the player into the level dimension at its start, from either
                        // the overworld or the level dimension itself
                        com.blackwithersteve.lattedoom.render.LatteWorld.enterLevelDim(client, s[0], s[1] + 0.1, s[2]);
                    }
                });
                return Command.SINGLE_SUCCESS;
            }));
            // /doomdemo [1-3]: play one of the IWAD's recorded attract demos (-playdemo),
            // watchable from inside the world.
            dispatcher.register(ClientCommands.literal("doomdemo")
                .executes(ctx -> {
                    playDemo(1);
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommands.argument("number",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3))
                    .executes(ctx -> {
                        playDemo(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "number"));
                        return Command.SINGLE_SUCCESS;
                    })));
        });
    }

    /** Reboot the engine into a level, which is raised around the player with no screen
     * opened. {@code warpNumber} is the engine's -warp argument (episode*10+map, or a bare
     * MAPxx number). */
    private static void warp(int warpNumber, boolean nomonsters) {
        warp(warpNumber, doomSkill(), nomonsters); // at the persisted skill level
    }

    /** The native DOOM menu's New Game: episode + map 1 at the chosen difficulty,
     * which persists and governs every later warp/boot until changed. */
    public static void startNewGame(int episode, int skill) {
        if (config != null) {
            config.doomSkill = Math.max(1, Math.min(5, skill));
            config.save();
        }
        // A commercial WAD numbers its maps MAPxx and has no episodes, so New Game warps to
        // map 1 directly; an ExMy WAD warps to the first map of the chosen episode.
        warp(com.blackwithersteve.lattedoom.render.LatteWorld.hasEpisodes()
            ? episode * 10 + 1 : 1, skill, false);
    }

    private static void warp(int warpNumber, int skill, boolean nomonsters) {
        final Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (config == null) {
                config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
            }
            if (!haveWad()) {
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal(
                        "§cNo game data.§r Put a DOOM or DOOM II WAD in"
                        + " §econfig/latte-doom/§r, then run §e/load <wad>§r."));
                }
                return;
            }
            if (host != null) {
                // Wait for the old engine to tear down before booting the next. terminate()
                // alone is asynchronous, which leaves the previous level's audio playing over
                // the new engine.
                host.terminateAndAwait(1500);
                host = null;
            }
            final List<String> extra = new ArrayList<>();
            extra.add("-warp");
            extra.add(Integer.toString(warpNumber));
            extra.add("-skill");
            extra.add(Integer.toString(Math.max(1, Math.min(5, skill))));
            if (nomonsters) {
                extra.add("-nomonsters");
            }
            host = bootHost(client, extra);
            suitEngine = false;
            // Loading while spectating another player's level takes ownership of it: the
            // most recent loader's engine becomes the world for everyone
            com.blackwithersteve.lattedoom.render.LatteWorld.claimWorld();
            com.blackwithersteve.lattedoom.render.LatteWorld.requestStartTeleport();
        });
    }

    /** Reboot with -playdemo demoN. The engine quits when the demo ends. */
    private static void playDemo(int number) {
        final Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (config == null) {
                config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
            }
            if (host != null) {
                host.terminateAndAwait(1500); // await teardown before the replacement boots (audio leak)
                host = null;
            }
            host = bootHost(client, List.of("-playdemo", "demo" + number));
        });
    }

    /** Toggle the engine's freeze while no screen is open. */
    private static void toggleWatch(Minecraft client) {
        if (host == null || host.state() != DoomHost.State.RUNNING) {
            if (config == null) {
                config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
            }
            host = bootHost(client, List.of());
            return;
        }
        host.setFrozen(!host.isFrozen());
    }

    /** Whether this key event matches the rebindable menu key. Screens use it to close
     * themselves independently of layout, since grave is a dead key on some of them. */
    public static boolean isBootKey(net.minecraft.client.input.KeyEvent event) {
        return bootKey != null && bootKey.matches(event);
    }

    /** Opens the DOOM menu, drawn as a Minecraft screen from the WAD's own art; the
     * engine's framebuffer is never shown. Outside a level the title screen backs the
     * menu, and inside one the world is dimmed behind it. Choosing New Game, an episode
     * and a skill raises that level around the player. */
    public static void openDoom(Minecraft client) {
        if (client.gui.screen() instanceof LatteMenuScreen) {
            return;
        }
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        // Menu art from the player's own WAD, loadable even before any level stood
        com.blackwithersteve.lattedoom.render.LatteWorld.ensureUiAssets(client, config.iwadPath);
        client.gui.setScreen(new LatteMenuScreen());
    }

    /** The warp number for a supporting engine: the shared map when spectating one
     * (e1m2 -> 12, map05 -> 5), else E1M1. */
    private static int suitWarpNumber() {
        final String rm = com.blackwithersteve.lattedoom.render.LatteWorld.remoteMapName();
        if (rm != null) {
            try {
                if (rm.startsWith("map")) {
                    return Integer.parseInt(rm.substring(3));
                }
                if (rm.length() == 4 && rm.charAt(0) == 'e' && rm.charAt(2) == 'm') {
                    return (rm.charAt(1) - '0') * 10 + (rm.charAt(3) - '0');
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 11;
    }

    private static void setCull(boolean on) {
        com.blackwithersteve.lattedoom.render.LatteWorldRenderer.CULL_ENABLED = on;
        final Minecraft c = Minecraft.getInstance();
        if (c.player != null) {
            c.player.sendSystemMessage(Component.literal(
                "§6Latte Doom§r: frustum culling " + (on ? "§aON§r" : "§7OFF§r")));
        }
    }

    private static void setPersist(boolean on) {
        final Minecraft c = Minecraft.getInstance();
        c.execute(() -> { // GPU-buffer create/dispose must run on the render thread
            com.blackwithersteve.lattedoom.render.LatteSectorBuffers.ENABLED = on;
            if (!on) {
                com.blackwithersteve.lattedoom.render.LatteSectorBuffers.dispose();
            }
            if (c.player != null) {
                c.player.sendSystemMessage(Component.literal(
                    "§6Latte Doom§r: persistent geometry "
                    + (on ? "§aON§r §7(experimental)§r" : "§7OFF§r")));
            }
        });
    }

    private static DoomHost bootHost(Minecraft client, List<String> extraArgs) {
        if (config.iwadPath == null) {
            // The WAD may have been added after launch: one fresh scan before failing
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
            com.blackwithersteve.lattedoom.render.LatteWorld.setPwads(config.pwads);
        }
        if (config.iwadPath == null) {
            LOGGER.warn("No IWAD found in {}", config.dataDir);
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                    "§cLatte Doom has no game data.§r It is a source port, so the levels,"
                    + " textures and sounds come from a DOOM or DOOM II WAD and none of it"
                    + " ships with the mod. Put §eDOOM.WAD§r or §eDOOM2.WAD§r in §e"
                    + config.dataDir + "§r and try again."));
            }
            return null;
        }
        LOGGER.info("Booting DOOM: iwad={}, data={}, pwads={}, extra={}",
            config.iwadPath, config.dataDir, config.pwads, extraArgs);
        final List<String> extra = new ArrayList<>(extraArgs);
        // Custom WADs (SIGIL, map packs) layered on top of the IWAD via -file, exactly
        // as the engine expects. The client mirrors the same set (LatteWorld.setPwads).
        if (!config.pwads.isEmpty()) {
            extra.add("-file");
            for (java.nio.file.Path p : config.pwads) {
                extra.add(p.toAbsolutePath().toString());
            }
        }
        // The engine offers several sound drivers, which differ in how they reach the audio
        // device. Its default is a software mixer; the others are selectable here so a
        // latency problem can be attributed without rebuilding.
        switch (config.soundDriver) {
            case "classic" -> extra.add("-classicsound");
            case "clip" -> extra.add("-clipsound");
            case "audiolines" -> extra.add("-audiolines");
            case "none" -> extra.add("-nosfx");
            default -> { } // "super": the engine's own default, no flag
        }
        if (config.novert) {
            // "-novert" on its own only prints a message. The "disable" argument is what
            // stops vertical mouse movement from walking the player forward and back.
            extra.add("-novert");
            extra.add("disable");
        }
        // The quit and crash callbacks fire asynchronously. After a /doomwarp reboot the old
        // engine's shutdown arrives late and must not clear the new engine's hookup, which
        // would tear the incoming level down immediately after it appeared. Each callback
        // therefore acts only while the shared host is still the one it was created for.
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("engine", "boot " + extra
            + " iwad=" + (config.iwadPath != null ? config.iwadPath.getFileName() : "none")
            + " pwads=" + config.pwads.size());
        final DoomHost[] self = new DoomHost[1];
        self[0] = DoomHost.boot(config.iwadPath, config.dataDir, extra,
            () -> client.execute(() -> {
                if (host != self[0]) {
                    return; // a newer engine already took over (warp/demo reboot)
                }
                LOGGER.info("DOOM quit normally");
                com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("engine", "quit normally");
                if (client.gui.screen() instanceof LatteDoomScreen) {
                    client.gui.setScreen(null);
                }
                host = null;
                com.blackwithersteve.lattedoom.render.LatteWorld.engineQuit();
            }),
            t -> client.execute(() -> {
                if (host != self[0]) {
                    return;
                }
                LOGGER.error("DOOM crashed", t);
                com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("error", "ENGINE CRASH: " + t);
                if (client.gui.screen() instanceof LatteDoomScreen) {
                    client.gui.setScreen(null);
                }
                host = null;
                com.blackwithersteve.lattedoom.render.LatteWorld.engineQuit();
                if (client.player != null) {
                    client.player.sendSystemMessage(
                        Component.literal("§cLatte Doom crashed:§r " + t));
                }
            }));
        return self[0];
    }
}
