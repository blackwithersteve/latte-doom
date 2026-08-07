package com.blackwithersteve.lattedoom;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Tiny properties config in config/latte-doom/latte-doom.properties.
 * The same folder is DOOM's data dir: IWAD, default.cfg, mochadoom.cfg and
 * savegames all live there, like a proper source port install.
 */
public final class LatteDoomConfig {

    private static final String[] IWAD_NAMES = {
        "DOOM.WAD", "DOOMU.WAD", "DOOM2.WAD", "PLUTONIA.WAD", "TNT.WAD",
        "DOOM1.WAD", "FREEDOOM1.WAD", "FREEDOOM2.WAD"
    };

    public final Path dataDir;
    public Path iwadPath;             // may be null if nothing found
    public boolean novert = true;     // mouse turns, but doesn't walk you forward
    public boolean pauseMinecraft = true;
    /** Dedicated DOOM audio levels (0..1), INDEPENDENT of Minecraft's own sliders — this is
     * the "its own sliders" ask. 1.0 = full. Persisted; set live via /doomvolume. */
    public float doomSfxVolume = 1f;
    public float doomMusicVolume = 1f;
    /** DOOM skill 1-5 (ITYTD..NM). The menu's New Game choice persists here and governs
     * every boot: monster spawns per-skill (P_LoadThings), damage scaling, respawn. */
    public int doomSkill = 3;
    /** DOOM gamma correction 0 (off) to 4, id's own tables, for levels that read darker
     * here than in the software renderer. */
    public int gamma = 0;
    /** Flat light raise, 0-4 notches of 16 — the options menu's Light Boost slider. */
    /** DOOM light units added to every sector, 0-128. */
    public int lightBoost = 0;
    /** Interface size: 0 = full status bar, 1 = simplified fullscreen numbers, 2 = none.
     * The +/- keys step it in play, source-port style. */
    public int hudSize = 0;
    /** Crosshair, the Crispy Doom feature: 0 = off, 1 = cross, 2 = health-colored. */
    public int crosshair = 0;
    /** Crosshair size multiplier and health colouring, as UZDoom exposes them. */
    public double crosshairScale = 1.0;
    /** The HUD's own scale, UZDoom's hud_scalefactor. */
    public double hudScale = 1.0;
    /** Engine messages: shown at all, how long they stay, and how many stack. */
    public boolean showMessages = true;
    public double messageTime = 3.0;
    public int messageLines = 4;
    public boolean crosshairHealth = false;
    /** Kills/items/secrets and level time on screen, Crispy Doom's stats widget. */
    public boolean levelStats = false;
    /** Weapon bob amount, Crispy Doom's setting: 0 = full, 1 = 75%, 2 = off. */
    public int bobScale = 0;
    /** Free look: off locks the pitch level the way the original played. */
    public boolean freelook = true;
    /** BSP mesh: geometry from the map's own subsectors and segs, the software
     * renderer's truth for trick maps. Experimental; takes effect at level load. */
    public boolean bspMesh = false;
    public boolean doomLight = true;
    /** B2: right-clicking a block item against the drawn level places real MC blocks. */
    public boolean placeBlocks = true;
    /** DOOM hit points awarded per point of Minecraft attack damage on a melee swing.
     * The plain inverse of the health conversion would be 5.0; the default is higher so
     * Minecraft weapons stay competitive with DOOM ones. Attack damage already encodes the
     * weapon tier, so every weapon scales from this one number. */
    public double meleeScale = 18.0;
    /** Which of the engine's sound drivers to boot with: {@code super} (its default, a
     * software mixer), {@code classic}, {@code clip}, {@code audiolines}, or {@code none}.
     * They differ in how they reach the audio device and therefore in latency. */
    public String soundDriver = "super";
    /** Extra WADs merged on top of the IWAD, in load order (SIGIL, custom maps…). */
    public final List<Path> pwads = new ArrayList<>();
    /** Standalone DEHACKED/BEX patch files applied after the WAD set, in order. Only ever
     * filled explicitly (a /load of a .deh, or auto-pairing with a WAD of the same name);
     * never scanned from the folder. */
    public final List<Path> dehs = new ArrayList<>();

    private LatteDoomConfig(Path dataDir) {
        this.dataDir = dataDir;
    }

    public static LatteDoomConfig load(Path configRoot) {
        final LatteDoomConfig cfg = new LatteDoomConfig(configRoot.resolve("latte-doom"));
        final Path file = cfg.dataDir.resolve("latte-doom.properties");
        final Properties props = new Properties();
        try {
            Files.createDirectories(cfg.dataDir);
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            System.err.println("[lattedoom] could not read config: " + e);
        }

        final String iwad = props.getProperty("iwad", "").trim();
        if (!iwad.isEmpty()) {
            final Path p = Path.of(iwad);
            cfg.iwadPath = p.isAbsolute() ? p : cfg.dataDir.resolve(p);
            // SELF-HEAL: a configured "iwad" that is really a PWAD (no PLAYPAL — the
            // SIGIL-as-iwad crash) is rejected and the folder scan takes over
            if (!Files.exists(cfg.iwadPath) || !isIwadFile(cfg.iwadPath)) {
                cfg.iwadPath = null;
            }
        }
        if (cfg.iwadPath == null) {
            cfg.iwadPath = scanForIwad(cfg.dataDir);
        }
        cfg.novert = Boolean.parseBoolean(props.getProperty("novert", "true"));
        cfg.pauseMinecraft = Boolean.parseBoolean(props.getProperty("pause-minecraft", "true"));
        cfg.placeBlocks = Boolean.parseBoolean(props.getProperty("place-blocks", "true"));
        try {
            cfg.meleeScale = Math.max(1.0, Math.min(100.0,
                Double.parseDouble(props.getProperty("melee-scale", "18.0"))));
        } catch (NumberFormatException ignored) {
            cfg.meleeScale = 18.0;
        }
        cfg.soundDriver = props.getProperty("sound-driver", "super")
            .trim().toLowerCase(java.util.Locale.ROOT);
        cfg.doomSfxVolume = parseVol(props.getProperty("doom-sfx-volume", "1.0"));
        cfg.doomMusicVolume = parseVol(props.getProperty("doom-music-volume", "1.0"));
        try {
            cfg.doomSkill = Math.max(1, Math.min(5,
                Integer.parseInt(props.getProperty("doom-skill", "3").trim())));
        } catch (NumberFormatException e) {
            cfg.doomSkill = 3;
        }
        try {
            cfg.gamma = Math.max(0, Math.min(4,
                Integer.parseInt(props.getProperty("gamma", "0").trim())));
        } catch (NumberFormatException e) {
            cfg.gamma = 0;
        }
        try {
            // light-boost is DOOM light units now, 0-128. It used to be four coarse
            // notches worth 32 units each, so a stored 1-4 is migrated up.
            int boost = Integer.parseInt(props.getProperty("light-boost", "0").trim());
            if (boost > 0 && boost <= 4) {
                boost *= 32;
            }
            cfg.lightBoost = Math.max(0, Math.min(128, boost));
        } catch (NumberFormatException e) {
            cfg.lightBoost = 0;
        }
        try {
            cfg.hudSize = Math.max(0, Math.min(2,
                Integer.parseInt(props.getProperty("hud-size", "0").trim())));
        } catch (NumberFormatException e) {
            cfg.hudSize = 0;
        }
        try {
            cfg.crosshair = Math.max(0, Math.min(6,
                Integer.parseInt(props.getProperty("crosshair", "0").trim())));
        } catch (NumberFormatException e) {
            cfg.crosshair = 0;
        }
        try {
            cfg.crosshairScale = Math.max(0.2, Math.min(4.0,
                Double.parseDouble(props.getProperty("crosshair-scale", "1.0").trim())));
        } catch (NumberFormatException e) {
            cfg.crosshairScale = 1.0;
        }
        try {
            cfg.hudScale = Math.max(0.4, Math.min(2.0,
                Double.parseDouble(props.getProperty("hud-scale", "1.0").trim())));
        } catch (NumberFormatException e) {
            cfg.hudScale = 1.0;
        }
        cfg.showMessages = Boolean.parseBoolean(props.getProperty("show-messages", "true"));
        try {
            cfg.messageTime = Math.max(1.0, Math.min(10.0,
                Double.parseDouble(props.getProperty("message-time", "3.0").trim())));
        } catch (NumberFormatException e) {
            cfg.messageTime = 3.0;
        }
        try {
            cfg.messageLines = Math.max(1, Math.min(8,
                Integer.parseInt(props.getProperty("message-lines", "4").trim())));
        } catch (NumberFormatException e) {
            cfg.messageLines = 4;
        }
        cfg.crosshairHealth =
            Boolean.parseBoolean(props.getProperty("crosshair-health", "false"));
        cfg.levelStats = Boolean.parseBoolean(props.getProperty("level-stats", "false"));
        cfg.freelook = Boolean.parseBoolean(props.getProperty("freelook", "true"));
        cfg.bspMesh = Boolean.parseBoolean(props.getProperty("bsp-mesh", "false"));
        cfg.doomLight = Boolean.parseBoolean(props.getProperty("doom-light", "true"));
        try {
            cfg.bobScale = Math.max(0, Math.min(2,
                Integer.parseInt(props.getProperty("weapon-bob", "0").trim())));
        } catch (NumberFormatException e) {
            cfg.bobScale = 0;
        }

        // Extra WADs, explicit order via the "pwads" property (comma-separated names,
        // relative to the pwads/ folder or absolute). These are the user's own files —
        // merged, not shipped, and layered on top of their own IWAD.
        cfg.loadPwads(props.getProperty("pwads", "").trim());
        cfg.loadDehs(props.getProperty("dehs", "").trim());

        // Write the file back so users can discover the knobs.
        props.setProperty("iwad", cfg.iwadPath != null ? cfg.iwadPath.toString() : "");
        props.setProperty("novert", Boolean.toString(cfg.novert));
        props.setProperty("pause-minecraft", Boolean.toString(cfg.pauseMinecraft));
        props.setProperty("place-blocks", Boolean.toString(cfg.placeBlocks));
        props.setProperty("melee-scale", Double.toString(cfg.meleeScale));
        props.setProperty("sound-driver", cfg.soundDriver);
        props.setProperty("doom-sfx-volume", Float.toString(cfg.doomSfxVolume));
        props.setProperty("doom-music-volume", Float.toString(cfg.doomMusicVolume));
        props.setProperty("doom-skill", Integer.toString(cfg.doomSkill));
        props.setProperty("gamma", Integer.toString(cfg.gamma));
        props.setProperty("light-boost", Integer.toString(cfg.lightBoost));
        props.setProperty("hud-size", Integer.toString(cfg.hudSize));
        props.setProperty("crosshair", Integer.toString(cfg.crosshair));
        props.setProperty("crosshair-scale", Double.toString(cfg.crosshairScale));
        props.setProperty("hud-scale", Double.toString(cfg.hudScale));
        props.setProperty("show-messages", Boolean.toString(cfg.showMessages));
        props.setProperty("message-time", Double.toString(cfg.messageTime));
        props.setProperty("message-lines", Integer.toString(cfg.messageLines));
        props.setProperty("crosshair-health", Boolean.toString(cfg.crosshairHealth));
        props.setProperty("level-stats", Boolean.toString(cfg.levelStats));
        props.setProperty("weapon-bob", Integer.toString(cfg.bobScale));
        props.setProperty("freelook", Boolean.toString(cfg.freelook));
        props.setProperty("bsp-mesh", Boolean.toString(cfg.bspMesh));
        props.setProperty("doom-light", Boolean.toString(cfg.doomLight));
        if (props.getProperty("pwads") == null) {
            props.setProperty("pwads", "");
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Latte Doom — iwad: path to DOOM.WAD (or drop it in this folder); "
                + "novert: mouse never moves you forward; pause-minecraft: freeze MC while playing; "
                + "doom-sfx-volume / doom-music-volume: 0.0-1.0, DOOM's own audio levels");
        } catch (IOException e) {
            System.err.println("[lattedoom] could not write config: " + e);
        }
        return cfg;
    }

    private static float parseVol(String s) {
        try {
            return Math.max(0f, Math.min(1f, Float.parseFloat(s.trim())));
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    /**
     * Persist the mutable runtime knobs (the volume sliders) to disk, preserving every other
     * key already in the file. load() is the only OTHER writer, so live slider changes would
     * be lost on restart without this. Synchronized: /doomvolume can fire from any thread.
     */
    public synchronized void save() {
        final Path file = dataDir.resolve("latte-doom.properties");
        final Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            System.err.println("[lattedoom] could not read config for save: " + e);
        }
        props.setProperty("iwad", iwadPath != null ? iwadPath.toString() : "");
        props.setProperty("novert", Boolean.toString(novert));
        props.setProperty("pause-minecraft", Boolean.toString(pauseMinecraft));
        props.setProperty("doom-sfx-volume", Float.toString(doomSfxVolume));
        props.setProperty("doom-music-volume", Float.toString(doomMusicVolume));
        props.setProperty("doom-skill", Integer.toString(doomSkill));
        props.setProperty("gamma", Integer.toString(gamma));
        props.setProperty("light-boost", Integer.toString(lightBoost));
        props.setProperty("hud-size", Integer.toString(hudSize));
        props.setProperty("crosshair", Integer.toString(crosshair));
        props.setProperty("crosshair-scale", Double.toString(crosshairScale));
        props.setProperty("hud-scale", Double.toString(hudScale));
        props.setProperty("show-messages", Boolean.toString(showMessages));
        props.setProperty("message-time", Double.toString(messageTime));
        props.setProperty("message-lines", Integer.toString(messageLines));
        props.setProperty("crosshair-health", Boolean.toString(crosshairHealth));
        props.setProperty("level-stats", Boolean.toString(levelStats));
        props.setProperty("weapon-bob", Integer.toString(bobScale));
        props.setProperty("freelook", Boolean.toString(freelook));
        props.setProperty("bsp-mesh", Boolean.toString(bspMesh));
        props.setProperty("doom-light", Boolean.toString(doomLight));
        // /pwad edits the load order at runtime — absolute paths, comma-joined,
        // same format load() parses (relative names still resolve against pwads/)
        final StringBuilder pw = new StringBuilder();
        for (Path p : pwads) {
            if (pw.length() > 0) {
                pw.append(',');
            }
            pw.append(p.toAbsolutePath());
        }
        props.setProperty("pwads", pw.length() == 0 ? "none" : pw.toString());
        final StringBuilder dh = new StringBuilder();
        for (Path p : dehs) {
            if (dh.length() > 0) {
                dh.append(',');
            }
            dh.append(p.toAbsolutePath());
        }
        props.setProperty("dehs", dh.length() == 0 ? "none" : dh.toString());
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Latte Doom config");
        } catch (IOException e) {
            System.err.println("[lattedoom] could not write config: " + e);
        }
    }

    /** A real IWAD says so in its header magic ("IWAD" vs "PWAD") AND carries the PLAYPAL
     * palette. The palette test alone let Eviternity through — big total-conversion PWADs
     * ship their own PLAYPAL, and booting one standalone just bounces (three /warp
     * attempts on tape). Reads only the header + directory — cheap for command validation. */
    public static boolean isIwadFile(Path p) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(p.toFile(), "r")) {
            final byte[] head = new byte[12];
            f.readFully(head);
            if (head[0] != 'I' || head[1] != 'W' || head[2] != 'A' || head[3] != 'D') {
                return false;
            }
            final java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(head)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            hb.position(4);
            final int num = hb.getInt();
            final int dirOfs = hb.getInt();
            if (num <= 0 || num > 65536 || dirOfs <= 0 || dirOfs >= f.length()) {
                return false;
            }
            f.seek(dirOfs);
            final byte[] dir = new byte[Math.min(num * 16, (int) (f.length() - dirOfs))];
            f.readFully(dir);
            // A base WAD needs a palette and at least one level. A resource or texture WAD
            // carries a palette and no maps, and accepting one lets the engine boot and then
            // die inside P_SetupLevel with a fatal error the player cannot interpret.
            boolean palette = false;
            boolean levels = false;
            for (int i = 0; i + 16 <= dir.length; i += 16) {
                if (dir[i + 8] == 'P' && dir[i + 9] == 'L' && dir[i + 10] == 'A'
                    && dir[i + 11] == 'Y' && dir[i + 12] == 'P' && dir[i + 13] == 'A'
                    && dir[i + 14] == 'L') {
                    palette = true;
                }
                if (isMapMarker(dir, i + 8)) {
                    levels = true;
                }
                if (palette && levels) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * The other id-engine games this port cannot run, identified by a lump only that game
     * ships. They are structurally valid WADs with a palette and maps, so every test for a
     * usable base WAD passes and the engine only fails later, on lumps that are not there.
     * A negative test is used deliberately: an unknown game is worse handled than a DOOM
     * total conversion is wrongly refused.
     */
    private static final String[][] FOREIGN_GAMES = {
        {"VELLOGO", "Strife"},
        {"RGELOGO", "Strife"},
        {"M_HTIC", "Heretic"},
        {"WINNOWR", "Hexen"},
        {"TINTTAB", "Hexen"},
    };

    /** The name of the game this WAD belongs to when it is not DOOM, or null. */
    public static String foreignGame(Path p) {
        if (p == null) {
            return null;
        }
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(p.toFile(), "r")) {
            final byte[] head = new byte[12];
            f.readFully(head);
            final java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(head)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            hb.position(4);
            final int num = hb.getInt();
            final int dirOfs = hb.getInt();
            if (num <= 0 || num > 65536 || dirOfs <= 0 || dirOfs >= f.length()) {
                return null;
            }
            f.seek(dirOfs);
            final byte[] dir = new byte[Math.min(num * 16, (int) (f.length() - dirOfs))];
            f.readFully(dir);
            for (int i = 0; i + 16 <= dir.length; i += 16) {
                int end = 8;
                while (end < 16 && dir[i + end] != 0) {
                    end++;
                }
                final String name = new String(dir, i + 8, end - 8,
                    java.nio.charset.StandardCharsets.US_ASCII);
                for (String[] g : FOREIGN_GAMES) {
                    if (g[0].equals(name)) {
                        return g[1];
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * Whether this WAD names its maps ExMy rather than MAPxx. Read from the file's own
     * directory, so it is answerable before anything has been loaded or a level raised.
     */
    public static boolean hasEpisodes(Path p) {
        if (p == null) {
            return false;
        }
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(p.toFile(), "r")) {
            final byte[] head = new byte[12];
            f.readFully(head);
            final java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(head)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            hb.position(4);
            final int num = hb.getInt();
            final int dirOfs = hb.getInt();
            if (num <= 0 || num > 65536 || dirOfs <= 0 || dirOfs >= f.length()) {
                return false;
            }
            f.seek(dirOfs);
            final byte[] dir = new byte[Math.min(num * 16, (int) (f.length() - dirOfs))];
            f.readFully(dir);
            for (int i = 0; i + 16 <= dir.length; i += 16) {
                final int o = i + 8;
                if (dir[o] == 'E' && dir[o + 1] >= '1' && dir[o + 1] <= '9'
                    && dir[o + 2] == 'M' && dir[o + 3] >= '1' && dir[o + 3] <= '9') {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    /** An ExMy or MAPxx marker at this offset in the directory entry. */
    private static boolean isMapMarker(byte[] d, int o) {
        if (d[o] == 'E' && d[o + 1] >= '1' && d[o + 1] <= '9'
            && d[o + 2] == 'M' && d[o + 3] >= '1' && d[o + 3] <= '9') {
            return true;
        }
        return d[o] == 'M' && d[o + 1] == 'A' && d[o + 2] == 'P'
            && d[o + 3] >= '0' && d[o + 3] <= '9' && d[o + 4] >= '0' && d[o + 4] <= '9';
    }

    private static Path scanForIwad(Path dir) {
        for (String name : IWAD_NAMES) {
            final Path p = dir.resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    private void loadPwads(String list) {
        final Path pwadDir = dataDir.resolve("pwads");
        try {
            Files.createDirectories(pwadDir);
        } catch (IOException ignored) {
        }
        if (list.equalsIgnoreCase("none")) {
            return; // explicit empty (/doomwad pwad none) — do NOT auto-scan the folder
        }
        // Only an explicit list loads. The old fallback merged every .wad dropped in
        // pwads/ alphabetically, which meant files stacked onto games they were never
        // meant for, silently — the load report is the only place a merge should come from.
        for (String name : list.split(",")) {
            final String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final Path p = Path.of(trimmed);
            final Path resolved = p.isAbsolute() ? p : pwadDir.resolve(p);
            if (Files.exists(resolved) && !isIwad(resolved)) {
                pwads.add(resolved);
            }
        }
    }

    private void loadDehs(String list) {
        if (list.isEmpty() || list.equalsIgnoreCase("none")) {
            return;
        }
        final Path pwadDir = dataDir.resolve("pwads");
        for (String name : list.split(",")) {
            final String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final Path p = Path.of(trimmed);
            final Path resolved = p.isAbsolute() ? p : pwadDir.resolve(p);
            if (Files.exists(resolved)) {
                dehs.add(resolved);
            }
        }
    }

    /** Don't let the IWAD double-load as a PWAD if a user drops a copy in pwads/. */
    private boolean isIwad(Path p) {
        return iwadPath != null && p.toAbsolutePath().equals(iwadPath.toAbsolutePath());
    }

    /** The WAD's directory entries' names, or an empty list when unreadable. */
    private static List<String> lumpNames(Path p) {
        final List<String> names = new ArrayList<>();
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(p.toFile(), "r")) {
            final byte[] head = new byte[12];
            f.readFully(head);
            final java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(head)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            hb.position(4);
            final int num = hb.getInt();
            final int dirOfs = hb.getInt();
            if (num <= 0 || num > 65536 || dirOfs <= 0 || dirOfs >= f.length()) {
                return names;
            }
            f.seek(dirOfs);
            final byte[] dir = new byte[Math.min(num * 16, (int) (f.length() - dirOfs))];
            f.readFully(dir);
            for (int i = 0; i + 16 <= dir.length; i += 16) {
                int end = 8;
                while (end < 16 && dir[i + end] != 0) {
                    end++;
                }
                names.add(new String(dir, i + 8, end - 8,
                    java.nio.charset.StandardCharsets.US_ASCII));
            }
        } catch (Exception ignored) {
        }
        return names;
    }

    /**
     * Which game family this WAD's maps belong to, read from its own directory:
     * 'E' = ExMy (DOOM), 'M' = MAPxx (DOOM II), 'B' = both, 'A' = no maps at all
     * (a resource or addon WAD, usable over any base).
     */
    public static char mapScheme(Path p) {
        boolean e = false, m = false;
        for (String n : lumpNames(p)) {
            if (n.length() == 4 && n.charAt(0) == 'E' && n.charAt(2) == 'M'
                && n.charAt(1) >= '1' && n.charAt(1) <= '9'
                && n.charAt(3) >= '1' && n.charAt(3) <= '9') {
                e = true;
            } else if (n.length() == 5 && n.startsWith("MAP")
                && n.charAt(3) >= '0' && n.charAt(3) <= '9'
                && n.charAt(4) >= '0' && n.charAt(4) <= '9') {
                m = true;
            }
            if (e && m) {
                return 'B';
            }
        }
        return e ? 'E' : m ? 'M' : 'A';
    }

    /** How many map markers the WAD carries. */
    public static int mapCount(Path p) {
        int count = 0;
        for (String n : lumpNames(p)) {
            final byte[] b = java.util.Arrays.copyOf(
                n.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 8);
            if (n.length() >= 4 && isMapMarker(b, 0)) {
                count++;
            }
        }
        return count;
    }

    /** Whether the WAD embeds its own DEHACKED lump (then a loose .deh is not paired). */
    public static boolean hasDehackedLump(Path p) {
        for (String n : lumpNames(p)) {
            if ("DEHACKED".equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }
}
