package com.blackwithersteve.lattedoom.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A DOOM WAD read at RUNTIME (not the build-time extractor): header + directory of named
 * lumps, kept in memory so any map's geometry lumps can be handed to {@link DoomMap}. This
 * is what lets the mod load an arbitrary external .wad the player drops in, not just the
 * maps baked into the mod's resources. Loaded WADs are cached by id so client + integrated
 * server (same JVM) share one copy.
 */
public final class WadFile {

    /** src = index into {@link #sources}: which loaded file this lump's bytes live in
     * (0 = the IWAD, 1.. = PWADs in load order). Lets one WadFile MERGE several files. */
    public record Lump(String name, int pos, int size, int src) {}

    // load order: sources.get(0) = IWAD, then each PWAD. Lumps carry their source index.
    private final List<byte[]> sources;
    public final List<Lump> lumps = new ArrayList<>();
    // last-wins so a PWAD lump overrides the IWAD's (DOOM's own merge order)
    private final Map<String, Lump> byName = new LinkedHashMap<>();

    private static final Map<String, WadFile> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private WadFile(List<byte[]> sources) {
        this.sources = sources;
    }

    /** Read + cache a WAD by id (a short handle, e.g. its filename). */
    public static WadFile load(String id, Path file) throws IOException {
        final WadFile existing = CACHE.get(id);
        if (existing != null) {
            return existing;
        }
        final WadFile wad = read(file);
        CACHE.put(id, wad);
        return wad;
    }

    /**
     * Read + cache an IWAD with PWADs merged on top (SIGIL etc.). Later files override
     * earlier ones by lump name, and map markers resolve PWAD-first — exactly how a
     * source port's -file load order works. The engine loads the same set via -file;
     * this is the client-side mirror so the extra maps and art actually render.
     */
    public static WadFile loadMerged(String id, Path iwad, List<Path> pwads) throws IOException {
        final WadFile existing = CACHE.get(id);
        if (existing != null) {
            return existing;
        }
        final List<Path> all = new ArrayList<>();
        all.add(iwad);
        all.addAll(pwads);
        final List<byte[]> raw = new ArrayList<>(all.size());
        for (Path p : all) {
            raw.add(Files.readAllBytes(p));
        }
        final WadFile wad = new WadFile(raw);
        for (int s = 0; s < raw.size(); s++) {
            wad.index(raw.get(s), s, all.get(s));
        }
        CACHE.put(id, wad);
        return wad;
    }

    public static WadFile cached(String id) {
        return CACHE.get(id);
    }

    /** Drop a cached merged set (the /doomwad reload path — old generations must not
     * pile up in memory when the user swaps WAD sets). */
    public static void evict(String id) {
        CACHE.remove(id);
    }

    public static WadFile read(Path file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file);
        final WadFile wad = new WadFile(List.of(bytes));
        wad.index(bytes, 0, file);
        return wad;
    }

    /** Parse one file's header + directory, appending its lumps at source index {@code src}. */
    private void index(byte[] bytes, int src, Path file) throws IOException {
        final ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final byte[] magic = new byte[4];
        bb.get(magic);
        final String m = new String(magic, StandardCharsets.US_ASCII);
        if (!m.equals("IWAD") && !m.equals("PWAD")) {
            throw new IOException("Not a WAD: " + file);
        }
        final int numLumps = bb.getInt();
        final int dirOfs = bb.getInt();
        bb.position(dirOfs);
        final byte[] nameBuf = new byte[8];
        for (int i = 0; i < numLumps; i++) {
            final int pos = bb.getInt();
            final int size = bb.getInt();
            bb.get(nameBuf);
            int end = 0;
            while (end < 8 && nameBuf[end] != 0) {
                end++;
            }
            final Lump lump = new Lump(
                new String(nameBuf, 0, end, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT),
                pos, size, src);
            lumps.add(lump);
            byName.put(lump.name, lump); // last-wins: a PWAD lump overrides the IWAD's
        }
    }

    /** Index of a map's marker lump (ExMy / MAPxx), or -1 if absent. Returns the LAST
     * match so a PWAD that replaces a stock map wins over the IWAD's copy. */
    public int markerOf(String mapName) {
        final String want = mapName.toUpperCase(Locale.ROOT);
        for (int i = lumps.size() - 1; i >= 0; i--) {
            if (lumps.get(i).name().equals(want)) {
                return i;
            }
        }
        return -1;
    }

    /** A map's sub-lump (VERTEXES, LINEDEFS, …) as a little-endian buffer, or null if absent. */
    public ByteBuffer mapLump(int markerIdx, String lumpName) {
        final String want = lumpName.toUpperCase(Locale.ROOT);
        for (int i = markerIdx + 1; i <= markerIdx + 11 && i < lumps.size(); i++) {
            final Lump l = lumps.get(i);
            if (l.name().equals(want)) {
                final byte[] out = new byte[l.size()];
                System.arraycopy(sources.get(l.src()), l.pos(), out, 0, l.size());
                return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            }
        }
        return null;
    }

    /** Does this WAD contain a lump with this name? (Map markers included — the DOOM 1
     * vs DOOM 2 naming probe: E1M1 exists in one, MAP01 in the other.) */
    public boolean hasLump(String name) {
        return byName.containsKey(name.toUpperCase(Locale.ROOT));
    }

    /** A global (non-map-scoped) lump's raw bytes, or null if this WAD lacks it. */
    public byte[] lumpBytes(String name) {
        final Lump l = byName.get(name.toUpperCase(Locale.ROOT));
        if (l == null) {
            return null;
        }
        final byte[] out = new byte[l.size()];
        System.arraycopy(sources.get(l.src()), l.pos(), out, 0, l.size());
        return out;
    }

    /**
     * A map's music lump by the conventional name D_&lt;MAPNAME&gt; (E5M1 -&gt; D_E5M1). SIGIL and
     * most ExMy PWADs embed their music this way. Returns null if absent (caller plays silent).
     */
    public byte[] musicLumpBytes(String mapName) {
        return lumpBytes("D_" + mapName.toUpperCase(Locale.ROOT));
    }

    /** Every map marker this WAD contains (ExMy first, then MAPxx). */
    public List<String> mapNames() {
        final List<String> out = new ArrayList<>();
        for (Lump l : lumps) {
            final String n = l.name();
            final boolean exmy = n.length() == 4 && n.charAt(0) == 'E' && n.charAt(2) == 'M'
                && Character.isDigit(n.charAt(1)) && Character.isDigit(n.charAt(3));
            final boolean mapxx = n.length() == 5 && n.startsWith("MAP")
                && Character.isDigit(n.charAt(3)) && Character.isDigit(n.charAt(4));
            if (exmy || mapxx) {
                out.add(n);
            }
        }
        return out;
    }
}
