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
import java.util.stream.Stream;

/**
 * Settings from {@code config/latte-doom/latte-doom.properties}. That folder doubles as the
 * engine's data directory, holding the IWAD, the engine's own config files and savegames.
 */
public final class LatteDoomConfig {

    private static final String[] IWAD_NAMES = {
        "DOOM.WAD", "DOOMU.WAD", "DOOM2.WAD", "PLUTONIA.WAD", "TNT.WAD",
        "DOOM1.WAD", "FREEDOOM1.WAD", "FREEDOOM2.WAD"
    };

    public final Path dataDir;
    public Path iwadPath;             // may be null if nothing found
    public boolean novert = true;     // the mouse turns only; it never walks the player
    public boolean pauseMinecraft = true;
    /** The mod's own audio levels, 0 to 1, separate from Minecraft's sliders, where 1.0 is
     * full volume. Persisted, and adjustable at runtime through the volume menu or the
     * volume command. */
    public float doomSfxVolume = 1f;
    public float doomMusicVolume = 1f;
    /** DOOM skill, 1 to 5. The menu's New Game choice is persisted here and applies to every
     * later boot, controlling per-skill monster spawns (P_LoadThings), damage scaling and
     * monster respawning. */
    public int doomSkill = 3;
    /** Whether right-clicking a block item against the drawn level places a real block. */
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
    /** Extra WADs merged on top of the IWAD, in load order (SIGIL, custom maps...). */
    public final List<Path> pwads = new ArrayList<>();

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
            // A configured base-WAD path that is in fact a PWAD is rejected and the folder
            // scan takes over: loading a PWAD as the base WAD fails later, in texture
            // lookups, with a far less obvious error.
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

        // Extra WADs, in explicit order from the "pwads" property: comma-separated names,
        // either absolute or relative to the pwads folder. Without that property every WAD
        // in pwads/ is loaded alphabetically. These are the user's own files, layered on
        // top of the user's own IWAD at runtime.
        cfg.loadPwads(props.getProperty("pwads", "").trim());

        // Write the file back so that every setting is discoverable in it.
        props.setProperty("iwad", cfg.iwadPath != null ? cfg.iwadPath.toString() : "");
        props.setProperty("novert", Boolean.toString(cfg.novert));
        props.setProperty("pause-minecraft", Boolean.toString(cfg.pauseMinecraft));
        props.setProperty("place-blocks", Boolean.toString(cfg.placeBlocks));
        props.setProperty("melee-scale", Double.toString(cfg.meleeScale));
        props.setProperty("sound-driver", cfg.soundDriver);
        props.setProperty("doom-sfx-volume", Float.toString(cfg.doomSfxVolume));
        props.setProperty("doom-music-volume", Float.toString(cfg.doomMusicVolume));
        props.setProperty("doom-skill", Integer.toString(cfg.doomSkill));
        if (props.getProperty("pwads") == null) {
            props.setProperty("pwads", "");
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Latte Doom: iwad: path to DOOM.WAD (or drop it in this folder); "
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
     * Persists the settings that change at runtime, preserving every other key already in
     * the file. Without this, volume changes made in game would be lost on restart, since
     * {@link #load} is the only other writer. Synchronised, because volume can be changed
     * from more than one thread.
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
        // The pwad command edits the load order at runtime: absolute paths, comma-joined,
        // in the same format load() parses. Relative names still resolve against pwads/.
        final StringBuilder pw = new StringBuilder();
        for (Path p : pwads) {
            if (pw.length() > 0) {
                pw.append(',');
            }
            pw.append(p.toAbsolutePath());
        }
        // "none" means explicitly cleared; an empty value would restart the folder scan.
        props.setProperty("pwads", pw.length() == 0 ? "none" : pw.toString());
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Latte Doom config");
        } catch (IOException e) {
            System.err.println("[lattedoom] could not write config: " + e);
        }
    }

    /** A base WAD identifies itself in its header magic, {@code IWAD} rather than
     * {@code PWAD}, and carries the {@code PLAYPAL} palette. Both tests are required: the
     * palette test alone is not sufficient, because large total-conversion
     * PWADs ship their own PLAYPAL and would pass it, yet fail to boot as a base WAD.
     * At least one map lump is required as well, so that resource WADs are not accepted.
     * Only the header and directory are read, which is cheap enough for validating
     * command input. */
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
            return; // explicitly cleared (/doomwad pwad none): the folder is not scanned
        }
        if (!list.isEmpty()) {
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
            return;
        }
        // No explicit order configured: load every WAD in pwads/ alphabetically.
        try (Stream<Path> s = Files.list(pwadDir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wad"))
                .filter(p -> !isIwad(p))
                .sorted()
                .forEach(pwads::add);
        } catch (IOException ignored) {
        }
    }

    /** Don't let the IWAD double-load as a PWAD if a user drops a copy in pwads/. */
    private boolean isIwad(Path p) {
        return iwadPath != null && p.toAbsolutePath().equals(iwadPath.toAbsolutePath());
    }
}
