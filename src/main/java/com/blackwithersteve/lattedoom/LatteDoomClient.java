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
 * Latte Doom — DOOM (1993) with Minecraft as its source port.
 * Boot with the keybind (default: =) or /doom.
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
    private static KeyMapping gammaKey;
    private static boolean plusWasDown, minusWasDown;
    private static boolean pausedByScreen;
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
            {"/load <file>", "load anything: a game, a patch WAD or a .deh"},
            {"/load", "list what is in the wads folder"},
            {"/pwad <wads|none>", "stack extra patch WADs by hand"},
            {"/warp <map>", "go to a map"},
            {"/doommarine", "become the marine, or change back"},
            {"/doomleave", "return to the overworld"},
            {"/doomvolume", "the mod's own sound and music levels"},
            {"/doomgamma [0-4]", "brightness, DOOM's own gamma (also F10)"},
            {"/lgive <what>", "give yourself weapons, ammo or keys"},
        };
        final String[][] adv = {
            {"/doomcheat <code>", "type a cheat into the engine"},
            {"/doomstart", "teleport to the map's own start"},
            {"/doomdemo [1-3]", "play a recorded attract demo"},
            {"/doomwatch", "freeze or resume the engine"},
            {"/doomscreen", "the engine's own framebuffer"},
            {"/doomdiag", "write the diagnostic log"},
            {"/cull, /persist, /bsp", "renderer toggles"},
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
                + " Run §e/load " + p.getFileName() + "§r to apply it over the loaded game.";
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
     * actually played rather than entered as it stands. */
    private static boolean suitEngine;

    /** Read access to the live config, for the picker to mirror the loaded set. */
    public static LatteDoomConfig configView() {
        return config;
    }

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

    /** This client's own engine (the marine suit / the world when we own it), or null. */
    public static DoomHost host() {
        return host;
    }

    /** DOOM's dedicated SFX level (0..1) — the spectator SFX path (DoomSfx) reads this so
     * remote/world sounds obey the same slider as the local suit. */
    public static float doomSfxVolume() {
        return config != null ? config.doomSfxVolume : 1f;
    }

    /** The persisted DOOM skill (1-5) — every warp and suit boot runs on it. */
    public static int doomSkill() {
        return config != null ? config.doomSkill : 3;
    }

    /** DOOM's dedicated music level (0..1). */
    public static float doomMusicVolume() {
        return config != null ? config.doomMusicVolume : 1f;
    }

    /** Set a DOOM audio level (0..1), persist it, and push it live to the engine.
     * music=true targets music, else SFX. Returns the clamped value applied. */
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
        // push immediately so the change is audible now (the next tick would too, but this
        // makes a mid-song music drag apply without waiting on the tick's diff-gate)
        if (host != null) {
            host.setVolumes(Math.round(config.doomSfxVolume * 15f),
                Math.round(config.doomMusicVolume * 15f));
        }
        return clamped;
    }

    /** Open the authentic DOOM Sound Volume menu (bare /doomvolume, keybind, or the
     * native menu's Options entry). */
    static void openVolume(Minecraft client) {
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        client.gui.setScreen(new LatteVolumeScreen(config));
    }

    static void diagCmd(String cmd) {
        com.blackwithersteve.lattedoom.diag.DoomDiag.logNow("cmd", cmd);
    }

    /** /load by map NAME ("e1m1", "map07", or a bare warp number). */
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
        // the commonest mistake. A bare number is exempt: it is the engine's own warp
        // argument and means the same thing in both schemes.
        //
        // The scheme is read from the configured WAD file rather than from a level that
        // happens to be raised. Asking the loaded level meant that before anything had been
        // raised the answer defaulted to the DOOM 1 layout, so the first warp of a session
        // was told to use E1M1 even with a DOOM II WAD loaded.
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

    /** Tab-complete map names from the LOADED WAD set's actual map lumps. */
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

    /** Tab-complete .wad files (per-token inside the greedy pwad list, like /gamemode sur-). */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestWads(com.mojang.brigadier.context.CommandContext<?> ctx,
                        com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        final String remaining = builder.getRemaining();
        // complete the LAST token; keep what's already typed before it
        final int cut = Math.max(remaining.lastIndexOf(' '), remaining.lastIndexOf(','));
        final String prefix = cut >= 0 ? remaining.substring(0, cut + 1) : "";
        final String token = remaining.substring(cut + 1).toLowerCase(java.util.Locale.ROOT);
        try {
            final java.util.Set<String> names = new java.util.TreeSet<>();
            for (java.nio.file.Path dir : List.of(config.dataDir, config.dataDir.resolve("pwads"))) {
                if (java.nio.file.Files.isDirectory(dir)) {
                    try (var s = java.nio.file.Files.list(dir)) {
                        s.map(pth -> pth.getFileName().toString())
                            .filter(n -> {
                                final String l = n.toLowerCase(java.util.Locale.ROOT);
                                return l.endsWith(".wad") || l.endsWith(".deh")
                                    || l.endsWith(".bex");
                            })
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

    /**
     * /load — the one loading command. A full game boots on its own; a patch WAD finds
     * its own base game, companion parts and matching .deh; a .deh/.bex file applies over
     * the loaded game. Nothing from a previous load survives except what this load names.
     */
    private static void loadAny(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src,
            String name) {
        loadAny((java.util.function.Consumer<Component>) src::sendFeedback, name);
    }

    /** The same seamless load with feedback to any sink — the WAD picker screen's entry. */
    public static void loadAny(java.util.function.Consumer<Component> fb, String name) {
        diagCmd("/load " + name);
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        final java.nio.file.Path p = findWad(name.trim());
        if (p == null) {
            fb.accept(Component.literal("§cNo file named §e" + name.trim()
                + "§c.§r Put it in §econfig/latte-doom/§r (or its §epwads/§r folder),"
                + " or give a full path. §e/load§r alone lists what is there."));
            return;
        }
        final String lower = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".deh") || lower.endsWith(".bex")) {
            loadDehFile(fb, p);
            return;
        }
        final String why = wadRejection(p);
        if (why != null) {
            fb.accept(Component.literal("§c" + why));
            return;
        }
        final String other = LatteDoomConfig.foreignGame(p);
        if (other != null) {
            fb.accept(Component.literal("§e" + p.getFileName() + "§c is "
                + other + ", not DOOM.§r This is a DOOM source port and cannot run it."
                + " Load §eDOOM.WAD§r or §eDOOM2.WAD§r instead."));
            return;
        }
        if (LatteDoomConfig.isIwadFile(p)) {
            loadBase(fb, p);
        } else {
            loadPatch(fb, p);
        }
    }

    /** A full game: it IS the whole set. Whatever was stacked before is dropped, and
     * said so — the silent carry-over is how a smoothing patch ended up inside TNT. */
    private static void loadBase(java.util.function.Consumer<Component> fb,
                                 java.nio.file.Path p) {
        final List<java.nio.file.Path> dropped = new ArrayList<>(config.pwads);
        config.iwadPath = p;
        config.pwads.clear();
        config.dehs.clear();
        pairDehs(List.of(p));
        config.save();
        applyWadSet();
        final StringBuilder msg = new StringBuilder("§6load:§f ").append(p.getFileName())
            .append(" §7(").append(LatteDoomConfig.mapCount(p)).append(" maps)§r");
        for (java.nio.file.Path d : config.dehs) {
            msg.append(" §7+ ").append(d.getFileName()).append("§r");
        }
        fb.accept(Component.literal(msg.toString()));
        if (!dropped.isEmpty()) {
            final StringBuilder db = new StringBuilder("§7Dropped: ");
            for (int i = 0; i < dropped.size(); i++) {
                db.append(i > 0 ? ", " : "").append(dropped.get(i).getFileName());
            }
            db.append("§r");
            fb.accept(Component.literal(db.toString()));
        }
    }

    /** A patch WAD: assemble the whole set around it — base game by map scheme,
     * companion parts by name, matching .deh files. */
    private static void loadPatch(java.util.function.Consumer<Component> fb,
                                 java.nio.file.Path p) {
        final char fam = LatteDoomConfig.mapScheme(p);
        final java.nio.file.Path base = pickBase(fam);
        if (base == null) {
            fb.accept(Component.literal("§c"
                + (fam == 'E' ? "DOOM.WAD" : "DOOM2.WAD") + " not present."));
            return;
        }
        final List<java.nio.file.Path> bundle = withCompanions(p);
        config.iwadPath = base;
        config.pwads.clear();
        config.pwads.addAll(bundle);
        config.dehs.clear();
        pairDehs(bundle);
        config.save();
        applyWadSet();
        final StringBuilder msg = new StringBuilder("§6load:§f ");
        for (int i = 0; i < bundle.size(); i++) {
            msg.append(i > 0 ? " + " : "").append(bundle.get(i).getFileName());
        }
        for (java.nio.file.Path d : config.dehs) {
            msg.append(" §7+ ").append(d.getFileName()).append("§r");
        }
        msg.append(" §7over§f ").append(base.getFileName());
        fb.accept(Component.literal(msg.toString()));
    }

    /** A standalone DEHACKED/BEX file: applies over the loaded game. */
    private static void loadDehFile(java.util.function.Consumer<Component> fb,
                                 java.nio.file.Path p) {
        // A patch file with a WAD of the same name beside it is the WAD's loose copy:
        // the WAD embeds the same patch AND carries the sprite lumps the patch's frames
        // point at. Loading the bare file leaves those frames aimed at art that is not
        // there, so the WAD is what actually gets loaded.
        final String stem = p.getFileName().toString()
            .replaceAll("(?i)\\.(deh|bex)$", "");
        for (String cand : new String[]{stem + ".wad", stem + ".WAD"}) {
            final java.nio.file.Path wad = p.resolveSibling(cand);
            if (java.nio.file.Files.exists(wad)) {
                fb.accept(Component.literal("§7" + p.getFileName() + " belongs to §e"
                    + wad.getFileName() + "§7, loading that.§r"));
                loadPatch(fb, wad);
                return;
            }
        }
        if (!haveWad()) {
            fb.accept(Component.literal("§cA DEHACKED patch changes a game, and"
                + " none is loaded.§r Run §e/load DOOM2.WAD§r (or another game) first."));
            return;
        }
        for (java.nio.file.Path have : config.dehs) {
            if (have.toAbsolutePath().equals(p.toAbsolutePath())) {
                fb.accept(Component.literal("§e" + p.getFileName()
                    + "§r is already applied."));
                return;
            }
        }
        config.dehs.add(p);
        config.save();
        applyWadSet();
        fb.accept(Component.literal("§6deh:§f " + p.getFileName() + " §7over§f "
            + config.iwadPath.getFileName()));
    }

    /** Every usable base game in the two folders. */
    private static List<java.nio.file.Path> scanBases() {
        final List<java.nio.file.Path> bases = new ArrayList<>();
        for (java.nio.file.Path dir : List.of(config.dataDir, config.dataDir.resolve("pwads"))) {
            if (!java.nio.file.Files.isDirectory(dir)) {
                continue;
            }
            try (var s = java.nio.file.Files.list(dir)) {
                s.filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".wad"))
                    .sorted()
                    .filter(LatteDoomConfig::isIwadFile)
                    .filter(f -> LatteDoomConfig.foreignGame(f) == null)
                    .forEach(bases::add);
            } catch (java.io.IOException ignored) {
            }
        }
        return bases;
    }

    /**
     * The base game for a patch of this family: 'E' wants DOOM, 'M' wants DOOM II, 'A'
     * (no maps) keeps the loaded game or takes what is there, 'B' takes either. The plain
     * game is preferred over TNT or Plutonia, so a patch never lands on a base whose own
     * maps and textures differ from what it was built against.
     */
    private static java.nio.file.Path pickBase(char fam) {
        if (fam == 'A' && haveWad()) {
            return config.iwadPath;
        }
        final List<java.nio.file.Path> bases = scanBases();
        final String[] preferred = fam == 'E'
            ? new String[]{"DOOM.WAD", "DOOMU.WAD", "DOOM1.WAD", "FREEDOOM1.WAD"}
            : new String[]{"DOOM2.WAD", "FREEDOOM2.WAD", "TNT.WAD", "PLUTONIA.WAD"};
        for (String want : preferred) {
            for (java.nio.file.Path b : bases) {
                if (b.getFileName().toString().equalsIgnoreCase(want)) {
                    return b;
                }
            }
        }
        for (java.nio.file.Path b : bases) {
            final char s = LatteDoomConfig.mapScheme(b);
            if (fam == 'A' || fam == 'B' || s == fam || s == 'B') {
                return b;
            }
        }
        return null;
    }

    /**
     * A multi-part release ships as one name with a letter suffix (btsx_e2a + btsx_e2b)
     * and both halves load together everywhere else. Two or more same-stem siblings in
     * the file's own folder are taken as one bundle, alphabetically.
     */
    private static List<java.nio.file.Path> withCompanions(java.nio.file.Path p) {
        final String file = p.getFileName().toString();
        final int dot = file.lastIndexOf('.');
        final String stem = (dot > 0 ? file.substring(0, dot) : file)
            .toLowerCase(java.util.Locale.ROOT);
        if (stem.length() < 2 || !Character.isLetter(stem.charAt(stem.length() - 1))) {
            return List.of(p);
        }
        final String root = stem.substring(0, stem.length() - 1);
        final List<java.nio.file.Path> parts = new ArrayList<>();
        try (var s = java.nio.file.Files.list(p.getParent())) {
            s.sorted().forEach(f -> {
                final String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (n.length() == root.length() + 5 && n.startsWith(root)
                    && Character.isLetter(n.charAt(root.length())) && n.endsWith(".wad")
                    && !LatteDoomConfig.isIwadFile(f)
                    && LatteDoomConfig.foreignGame(f) == null) {
                    parts.add(f);
                }
            });
        } catch (java.io.IOException ignored) {
        }
        return parts.size() >= 2 ? parts : List.of(p);
    }

    /**
     * Loose .deh/.bex files whose name matches a WAD in the set apply with it, the way
     * source ports pair them — unless a WAD already embeds a DEHACKED lump, which is the
     * same content brought along properly.
     */
    private static void pairDehs(List<java.nio.file.Path> bundle) {
        for (java.nio.file.Path w : bundle) {
            if (LatteDoomConfig.hasDehackedLump(w)) {
                return;
            }
        }
        final List<String> stems = new ArrayList<>();
        for (java.nio.file.Path w : bundle) {
            final String f = w.getFileName().toString();
            final int dot = f.lastIndexOf('.');
            stems.add((dot > 0 ? f.substring(0, dot) : f).toLowerCase(java.util.Locale.ROOT));
        }
        for (java.nio.file.Path dir : List.of(config.dataDir, config.dataDir.resolve("pwads"))) {
            if (!java.nio.file.Files.isDirectory(dir)) {
                continue;
            }
            try (var s = java.nio.file.Files.list(dir)) {
                s.sorted().forEach(f -> {
                    final String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (!n.endsWith(".deh") && !n.endsWith(".bex")) {
                        return;
                    }
                    final String stem = n.substring(0, n.lastIndexOf('.'));
                    for (String w : stems) {
                        if ((w.startsWith(stem) || stem.startsWith(w))
                            && !config.dehs.contains(f)) {
                            config.dehs.add(f);
                            return;
                        }
                    }
                });
            } catch (java.io.IOException ignored) {
            }
        }
    }

    /** Whether a base WAD is configured — the picker opens first when none is. */
    public static boolean hasGameData() {
        return haveWad();
    }

    /** The game a patch WAD is for, when one is in the folder — the picker's
     * auto-select when a patch is chosen with no game. Null when unknown or absent. */
    public static String suggestedBaseFor(String patchName) {
        final java.nio.file.Path f = findWad(patchName);
        if (f == null) {
            return null;
        }
        final java.nio.file.Path base = pickBase(LatteDoomConfig.mapScheme(f));
        return base != null ? base.getFileName().toString() : null;
    }

    /** The picker's three columns: [0] games, [1] patch WADs, [2] patch files. */
    public static List<List<String>> pickerCategories() {
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        final List<String> games = new ArrayList<>();
        final List<String> patches = new ArrayList<>();
        final List<String> dehs = new ArrayList<>();
        for (java.nio.file.Path dir : List.of(config.dataDir, config.dataDir.resolve("pwads"))) {
            if (!java.nio.file.Files.isDirectory(dir)) {
                continue;
            }
            try (var s = java.nio.file.Files.list(dir)) {
                s.sorted().forEach(f -> {
                    final String n = f.getFileName().toString();
                    final String l = n.toLowerCase(java.util.Locale.ROOT);
                    if (l.endsWith(".deh") || l.endsWith(".bex")) {
                        dehs.add(n);
                    } else if (l.endsWith(".wad") && LatteDoomConfig.foreignGame(f) == null) {
                        (LatteDoomConfig.isIwadFile(f) ? games : patches).add(n);
                    }
                });
            } catch (java.io.IOException ignored) {
            }
        }
        return List.of(games, patches, dehs);
    }

    /**
     * The picker's LOAD: exactly the chosen files, nothing inferred. A null game keeps
     * the loaded one. Every file is re-validated here — the picker's lists are a display,
     * not an authority, and the folder can change between drawing and pressing LOAD.
     */
    public static void loadSelection(java.util.function.Consumer<Component> fb, String game,
            java.util.Collection<String> patches, java.util.Collection<String> dehFiles) {
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        java.nio.file.Path base;
        if (game != null) {
            base = findWad(game);
            if (base == null) {
                fb.accept(Component.literal("§c" + game + " not present."));
                return;
            }
            if (LatteDoomConfig.foreignGame(base) != null
                || !LatteDoomConfig.isIwadFile(base)) {
                fb.accept(Component.literal("§c" + game + " is not a game."));
                return;
            }
        } else if (haveWad()) {
            base = config.iwadPath;
        } else {
            fb.accept(Component.literal("§cNo game selected."));
            return;
        }
        final List<java.nio.file.Path> pw = new ArrayList<>();
        for (String n : patches) {
            final java.nio.file.Path f = findWad(n);
            if (f == null) {
                fb.accept(Component.literal("§c" + n + " not present."));
                return;
            }
            if (f.toAbsolutePath().equals(base.toAbsolutePath())) {
                continue;
            }
            if (LatteDoomConfig.isIwadFile(f)) {
                fb.accept(Component.literal("§c" + n + " is a game, not a patch."));
                return;
            }
            final String why = wadRejection(f);
            if (why != null) {
                fb.accept(Component.literal("§c" + why));
                return;
            }
            if (!pw.contains(f)) {
                pw.add(f);
            }
        }
        final List<java.nio.file.Path> dh = new ArrayList<>();
        for (String n : dehFiles) {
            final java.nio.file.Path f = findWad(n);
            final String l = n.toLowerCase(java.util.Locale.ROOT);
            if (f == null) {
                fb.accept(Component.literal("§c" + n + " not present."));
                return;
            }
            if (!l.endsWith(".deh") && !l.endsWith(".bex")) {
                fb.accept(Component.literal("§c" + n + " is not a patch file."));
                return;
            }
            if (!dh.contains(f)) {
                dh.add(f);
            }
        }
        config.iwadPath = base;
        config.pwads.clear();
        config.pwads.addAll(pw);
        config.dehs.clear();
        config.dehs.addAll(dh);
        config.save();
        applyWadSet();
        final StringBuilder msg = new StringBuilder("§6load:§f ")
            .append(base.getFileName());
        for (java.nio.file.Path f : pw) {
            msg.append(" + ").append(f.getFileName());
        }
        for (java.nio.file.Path f : dh) {
            msg.append(" + ").append(f.getFileName());
        }
        fb.accept(Component.literal(msg.toString()));
    }

    /** Bare /load: what is in the folders, sorted into what each file is. */
    private static void listWads(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src) {
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        final List<String> games = new ArrayList<>();
        final List<String> patches = new ArrayList<>();
        final List<String> dehs = new ArrayList<>();
        for (java.nio.file.Path dir : List.of(config.dataDir, config.dataDir.resolve("pwads"))) {
            if (!java.nio.file.Files.isDirectory(dir)) {
                continue;
            }
            try (var s = java.nio.file.Files.list(dir)) {
                s.sorted().forEach(f -> {
                    final String n = f.getFileName().toString();
                    final String l = n.toLowerCase(java.util.Locale.ROOT);
                    if (l.endsWith(".deh") || l.endsWith(".bex")) {
                        dehs.add(n);
                    } else if (l.endsWith(".wad")) {
                        if (LatteDoomConfig.foreignGame(f) != null) {
                            return; // not DOOM's; the load path explains if tried
                        }
                        if (LatteDoomConfig.isIwadFile(f)) {
                            games.add(n);
                        } else {
                            final char fam = LatteDoomConfig.mapScheme(f);
                            patches.add(n + (fam == 'E' ? " §7(DOOM)§r"
                                : fam == 'M' ? " §7(DOOM II)§r" : ""));
                        }
                    }
                });
            } catch (java.io.IOException ignored) {
            }
        }
        if (games.isEmpty() && patches.isEmpty() && dehs.isEmpty()) {
            src.sendFeedback(Component.literal("§cNothing in §econfig/latte-doom/§c yet.§r"
                + " Put a DOOM or DOOM II WAD there."));
            return;
        }
        if (!games.isEmpty()) {
            src.sendFeedback(Component.literal("§6Games:§f " + String.join(", ", games)));
        }
        if (!patches.isEmpty()) {
            src.sendFeedback(Component.literal("§6Patches:§f " + String.join(", ", patches)));
        }
        if (!dehs.isEmpty()) {
            src.sendFeedback(Component.literal("§6DEH:§f " + String.join(", ", dehs)));
        }
    }

    /** /pwad — set the PWAD list ("none" clears). Output stays one line. */
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
            // Split on commas only. Splitting on whitespace made any WAD with a space in
            // its name impossible to load, while tab-completion went on suggesting it.
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
                final String otherGame = LatteDoomConfig.foreignGame(p);
                if (otherGame != null) {
                    src.sendFeedback(Component.literal("§e" + p.getFileName() + "§c is "
                        + otherGame + ", not DOOM.§r This is a DOOM source port and cannot"
                        + " run it. The patch list is unchanged."));
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
            // Later entries override earlier ones, which is the opposite of what people
            // assume when a mapset and a texture pack disagree.
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

    /** Name resolution: absolute path, data dir, pwads/ — with .wad appended if missing. */
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

    /** DOOM's own gamma strings, so the overlay reads like F11 always has. */
    private static final String[] GAMMA_MSG = {
        doom.englsh.GAMMALVL0, doom.englsh.GAMMALVL1, doom.englsh.GAMMALVL2,
        doom.englsh.GAMMALVL3, doom.englsh.GAMMALVL4};

    private static void setGamma(int level) {
        final int lvl = Math.max(0, Math.min(4, level));
        com.blackwithersteve.lattedoom.render.LatteMesh.setGamma(lvl);
        if (config != null) {
            config.gamma = lvl;
            config.save();
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal(GAMMA_MSG[lvl]));
        }
    }

    private static void cycleGamma(Minecraft client) {
        setGamma((com.blackwithersteve.lattedoom.render.LatteMesh.gamma() + 1) % 5);
    }

    /** Interface size 0-2, read by the HUD each frame; set from the options menu and
     * the +/- keys. */
    public static int hudSize() {
        return config != null ? config.hudSize : 0;
    }

    public static void setHudSize(int size) {
        final int s = Math.max(0, Math.min(2, size));
        if (config != null && config.hudSize != s) {
            config.hudSize = s;
            config.save();
        }
    }

    public static int crosshair() {
        return config != null ? config.crosshair : 0;
    }

    public static void setCrosshair(int mode) {
        if (config != null) {
            config.crosshair = Math.max(0, Math.min(2, mode));
            config.save();
        }
    }

    public static boolean levelStats() {
        return config != null && config.levelStats;
    }

    public static void setLevelStats(boolean on) {
        if (config != null) {
            config.levelStats = on;
            config.save();
        }
    }

    public static boolean freelook() {
        return config == null || config.freelook;
    }

    public static void setFreelook(boolean on) {
        if (config != null) {
            config.freelook = on;
            config.save();
        }
    }

    public static int bobScale() {
        return config != null ? config.bobScale : 0;
    }

    public static void setBobScale(int mode) {
        if (config != null) {
            config.bobScale = Math.max(0, Math.min(2, mode));
            config.save();
        }
    }

    public static int lightBoost() {
        return com.blackwithersteve.lattedoom.render.LatteMesh.lightBoost();
    }

    public static void setLightBoost(int notches) {
        final int n = Math.max(0, Math.min(4, notches));
        com.blackwithersteve.lattedoom.render.LatteMesh.setLightBoost(n);
        if (config != null && config.lightBoost != n) {
            config.lightBoost = n;
            config.save();
        }
    }


    /** Leaving a world takes DOOM with it: the engine, the raised level and the
     * transformed state must not leak into the next world (a fresh world would
     * otherwise open with the DOOM HUD up and a level already standing). */
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
        LOGGER.info("Latte Doom initializing — Minecraft is now a DOOM source port");

        // MIDI warm-up: the FIRST MIDI device open inside the Minecraft process can stall
        // 10+ seconds on Windows (fresh provider scan + endpoint open against MC's live
        // audio stack) — that was the "/doommarine showed no HUD" window: the suit engine
        // sat in music init, never reaching the level, so there was no snapshot to draw.
        // Do the full open/close cycle ONCE, in the background, at mod init — no engine
        // can be running yet, and by the time any engine boots the path is warm. The logged
        // duration doubles as the diagnosis: a five-digit number here CONFIRMS the stall
        // was MIDI's (and that it now happens before the user ever notices).
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

        // THE WORLD-CHANGE SWEEP — this registration was MISSING: resetOnDisconnect
        // existed but nothing ever called it on an actual disconnect, so a new world in
        // the same Minecraft run inherited the old world's ENTIRE DOOM session (engine,
        // warp flags, level origin, mirror positions) — the /doommarine mid-air ghost.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
            .register((handler, client) -> client.execute(LatteDoomClient::resetOnDisconnect));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN
            .register((handler, sender, client) ->
                client.execute(() -> {
                    com.blackwithersteve.lattedoom.render.LatteWorld.fullSessionReset();
                    // Re-read on every join, not only the first. The no-game-data notice
                    // tells the player to add a WAD and rejoin, and that instruction was
                    // false while this only loaded when the settings were still null: the
                    // folder was never scanned again and the same notice printed forever.
                    final boolean hadWad = haveWad();
                    config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
                    com.blackwithersteve.lattedoom.render.LatteWorld.setPwads(config.pwads);
                    if (!hadWad && haveWad() && client.player != null) {
                        client.player.sendSystemMessage(Component.literal(
                            "§6Latte Doom§r found §e"
                            + config.iwadPath.getFileName() + "§r. Run §e/warp e1m1§r"
                            + " or press §eM§r for the menu."));
                    }
                    // scavenging table: (re)read per world join so config edits apply
                    // without a game restart
                    PickupConfig.reload(config.dataDir);
                }));
        // B2 (blocks inside levels): right-clicking a block item at the DRAWN level.
        // Vanilla only reaches this callback when its own ray hit NO real block — the
        // level is rendered geometry in a void dim, so the first block has nothing to
        // click against. Ray-march the doom mesh for the cell and let the server place.
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

        // eager IWAD scan: the join handshake (WAD disclaimer) needs to know what we
        // have before any command ever runs
        config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        com.blackwithersteve.lattedoom.render.LatteWorld.setPwads(config.pwads); // SIGIL etc. merge
        com.blackwithersteve.lattedoom.net.LatteNet.init(
            () -> config != null ? config.iwadPath : null); // marine sync + WAD handshake

        // default M (menu): a plain letter sits in the same spot on every layout — the old
        // `=` default was a dead-key position on German QWERTZ. All of these are rebindable
        // in Options -> Controls -> Key Binds under the "Latte Doom" category.
        bootKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.boot", GLFW.GLFW_KEY_M, CATEGORY));
        useKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.use", GLFW.GLFW_KEY_R, CATEGORY));
        // DOOM Sound Volume menu — unbound by default (also reachable via /doomvolume); the
        // user binds it in Controls if they want a hotkey, so we never stomp a vanilla key.
        volumeKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.volume", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        // the automap on DOOM's own Tab (rebindable; coexists with the player list —
        // that's hold-to-show, this toggles)
        automapKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.automap", GLFW.GLFW_KEY_TAB, CATEGORY));
        // gamma on F10 rather than DOOM's F11, which Minecraft owns for fullscreen
        gammaKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.lattedoom.gamma", GLFW.GLFW_KEY_F10, CATEGORY));
        com.blackwithersteve.lattedoom.render.LatteMesh.setGamma(config.gamma);
        com.blackwithersteve.lattedoom.render.LatteMesh.setLightBoost(config.lightBoost);
        com.blackwithersteve.lattedoom.render.LatteMesh.setBspMode(config.bspMesh);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // alt-tabbing must not pause the world (the DOOM engine keeps running anyway;
            // a paused MC against a live engine is the worst of both)
            if (client.options.pauseOnLostFocus) {
                client.options.pauseOnLostFocus = false;
            }
            // MARINE FORM keyboard law: Caps Lock toggles DOOM always-run (shift = hold-to-
            // run), and the Minecraft inventory does not exist — E closes itself.
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
            // a MARINE's death is DOOM's: swap Minecraft's "You Died!" screen for the DOOM
            // death — red fade over the sinking view, fire/use to rise again (reborn lane).
            // The swap fires exactly once per death, so the death scream plays here too
            // (DSPLDETH, from the player's own WAD, on the dedicated DOOM sfx slider).
            if (com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()
                && client.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) {
                client.gui.setScreen(new DoomDeathScreen());
                com.blackwithersteve.lattedoom.render.DoomSfx.play(
                    data.sounds.sfxenum_t.sfx_pldeth.ordinal(), false, 0, 0);
            }
            while (bootKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    openDoom(client);
                }
            }
            while (useKey.consumeClick()) {
                // the marine's USE: the engine's own P_UseLines at your mirrored position.
                // On someone ELSE's level the press ships upstream to THEIR engine.
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
            while (gammaKey.consumeClick()) {
                cycleGamma(client);
            }
            // THE PAUSE, tick-driven: any pausing screen freezes the engine in genuine
            // singleplayer — the DOOM menu, the volume screen, and Minecraft's own Esc
            // menu, which pauses the integrated server but never knew about the engine
            // thread. An explicit list, because intermission/finale screens must run.
            if (host != null) {
                final var scr = client.gui.screen();
                final var ssp = client.getSingleplayerServer();
                final boolean wantPause = scr != null && ssp != null
                    && ssp.getPlayerCount() <= 1
                    && !com.blackwithersteve.lattedoom.render.LatteWorld.worldIsRemoteNow(client)
                    && (scr instanceof net.minecraft.client.gui.screens.PauseScreen
                        || scr instanceof LatteMenuScreen
                        || scr instanceof LatteVolumeScreen);
                if (wantPause && !host.isFrozen()) {
                    host.setFrozen(true);
                    pausedByScreen = true;
                } else if (!wantPause && pausedByScreen) {
                    if (host.isFrozen()) {
                        host.setFrozen(false);
                    }
                    pausedByScreen = false;
                }
            }
            // no free look: the pitch INPUT is blocked (PitchLockMixin), so there is
            // nothing to fight — this only eases an already-pitched view back to level
            // once, for the moment the setting turns off mid-game.
            if (!freelook() && client.player != null
                && (com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()
                    || com.blackwithersteve.lattedoom.render.LatteWorld.playMode())) {
                final float pitch = client.player.getXRot();
                if (Math.abs(pitch) > 0.25f) {
                    client.player.setXRot(pitch * 0.7f);
                } else if (pitch != 0f) {
                    client.player.setXRot(0f);
                }
            }
            // the +/- interface-size keys, source-port style: minus = more interface,
            // equals/plus = more screen. Only in play, so typing in menus never resizes.
            if (client.gui.screen() == null
                && com.blackwithersteve.lattedoom.render.LatteWorld.playMode()) {
                final long win2 = client.getWindow().handle();
                final boolean plus = GLFW.glfwGetKey(win2, GLFW.GLFW_KEY_EQUAL) == GLFW.GLFW_PRESS;
                final boolean minus = GLFW.glfwGetKey(win2, GLFW.GLFW_KEY_MINUS) == GLFW.GLFW_PRESS;
                if (plus && !plusWasDown && hudSize() < 2) {
                    setHudSize(hudSize() + 1);
                    com.blackwithersteve.lattedoom.render.DoomSfx.play(
                        data.sounds.sfxenum_t.sfx_stnmov.ordinal(), false, 0, 0);
                }
                if (minus && !minusWasDown && hudSize() > 0) {
                    setHudSize(hudSize() - 1);
                    com.blackwithersteve.lattedoom.render.DoomSfx.play(
                        data.sounds.sfxenum_t.sfx_stnmov.ordinal(), false, 0, 0);
                }
                plusWasDown = plus;
                minusWasDown = minus;
            }
            while (automapKey.consumeClick()) {
                // the automap needs a standing level + the marine's map sense
                if (client.gui.screen() == null
                    && com.blackwithersteve.lattedoom.render.LatteWorld.map() != null
                    && com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()) {
                    com.blackwithersteve.lattedoom.render.DoomAutomap.toggle();
                }
            }
            if (com.blackwithersteve.lattedoom.render.DoomAutomap.active()) {
                // vanilla zoom: keypad +/- (layout-safe), 1.02x per tic while held
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
            // THE DOOM SHELL — intermission and finale, NATIVE: crossing an exit auto-opens
            // the Minecraft-rendered WI tally (WAD art, counting stats, pistol ticks);
            // an episode end opens the native finale (typewriter text over the flat).
            // Never the engine framebuffer. Each screen forwards presses to the engine
            // (the authority on advancing) and closes itself when the next state lands.
            // hadLevel() gates out engine-boot init states; warpedIn gates out suit-only boots.
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
                // DOOM audio runs on its OWN dedicated sliders (config.doomSfxVolume /
                // doomMusicVolume, 0..1), fully independent of Minecraft's MASTER/MUSIC — the
                // "its own sliders" ask. While SPECTATING someone else's world, the private
                // suit engine's sfx are muted — world sounds arrive over the wire instead
                // (the suit's music keeps playing: it runs the shared map's track).
                final boolean spectating =
                    com.blackwithersteve.lattedoom.render.LatteWorld.worldIsRemoteNow(client);
                // suit-only boots stay quiet on music: the overworld must not run level
                // music — the level's track plays when you're actually in a level
                // session (warped in, or spectating a shared world)
                final boolean musicOn =
                    com.blackwithersteve.lattedoom.render.LatteWorld.musicShouldPlay(client);
                host.setVolumes(
                    spectating ? 0 : Math.round(config.doomSfxVolume * 15f),
                    musicOn ? Math.round(config.doomMusicVolume * 15f) : 0);
                // form state feeds pickups, voice permissions, damage translation —
                // set unconditionally (it used to go stale outside the level)
                host.setMarineMode(com.blackwithersteve.lattedoom.render.LatteWorld.marineForm());
                host.setScavengeSprites(PickupConfig.consumableSprites());
                // the software view renders ONLY for the debug framebuffer screen — the
                // Minecraft world is the renderer (frees the engine thread = steady 35Hz)
                host.setViewRender(client.gui.screen() instanceof LatteDoomScreen);
            }
            // apply a per-instance window title when one is configured
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
            // /cull [on|off] — toggle S1 frustum culling (the A/B for the perf video).
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
            // /persist [on|off] — EXPERIMENTAL S2: draw the level from persistent GPU buffers
            // (bake-once) instead of re-emitting every vertex every frame. Default OFF (the
            // proven renderer). Toggle it to test the big perf change without risking the game.
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
            // Seamless loading — /load alone must be enough:
            //   /load                  list the folder, classified
            //   /load <file>           game, patch WAD or .deh — the set assembles itself
            //   /pwad <wads...|none>   stack extras explicitly (never required)
            //   /warp <map>            go to a map (e1m1 / map07), optional nomonsters
            // Starting a game properly = the DOOM menu (M): New Game -> episode -> skill.
            dispatcher.register(ClientCommands.literal("load")
                .executes(ctx -> {
                    listWads(ctx.getSource());
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommands.argument("wad",
                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .suggests(LatteDoomClient::suggestWads)
                    .executes(ctx -> {
                        loadAny(ctx.getSource(), com.mojang.brigadier.arguments
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
            // /doomvolume — DOOM's OWN audio sliders (0-100), independent of Minecraft's.
            //   /doomvolume                 report current levels
            //   /doomvolume sfx <0-100>     set the DOOM sound-effects level
            //   /doomvolume music <0-100>   set the DOOM music level (this is the "louder MIDI" knob)
            dispatcher.register(ClientCommands.literal("doomvolume")
                .executes(ctx -> {
                    // bare command opens the authentic DOOM Sound Volume menu
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
            // /doomgamma — DOOM's gamma correction, 0 (off) to 4; bare cycles like F11 did
            dispatcher.register(ClientCommands.literal("doomgamma")
                .executes(ctx -> {
                    cycleGamma(Minecraft.getInstance());
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommands.argument("level",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 4))
                    .executes(ctx -> {
                        setGamma(com.mojang.brigadier.arguments.IntegerArgumentType
                            .getInteger(ctx, "level"));
                        return Command.SINGLE_SUCCESS;
                    })));
            // /bsp — the experimental BSP-truth mesh (subsectors + segs); next level load
            dispatcher.register(ClientCommands.literal("bsp").executes(ctx -> {
                if (config == null) {
                    config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
                }
                config.bspMesh = !config.bspMesh;
                config.save();
                com.blackwithersteve.lattedoom.render.LatteMesh.setBspMode(config.bspMesh);
                ctx.getSource().sendFeedback(Component.literal("\u00a76bsp mesh:\u00a7f "
                    + (config.bspMesh ? "on" : "off") + " \u00a77(next level load)\u00a7r"));
                return Command.SINGLE_SUCCESS;
            }));
            // /doomdiag — flush the flight recorder to logs/lattedoom-diag.log right now
            dispatcher.register(ClientCommands.literal("doomdiag").executes(ctx -> {
                com.blackwithersteve.lattedoom.diag.DoomDiag.dump("manual /doomdiag");
                return Command.SINGLE_SUCCESS;
            }));
            // /doomscreen — DEBUG ONLY: the raw engine framebuffer (the old flat screen).
            // The shell (menu/intermission/finale) is native now; this stays for dev eyes.
            dispatcher.register(ClientCommands.literal("doomscreen").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    if (host != null && !(client.gui.screen() instanceof LatteDoomScreen)) {
                        client.gui.setScreen(new LatteDoomScreen(host, config));
                    }
                });
                return Command.SINGLE_SUCCESS;
            }));
            // /doomwatch — freeze/unfreeze the engine while NO screen is open: stand in the
            // world and watch the DOOM world run (or pause it to fly around a frozen moment).
            dispatcher.register(ClientCommands.literal("doomwatch").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> toggleWatch(client));
                return Command.SINGLE_SUCCESS;
            }));
            // /doommarine — THE transformation: DOOM weapons, pickups, view gun, marine eye.
            // Off = plain Steve: monsters still hunt you, but you fight with Minecraft means.
            dispatcher.register(ClientCommands.literal("doommarine").executes(ctx -> {
                final Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    // INSIDE a level the command toggles the form — an untransformed
                    // player on a DOOM map is allowed. The overworld side stays closed:
                    // no manual transform, no engine booted for suit play (delivery
                    // transforms, leaving reverts; the rest comes later).
                    if (!com.blackwithersteve.lattedoom.render.LatteWorld.playMode()) {
                        ctx.getSource().sendFeedback(Component.literal(
                            "Marine form is automatic: start a game from the DOOM menu."));
                        return;
                    }
                    final boolean on = !com.blackwithersteve.lattedoom.render.LatteWorld.marineForm();
                    com.blackwithersteve.lattedoom.render.LatteWorld.setMarineForm(on);
                    if (!on && host != null) {
                        // Select the fist. The engine keeps running its weapon state
                        // machine either way, and a held chainsaw loops its idle sound,
                        // which is audible with no weapon on screen.
                        host.selectWeapon("fist");
                    }
                    if (client.player != null) {
                        client.player.refreshDimensions(); // marine eye height with the form
                    }
                });
                return Command.SINGLE_SUCCESS;
            }));
            // /doomstart — convenience teleport to the level's own player-1 start. Being the
            // marine needs NO command (auto inside the level); the transformation command
            // (model change etc.) comes later per the plan.
            // /lattedoom: the basic command set. The diagnostic and experimental commands
            // are registered but deliberately absent, since they exist for development and
            // are not useful to a player.
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
                        // pull the player into the level's dimension at its start — works
                        // whether they're in the overworld or already in the void
                        com.blackwithersteve.lattedoom.render.LatteWorld.enterLevelDim(client, s[0], s[1] + 0.1, s[2]);
                    }
                });
                return Command.SINGLE_SUCCESS;
            }));
            // /doomdemo [1-3] — play one of the IWAD's recorded attract demos (-playdemo):
            // the 1993 recorded marine fights through the level, watchable in the world.
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

    /** Reboot the engine into a level: it rises around you and RUNS, screen stays closed.
     * warpNumber is the engine's -warp argument (episode*10+map, or a bare MAPxx number). */
    private static void warp(int warpNumber, boolean nomonsters) {
        warp(warpNumber, doomSkill(), nomonsters); // the persisted difficulty governs
    }

    /** The native DOOM menu's New Game: episode + map 1 at the CHOSEN difficulty —
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
                // AWAIT the old engine's teardown before booting the next: bare terminate() is
                // async, so the previous level's audio was still playing when the new engine
                // started (the previous level's monsters stayed audible).
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
            // loading while spectating someone else's level = taking over: last loader's
            // engine becomes THE world for everyone (id's co-op spirit)
            com.blackwithersteve.lattedoom.render.LatteWorld.claimWorld();
            com.blackwithersteve.lattedoom.render.LatteWorld.requestStartTeleport();
        });
    }

    /** Reboot with -playdemo demoN: the recorded marine plays; the engine quits when it ends. */
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

    /** Toggle the engine's freeze while no screen is open (live spectate vs frozen diorama). */
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

    /** Does this key event match the (rebindable) DOOM boot/menu key? Screens use it as
     * their layout-safe close toggle (grave is a dead key on German QWERTZ). */
    public static boolean isBootKey(net.minecraft.client.input.KeyEvent event) {
        return bootKey != null && bootKey.matches(event);
    }

    /** Open the DOOM MENU — the NATIVE one, rendered in Minecraft from the WAD's own art.
     * The engine
     * framebuffer never appears: outside a level the TITLEPIC backs the menu, inside the
     * world dims behind it; New Game -> episode -> skill materializes the level around
     * you. (The raw framebuffer screen survives only as /doomscreen for debugging.) */
    public static void openDoom(Minecraft client) {
        if (client.gui.screen() instanceof LatteMenuScreen) {
            return;
        }
        if (config == null) {
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
        }
        // menu art from the player's own WAD, loadable even before any level stood
        com.blackwithersteve.lattedoom.render.LatteWorld.ensureUiAssets(client, config.iwadPath);
        client.gui.setScreen(new LatteMenuScreen());
    }

    /** The -warp number for a suit engine: the shared map if we're spectating one
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
                "§6Latte Doom§r frustum culling " + (on ? "§aON§r" : "§7OFF§r")));
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
                    "§6Latte Doom§r persistent geometry "
                    + (on ? "§aON§r §7(experimental)§r" : "§7OFF§r")));
            }
        });
    }

    private static DoomHost bootHost(Minecraft client, List<String> extraArgs) {
        return bootHost(client, extraArgs, -1);
    }

    private static DoomHost bootHost(Minecraft client, List<String> extraArgs, int loadSlot) {
        if (config.iwadPath == null) {
            // maybe the WAD was dropped in AFTER launch — one fresh scan before failing
            config = LatteDoomConfig.load(FabricLoader.getInstance().getConfigDir());
            com.blackwithersteve.lattedoom.render.LatteWorld.setPwads(config.pwads);
        }
        if (config.iwadPath == null) {
            LOGGER.warn("No IWAD found in {}", config.dataDir);
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                    "§cLatte Doom has no game data.§r It is a source port, so the actual"
                    + " levels, textures and sounds live in a DOOM or DOOM II WAD, which this"
                    + " mod does not include. Put your own §eDOOM.WAD§r or §eDOOM2.WAD§r in "
                    + config.dataDir + " and try again."));
            }
            return null;
        }
        LOGGER.info("Booting DOOM: iwad={}, data={}, pwads={}, extra={}",
            config.iwadPath, config.dataDir, config.pwads, extraArgs);
        final List<String> extra = new ArrayList<>(extraArgs);
        // custom WADs (SIGIL, map packs) layered on top of the IWAD via -file, exactly
        // as the engine expects. The client mirrors the same set (LatteWorld.setPwads).
        if (!config.pwads.isEmpty()) {
            extra.add("-file");
            for (java.nio.file.Path p : config.pwads) {
                extra.add(p.toAbsolutePath().toString());
            }
        }
        // Standalone .deh/.bex patch files, applied after the WAD set's embedded lumps.
        // Set on every boot: a boot without patches must clear the previous boot's list.
        final java.util.List<String> dehFiles = new ArrayList<>();
        for (java.nio.file.Path p : config.dehs) {
            dehFiles.add(p.toAbsolutePath().toString());
        }
        mochadoom.Engine.DEH_FILES = List.copyOf(dehFiles);
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
            // Quirky cvar: plain "-novert" only prints a message; the literal
            // argument "disable" is what actually stops mouse-Y from walking you.
            extra.add("-novert");
            extra.add("disable");
        }
        // The quit/crash callbacks fire ASYNC — after a /doomwarp reboot, the OLD engine's
        // shutdown lands a beat later and must NOT null the NEW engine's hookup (that was
        // "the level appears then vanishes a split second later"). Each callback only acts
        // if the shared host still IS the host it was created for.
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
            }),
            saveSetKey(), loadSlot);
        return self[0];
    }

    // ---- savegames: per WAD set, the way the GZDoom family keys them ----

    /** Names the save folder for the CURRENT selection: iwad+pwads+dehs in load
     * order. A save belongs to the exact combination that made it — saving in DOOM
     * and loading it under DOOM II is structurally impossible. */
    public static String saveSetKey() {
        if (config == null || config.iwadPath == null) {
            return "default";
        }
        final StringBuilder k = new StringBuilder(
            config.iwadPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
        for (java.nio.file.Path p : config.pwads) {
            k.append('+').append(p.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
        }
        for (java.nio.file.Path p : config.dehs) {
            k.append('+').append(p.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
        }
        return k.toString().replaceAll("[^a-z0-9+._-]", "_");
    }

    private static java.nio.file.Path saveDir() {
        return config.dataDir.resolve("saves").resolve(saveSetKey());
    }

    /** The six slot descriptions for the current WAD set, null where no save exists.
     * Read straight off the .dsg headers (first 24 bytes), engine running or not. */
    public static String[] saveSlots() {
        final String[] out = new String[6];
        final java.nio.file.Path dir = saveDir();
        for (int i = 0; i < 6; i++) {
            final java.nio.file.Path f = dir.resolve("doomsav" + i + ".dsg");
            try {
                if (java.nio.file.Files.exists(f)) {
                    final byte[] head = new byte[24];
                    try (var in = java.nio.file.Files.newInputStream(f)) {
                        final int n = in.read(head);
                        final StringBuilder s = new StringBuilder();
                        for (int j = 0; j < n && head[j] != 0; j++) {
                            final char c = (char) (head[j] & 0xFF);
                            s.append(c >= 32 && c < 127 ? c : ' ');
                        }
                        out[i] = s.toString().trim();
                        if (out[i].isEmpty()) {
                            out[i] = "SLOT " + (i + 1);
                        }
                    }
                }
            } catch (java.io.IOException ignored) {
            }
        }
        return out;
    }

    /** Whether the engine is standing in a level — the vanilla save condition. */
    public static boolean canSave() {
        return host != null && host.state() == DoomHost.State.RUNNING
            && host.gamestateKind() == 0;
    }

    /** Menu Save: the engine must be standing in a level. The write happens on the
     * engine's next unfrozen tic (the menu closes right after, which unfreezes). */
    public static boolean saveGame(int slot) {
        if (!canSave()) {
            return false;
        }
        host.requestSave(slot, saveDescription());
        return true;
    }

    /** 24 chars max (the vanilla header field): level + date, GZDoom-style auto-name. */
    private static String saveDescription() {
        String map = com.blackwithersteve.lattedoom.render.LatteWorld.mapName();
        map = map == null ? "SAVE" : map.toUpperCase(java.util.Locale.ROOT);
        final java.time.LocalDateTime t = java.time.LocalDateTime.now();
        return String.format("%s  %02d-%02d %02d:%02d", map,
            t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute());
    }

    /** Menu Load: engine running gets an in-place G_LoadGame; a cold menu boots the
     * engine straight into the save. Either way the player is delivered onto the
     * loaded level the way a death restart re-delivers. */
    public static boolean loadGame(int slot) {
        if (!java.nio.file.Files.exists(saveDir().resolve("doomsav" + slot + ".dsg"))) {
            return false;
        }
        com.blackwithersteve.lattedoom.render.LatteWorld.requestLoadDelivery();
        if (host != null && host.state() == DoomHost.State.RUNNING) {
            host.requestLoad(slot);
        } else {
            host = bootHost(Minecraft.getInstance(), List.of(), slot);
        }
        return true;
    }
}
