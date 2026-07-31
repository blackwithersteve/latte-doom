package com.blackwithersteve.lattedoom.arena;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the OVERWORLD ARENA as an in-memory PWAD: one flat square sector (the map the
 * engine needs so it can run REAL DOOM monsters), with a player-1 start and any number of
 * monster things. It is ORIGINAL geometry the mod generates — zero id content, legal to
 * ship (LEGAL.md); the monsters themselves render/behave from the user's own WAD.
 *
 * The map is a "trivial map" in Boom terms: ONE subsector, ZERO BSP nodes (BoomLevelLoader
 * explicitly allows this), and an EMPTY blockmap (the loader rebuilds it). So there is no
 * hand-authored BSP — the risky part is gone. Overrides E1M1 (loaded via -warp 1 1).
 */
public final class ArenaWad {

    /** Half-size of the square arena, in map units (2048×2048 room, ~73×73 blocks). */
    private static final int H = 1024;

    /** A monster to place: DOOM thing type (imp = 3001) at (x,y), facing angle degrees. */
    public record Spawn(int type, int x, int y, int angle) {}

    /** Build the PWAD bytes for an arena containing a P1 start plus the given monsters. */
    public static byte[] build(List<Spawn> monsters) {
        // ---- THINGS: player-1 start at south, monsters as given ----
        final ByteArrayOutputStream things = new ByteArrayOutputStream();
        writeThing(things, 0, -512, 90, 1, 0x07);          // player 1 start, faces north
        for (Spawn s : monsters) {
            writeThing(things, s.x, s.y, s.angle, s.type, 0x07);
        }

        // ---- VERTEXES: the four corners (clockwise so the sector is each wall's front) ----
        final int[][] v = {{-H, -H}, {-H, H}, {H, H}, {H, -H}};
        final ByteArrayOutputStream vertexes = new ByteArrayOutputStream();
        for (int[] p : v) {
            le16(vertexes, p[0]);
            le16(vertexes, p[1]);
        }

        // ---- LINEDEFS + SIDEDEFS: four one-sided walls, all fronting sector 0 ----
        final ByteArrayOutputStream linedefs = new ByteArrayOutputStream();
        final ByteArrayOutputStream sidedefs = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) {
            final int a = i, b = (i + 1) % 4;
            le16(linedefs, a);        // v1
            le16(linedefs, b);        // v2
            le16(linedefs, 0x0001);   // flags = IMPASSABLE (one-sided wall)
            le16(linedefs, 0);        // special
            le16(linedefs, 0);        // tag
            le16(linedefs, i);        // front sidedef
            le16(linedefs, 0xFFFF);   // back sidedef = none (one-sided)

            le16(sidedefs, 0);        // x offset
            le16(sidedefs, 0);        // y offset
            name(sidedefs, "-");      // upper texture (none)
            name(sidedefs, "-");      // lower texture (none)
            name(sidedefs, "STARTAN3"); // middle texture (a stock DOOM wall)
            le16(sidedefs, 0);        // sector
        }

        // ---- SEGS: one per wall (for the single subsector); direction 0, offset 0 ----
        final int[] segAngle = {0x4000, 0x0000, 0xC000, 0x8000}; // N, E, S, W (BAM)
        final ByteArrayOutputStream segs = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) {
            le16(segs, i);            // v1
            le16(segs, (i + 1) % 4);  // v2
            le16(segs, segAngle[i]);  // angle (BAM)
            le16(segs, i);            // linedef
            le16(segs, 0);            // direction (same as linedef)
            le16(segs, 0);            // offset
        }

        // ---- SSECTORS: one subsector covering all four segs ----
        final ByteArrayOutputStream ssectors = new ByteArrayOutputStream();
        le16(ssectors, 4);            // seg count
        le16(ssectors, 0);            // first seg

        // ---- NODES: ONE degenerate node whose BOTH children are subsector 0. (An empty
        // NODES lump underflows the ZDoom/DeePBSP node-version probes, so we give a real
        // node; R_PointInSubsector walks it either way and lands on subsector 0.) ----
        final ByteArrayOutputStream nodesOut = new ByteArrayOutputStream();
        le16(nodesOut, 0);            // partition x
        le16(nodesOut, -H);           // partition y
        le16(nodesOut, 0);            // partition dx
        le16(nodesOut, 2 * H);        // partition dy (vertical line up the middle)
        for (int j = 0; j < 2; j++) { // bbox for both children = the whole map
            le16(nodesOut, H);        // top (max y)
            le16(nodesOut, -H);       // bottom (min y)
            le16(nodesOut, -H);       // left (min x)
            le16(nodesOut, H);        // right (max x)
        }
        le16(nodesOut, 0x8000);       // right child = subsector 0 (NF_SUBSECTOR bit)
        le16(nodesOut, 0x8000);       // left child  = subsector 0
        final byte[] nodes = nodesOut.toByteArray();

        // ---- SECTORS: one flat sector, floor 0 / ceil 256 ----
        final ByteArrayOutputStream sectors = new ByteArrayOutputStream();
        le16(sectors, 0);             // floor height
        le16(sectors, 256);           // ceiling height
        name(sectors, "FLOOR4_8");    // floor flat (stock)
        name(sectors, "CEIL3_5");     // ceiling flat (stock)
        le16(sectors, 200);           // light level
        le16(sectors, 0);             // special
        le16(sectors, 0);             // tag

        // ---- REJECT: 1 sector → 1 bit → 1 byte, 0 = visible ----
        final byte[] reject = new byte[]{0};
        // ---- BLOCKMAP: empty → the Boom loader rebuilds it ----
        final byte[] blockmap = new byte[0];

        // ---- assemble the PWAD (marker + the 10 map lumps, in the vanilla order) ----
        final List<Lump> lumps = new ArrayList<>();
        lumps.add(new Lump("E1M1", new byte[0]));
        lumps.add(new Lump("THINGS", things.toByteArray()));
        lumps.add(new Lump("LINEDEFS", linedefs.toByteArray()));
        lumps.add(new Lump("SIDEDEFS", sidedefs.toByteArray()));
        lumps.add(new Lump("VERTEXES", vertexes.toByteArray()));
        lumps.add(new Lump("SEGS", segs.toByteArray()));
        lumps.add(new Lump("SSECTORS", ssectors.toByteArray()));
        lumps.add(new Lump("NODES", nodes));
        lumps.add(new Lump("SECTORS", sectors.toByteArray()));
        lumps.add(new Lump("REJECT", reject));
        lumps.add(new Lump("BLOCKMAP", blockmap));
        return assemble(lumps);
    }

    private record Lump(String name, byte[] data) {}

    private static byte[] assemble(List<Lump> lumps) {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final int headerSize = 12;
        final int[] offsets = new int[lumps.size()];
        int pos = headerSize;
        for (int i = 0; i < lumps.size(); i++) {
            offsets[i] = pos;
            body.write(lumps.get(i).data(), 0, lumps.get(i).data().length);
            pos += lumps.get(i).data().length;
        }
        final int dirOffset = pos;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("PWAD".getBytes(StandardCharsets.US_ASCII));
        le32(out, lumps.size());
        le32(out, dirOffset);
        out.writeBytes(body.toByteArray());
        for (int i = 0; i < lumps.size(); i++) {
            le32(out, offsets[i]);
            le32(out, lumps.get(i).data().length);
            name(out, lumps.get(i).name());
        }
        return out.toByteArray();
    }

    private static void writeThing(ByteArrayOutputStream o, int x, int y, int angle,
                                   int type, int flags) {
        le16(o, x);
        le16(o, y);
        le16(o, angle);
        le16(o, type);
        le16(o, flags);
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void name(ByteArrayOutputStream o, String s) {
        final byte[] b = s.toUpperCase().getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 8; i++) {
            o.write(i < b.length ? b[i] : 0);
        }
    }

    private ArenaWad() {}
}
