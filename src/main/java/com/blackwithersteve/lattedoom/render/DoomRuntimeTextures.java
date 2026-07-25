package com.blackwithersteve.lattedoom.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Composites a WAD's wall textures, from its {@code TEXTURE1}/{@code TEXTURE2}, {@code PNAMES}
 * and patch lumps, together with its flats, into GPU textures when a level loads, registering
 * each under the identifier the level mesh binds.
 *
 * <p>No artwork is bundled with the mod: the user's own WAD, plus any patch WADs, is the sole
 * source, and everything is composited at the moment a level needs it.
 */
public final class DoomRuntimeTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger("lattedoom");

    /** Registered runtime texture keys -> dimensions [w,h], for LatteMesh's size/missing check. */
    private static final Map<String, int[]> sizes = new HashMap<>();
    private static final Set<Identifier> registeredIds = new HashSet<>();
    private static int[] iwadPalette; // the baked IWAD palette, loaded once
    private static String loadedWad;  // the wad id whose textures are currently registered

    /** Point the geometry's runtime-texture lookup at this registry. Idempotent. */
    public static void init() {
        LatteMesh.setTexSize(sizes::get);
    }

    /**
     * A texture whose sampler repeats, with nearest filtering to keep it pixel-crisp.
     * Walls and flats therefore tile on the GPU and the mesh never has to be split at a
     * texture repeat, which is what allows one quad per wall and whole-sector floor
     * triangles.
     */
    private static final class RepeatTexture extends DynamicTexture {
        RepeatTexture(String label, int w, int h) {
            super(label, w, h, true);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                .getRepeat(com.mojang.blaze3d.textures.FilterMode.NEAREST);
        }
    }

    /** Pixel-crisp non-tiling sampler for sprites. */
    private static final class ClampTexture extends DynamicTexture {
        ClampTexture(String label, int w, int h) {
            super(label, w, h, true);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                .getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST);
        }
    }

    /**
     * Ensures a WAD's textures are composited and registered. Called before the mesh is
     * built, so that registration always precedes both the geometry build, which needs real
     * texture keys, and the first bind, which would otherwise show a missing-texture
     * placeholder. Idempotent per WAD, so rebuilding the mesh does not recomposite.
     */
    public static void ensureLoaded(String wadId) {
        if (java.util.Objects.equals(wadId, loadedWad)) {
            return;
        }
        load(com.blackwithersteve.lattedoom.render.WadFile.cached(wadId));
        loadedWad = wadId;
    }

    /** Drop every runtime texture (level change / disconnect). */
    public static void clear() {
        loadedWad = null;
        if (registeredIds.isEmpty()) {
            return;
        }
        final var tm = Minecraft.getInstance().getTextureManager();
        for (Identifier id : registeredIds) {
            tm.release(id);
        }
        registeredIds.clear();
        sizes.clear();
        spriteOffsets.clear();
    }

    /** Composite + register every wall texture and flat a runtime WAD provides. */
    public static void load(WadFile wad) {
        clear();
        orderedWalls.clear();
        orderedFlats.clear();
        if (wad == null) {
            return;
        }
        final int[] pal = palette(wad);
        if (pal == null) {
            LOGGER.warn("DoomRuntimeTextures: no palette (baked playpal.dat missing?), custom textures will be gray");
            return;
        }
        try {
            loadWalls(wad, pal);
        } catch (Exception e) {
            LOGGER.error("DoomRuntimeTextures: wall composite failed", e);
        }
        try {
            loadFlats(wad, pal);
        } catch (Exception e) {
            LOGGER.error("DoomRuntimeTextures: flat composite failed", e);
        }
        try {
            loadSprites(wad, pal);
        } catch (Exception e) {
            LOGGER.error("DoomRuntimeTextures: sprite composite failed", e);
        }
        try {
            loadStatusGraphics(wad, pal);
        } catch (Exception e) {
            LOGGER.error("DoomRuntimeTextures: status graphics composite failed", e);
        }
        // Guaranteed fallback: the mesh substitutes the GRAY1 texture for any texture it
        // cannot resolve. That lump is present in every stock IWAD, but one is synthesised
        // when a WAD lacks it, so the fallback itself can never be missing.
        if (!sizes.containsKey("walls/gray1")) {
            final int[] gray = new int[64 * 64];
            java.util.Arrays.fill(gray, 0xFF6C6C6C);
            register("walls/gray1", gray, 64, 64);
        }
        LatteAnims.build(orderedWalls, orderedFlats, wad);
        // A WAD's SWITCHES lump: the rendering side needs the same switch pairs the engine
        // matches against, falling back to the built-in name prefixes.
        final java.util.Map<String, String> pairs = new java.util.HashMap<>();
        final byte[] sw = wad.lumpBytes("SWITCHES");
        if (sw != null) {
            for (int o = 0; o + 20 <= sw.length; o += 20) {
                final int ep = (sw[o + 18] & 0xFF) | ((sw[o + 19] & 0xFF) << 8);
                if (ep == 0) {
                    break;
                }
                final String off = swStr(sw, o), on = swStr(sw, o + 9);
                if (!off.isEmpty() && !on.isEmpty()) {
                    pairs.put(off, on);
                    pairs.put(on, off);
                }
            }
            LOGGER.info("DoomRuntimeTextures: SWITCHES lump gave {} pair entries", pairs.size());
        }
        LatteMesh.setSwitchPairs(pairs);
        LOGGER.info("DoomRuntimeTextures: registered {} runtime textures", sizes.size());
    }

    /** The WAD's own PLAYPAL (every IWAD has one), else the cached IWAD palette. 256 ARGB entries. */
    private static int[] palette(WadFile wad) {
        byte[] pp = wad.lumpBytes("PLAYPAL");
        if ((pp == null || pp.length < 768) && iwadPalette != null) {
            return iwadPalette;
        }
        if (pp == null || pp.length < 768) {
            return null;
        }
        final int[] out = new int[256];
        for (int i = 0; i < 256; i++) {
            out[i] = 0xFF000000 | ((pp[i * 3] & 0xFF) << 16) | ((pp[i * 3 + 1] & 0xFF) << 8) | (pp[i * 3 + 2] & 0xFF);
        }
        iwadPalette = out; // remember the last good palette for palette-less PWADs
        return out;
    }

    private static void loadWalls(WadFile wad, int[] pal) {
        final byte[] pnamesData = wad.lumpBytes("PNAMES");
        if (pnamesData == null) {
            return; // no custom textures without PNAMES
        }
        final ByteBuffer pn = ByteBuffer.wrap(pnamesData).order(ByteOrder.LITTLE_ENDIAN);
        final int numPatches = pn.getInt();
        final String[] patchNames = new String[numPatches];
        final byte[] nameBuf = new byte[8];
        for (int i = 0; i < numPatches; i++) {
            pn.get(nameBuf);
            patchNames[i] = lumpName(nameBuf);
        }
        for (String texLump : new String[]{"TEXTURE1", "TEXTURE2"}) {
            final byte[] data = wad.lumpBytes(texLump);
            if (data == null) {
                continue;
            }
            final ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            final int numTex = bb.getInt();
            final int[] offsets = new int[numTex];
            for (int i = 0; i < numTex; i++) {
                offsets[i] = bb.getInt();
            }
            for (int i = 0; i < numTex; i++) {
                bb.position(offsets[i]);
                bb.get(nameBuf);
                final String name = lumpName(nameBuf);
                bb.getInt();                            // masked
                final int w = bb.getShort() & 0xFFFF;
                final int h = bb.getShort() & 0xFFFF;
                bb.getInt();                            // columndirectory (obsolete)
                final int patchCount = bb.getShort() & 0xFFFF;
                if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
                    continue;
                }
                final String key = "walls/" + name.toLowerCase(Locale.ROOT);
                orderedWalls.add(name); // directory order: animation sequences resolve on this
                if (bakedExists(key)) {
                    continue; // already supplied by the mod, so nothing to composite
                }
                final int[] argb = new int[w * h]; // transparent where uncovered
                for (int p = 0; p < patchCount; p++) {
                    final int origX = bb.getShort();
                    final int origY = bb.getShort();
                    final int patchIdx = bb.getShort() & 0xFFFF;
                    bb.getShort();                      // stepdir
                    bb.getShort();                      // colormap
                    if (patchIdx >= numPatches) {
                        continue;
                    }
                    final byte[] pd = wad.lumpBytes(patchNames[patchIdx]);
                    if (pd == null) {
                        continue;
                    }
                    try {
                        blitPatch(argb, w, h, pd, pal, origX, origY);
                    } catch (RuntimeException ignored) {
                    }
                }
                register(key, argb, w, h);
            }
        }
    }

    private static void loadFlats(WadFile wad, int[] pal) {
        boolean inFlats = false;
        for (WadFile.Lump lump : wad.lumps) {
            final String n = lump.name();
            if (n.equals("F_START") || n.equals("FF_START")) {
                inFlats = true;
                continue;
            }
            if (n.equals("F_END") || n.equals("FF_END")) {
                inFlats = false;
                continue;
            }
            if (!inFlats || lump.size() != 4096) {
                continue;
            }
            final String key = "flats/" + n.toLowerCase(Locale.ROOT);
            orderedFlats.add(n); // directory order: animation sequences resolve on this
            if (bakedExists(key)) {
                continue;
            }
            final byte[] data = wad.lumpBytes(n);
            if (data == null) {
                continue;
            }
            final int[] argb = new int[64 * 64];
            for (int i = 0; i < 4096; i++) {
                argb[i] = pal[data[i] & 0xFF];
            }
            register(key, argb, 64, 64);
        }
    }

    /**
     * Composites the sprite lumps between the {@code S_START} and {@code S_END} markers and
     * registers each with a clamping sampler: billboards never tile, and a repeating sampler
     * would bleed the opposite edge's texels along the borders. The left and top offsets
     * from each patch header are kept for anchoring the billboard.
     */
    private static void loadSprites(WadFile wad, int[] pal) {
        boolean in = false;
        for (WadFile.Lump lump : wad.lumps) {
            final String n = lump.name();
            if (n.equals("S_START") || n.equals("SS_START")) {
                in = true;
                continue;
            }
            if (n.equals("S_END") || n.equals("SS_END")) {
                in = false;
                continue;
            }
            if (!in || lump.size() < 12) {
                continue;
            }
            final byte[] data = wad.lumpBytes(n);
            if (data == null) {
                continue;
            }
            final ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            final int w = bb.getShort() & 0xFFFF;
            final int h = bb.getShort() & 0xFFFF;
            final int leftOfs = bb.getShort();
            final int topOfs = bb.getShort();
            if (w <= 0 || w > 512 || h <= 0 || h > 512) {
                continue;
            }
            final String key = "sprites/" + n.toLowerCase(Locale.ROOT);
            final int[] argb = new int[w * h];
            try {
                blitPatch(argb, w, h, data, pal, 0, 0);
            } catch (RuntimeException ignored) {
                continue;
            }
            register(key, argb, w, h, false);
            spriteOffsets.put(safe(key), new int[]{leftOfs, topOfs});
        }
    }

    /**
     * Composites the interface graphics, which are loose patch lumps outside any markers:
     * the status bar, its large and small digits, the keys and the full set of faces.
     * Registered under {@code gfx/<lump>} with clamping sampling.
     */
    private static void loadStatusGraphics(WadFile wad, int[] pal) {
        final String[] prefixes = {"STBAR", "STARMS", "STTNUM", "STTPRCNT", "STTMINUS",
            "STYSNUM", "STGNUM", "STKEYS", "STF",
            // The M_ prefix covers the menu graphics: title and label patches, the
            // thermometer pieces and the skull cursor.
            "M_",
            // Artwork for the menu, intermission and finale screens, which are drawn as
            // Minecraft screens: the title, credit and help pages, the full intermission
            // set of banners, level names, digits and backgrounds, and the small font used
            // for the finale text.
            "TITLEPIC", "CREDIT", "HELP", "WI", "STCFN", "INTERPIC", "PFUB",
            "VICTORY2", "ENDPIC"};
        for (WadFile.Lump lump : wad.lumps) {
            final String n = lump.name();
            boolean want = false;
            for (String p : prefixes) {
                if (n.startsWith(p)) {
                    want = true;
                    break;
                }
            }
            if (!want || lump.size() < 12) {
                continue;
            }
            final byte[] data = wad.lumpBytes(n);
            if (data == null) {
                continue;
            }
            final ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            final int w = bb.getShort() & 0xFFFF;
            final int h = bb.getShort() & 0xFFFF;
            final int leftOfs = bb.getShort();
            final int topOfs = bb.getShort();
            if (w <= 0 || w > 512 || h <= 0 || h > 512) {
                continue;
            }
            final String key = "gfx/" + n.toLowerCase(Locale.ROOT);
            final int[] argb = new int[w * h];
            try {
                blitPatch(argb, w, h, data, pal, 0, 0);
            } catch (RuntimeException ignored) {
                continue;
            }
            register(key, argb, w, h, false);
            // V_DrawPatch subtracts these when drawing; the status-bar faces carry large
            // offsets in particular.
            spriteOffsets.put(safe(key), new int[]{leftOfs, topOfs});
        }
    }

    private static String swStr(byte[] b, int off) {
        int len = 0;
        while (len < 8 && b[off + len] != 0) {
            len++;
        }
        return new String(b, off, len, java.nio.charset.StandardCharsets.US_ASCII)
            .toUpperCase(Locale.ROOT);
    }

    /** Minecraft resource identifiers allow only {@code [a-z0-9/._-]}, while sprite
     * rotation pairs in some WADs use bracket and backslash characters in their lump
     * names. This single point maps those to safe tokens, and every producer and consumer
     * of a texture key routes through it. */
    public static String safe(String key) {
        if (key.indexOf('[') < 0 && key.indexOf('\\') < 0 && key.indexOf(']') < 0) {
            return key;
        }
        return key.replace("[", "_lb").replace("\\", "_bs").replace("]", "_rb");
    }

    /** Sprite key -> {leftOffset, topOffset} texels (patch header), for billboard anchoring. */
    private static final Map<String, int[]> spriteOffsets = new HashMap<>();

    /** WAD-directory-ordered texture/flat names (animation sequences span consecutive entries). */
    private static final java.util.List<String> orderedWalls = new java.util.ArrayList<>();
    private static final java.util.List<String> orderedFlats = new java.util.ArrayList<>();

    public static int[] spriteOffset(String key) {
        return spriteOffsets.get(safe(key));
    }

    public static int[] textureSize(String rawKey) {
        final String key = safe(rawKey);
        return sizes.get(key);
    }

    private static void register(String key, int[] argb, int w, int h) {
        register(key, argb, w, h, true);
    }

    private static void register(String rawKey, int[] argb, int w, int h, boolean repeat) {
        final String key = safe(rawKey);
        try {
            final Identifier id = Identifier.fromNamespaceAndPath(
                "lattedoom", "textures/doom/" + key + ".png");
            final DynamicTexture tex = repeat ? new RepeatTexture(key, w, h)
                : new ClampTexture(key, w, h);
            final var pixels = tex.getPixels();
            if (pixels == null) {
                LOGGER.warn("DoomRuntimeTextures: null pixels for {}", key);
                return;
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    final int a = argb[y * w + x];
                    // ARGB to ABGR: the red and blue channels are swapped for upload.
                    final int abgr = (a & 0xFF00FF00) | ((a & 0xFF) << 16) | ((a >> 16) & 0xFF);
                    pixels.setPixelABGR(x, y, abgr);
                }
            }
            tex.upload();
            Minecraft.getInstance().getTextureManager().register(id, tex);
            sizes.put(key, new int[]{w, h});
            registeredIds.add(id);
        } catch (Throwable t) {
            LOGGER.error("DoomRuntimeTextures: register {} failed", key, t);
        }
    }

    /** DOOM picture format: columns of vertical posts, palette-indexed, holes transparent. */
    private static void blitPatch(int[] canvas, int cw, int ch, byte[] data, int[] pal, int ox, int oy) {
        final ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        final int w = bb.getShort() & 0xFFFF;
        final int h = bb.getShort() & 0xFFFF;
        bb.getShort();                                  // leftoffset
        bb.getShort();                                  // topoffset
        if (w <= 0 || w > 4096 || h <= 0 || h > 4096 || data.length < 8 + 4 * w) {
            return;
        }
        for (int x = 0; x < w; x++) {
            int ofs = bb.getInt(8 + 4 * x);
            if (ofs < 0 || ofs >= data.length) {
                continue;
            }
            int prevTop = -1;
            while (true) {
                final int topDelta = data[ofs] & 0xFF;
                if (topDelta == 0xFF) {
                    break;
                }
                // Tall-patch convention: a non-increasing topdelta is relative to the previous post.
                final int top = (prevTop >= 0 && topDelta <= prevTop) ? prevTop + topDelta : topDelta;
                prevTop = top;
                final int len = data[ofs + 1] & 0xFF;
                for (int i = 0; i < len; i++) {
                    final int y = top + i;
                    final int cx = ox + x, cy = oy + y;
                    if (cx >= 0 && cx < cw && cy >= 0 && cy < ch && y >= 0 && y < h) {
                        canvas[cy * cw + cx] = pal[data[ofs + 3 + i] & 0xFF];
                    }
                }
                ofs += len + 4;
                if (ofs >= data.length) {
                    break;
                }
            }
        }
    }

    /** Headless composite of one named texture (no GPU) for the probe harness. Fills wh=[w,h]. */
    public static int[] debugComposite(WadFile wad, String texName, int[] wh) {
        final int[] pal = palette(wad);
        if (pal == null) {
            System.out.println("  NO PALETTE");
            return null;
        }
        final byte[] pnamesData = wad.lumpBytes("PNAMES");
        if (pnamesData == null) {
            return null;
        }
        final ByteBuffer pn = ByteBuffer.wrap(pnamesData).order(ByteOrder.LITTLE_ENDIAN);
        final int numPatches = pn.getInt();
        final String[] patchNames = new String[numPatches];
        final byte[] nameBuf = new byte[8];
        for (int i = 0; i < numPatches; i++) {
            pn.get(nameBuf);
            patchNames[i] = lumpName(nameBuf);
        }
        final String want = texName.toUpperCase(Locale.ROOT);
        for (String texLump : new String[]{"TEXTURE1", "TEXTURE2"}) {
            final byte[] data = wad.lumpBytes(texLump);
            if (data == null) {
                continue;
            }
            final ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            final int numTex = bb.getInt();
            final int[] offsets = new int[numTex];
            for (int i = 0; i < numTex; i++) {
                offsets[i] = bb.getInt();
            }
            for (int i = 0; i < numTex; i++) {
                bb.position(offsets[i]);
                bb.get(nameBuf);
                final String name = lumpName(nameBuf);
                bb.getInt();
                final int w = bb.getShort() & 0xFFFF;
                final int h = bb.getShort() & 0xFFFF;
                bb.getInt();
                final int patchCount = bb.getShort() & 0xFFFF;
                if (!name.equals(want)) {
                    continue;
                }
                final int[] argb = new int[w * h];
                int found = 0, missing = 0;
                for (int p = 0; p < patchCount; p++) {
                    final int origX = bb.getShort();
                    final int origY = bb.getShort();
                    final int patchIdx = bb.getShort() & 0xFFFF;
                    bb.getShort();
                    bb.getShort();
                    if (patchIdx >= numPatches) {
                        missing++;
                        continue;
                    }
                    final byte[] pd = wad.lumpBytes(patchNames[patchIdx]);
                    if (pd == null) {
                        System.out.println("  MISSING patch lump: " + patchNames[patchIdx]);
                        missing++;
                        continue;
                    }
                    found++;
                    try {
                        blitPatch(argb, w, h, pd, pal, origX, origY);
                    } catch (RuntimeException e) {
                        System.out.println("  patch decode FAILED: " + patchNames[patchIdx] + " " + e);
                    }
                }
                int opaque = 0;
                for (int a : argb) {
                    if ((a >>> 24) != 0) {
                        opaque++;
                    }
                }
                System.out.printf("  composite '%s' %dx%d: patches %d found / %d missing / %d total, opaque px %d/%d%n",
                    name, w, h, found, missing, patchCount, opaque, w * h);
                wh[0] = w;
                wh[1] = h;
                return argb;
            }
        }
        System.out.println("  texture '" + want + "' NOT FOUND in TEXTURE1/TEXTURE2");
        return null;
    }

    /** No artwork is bundled, so every texture is composited from the WAD at runtime. */
    private static boolean bakedExists(String key) {
        return false;
    }

    private static String lumpName(byte[] buf) {
        int end = 0;
        while (end < 8 && buf[end] != 0) {
            end++;
        }
        return new String(buf, 0, end, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }

    private DoomRuntimeTextures() {}
}
