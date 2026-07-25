package com.blackwithersteve.lattedoom.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A level parsed from a WAD's raw map lumps: vertexes, linedefs resolved to coordinates
 * and front and back sectors, sectors with their heights, flats and light levels, and
 * things. It also answers the geometric query the rest of the mod needs, which sector
 * contains a given point: in the manner of {@code R_PointInSubsector}, from the nearest
 * linedef crossing in the +x direction and which side of it the point lies on.
 */
public final class DoomMap {

    public record Sector(int floorH, int ceilH, String floorFlat, String ceilFlat, int light) {}

    public record Side(int xofs, int yofs, String upper, String lower, String middle, int sector) {}

    public record Line(int x1, int y1, int x2, int y2, int frontSector, int backSector,
                       int special, int tag, int frontSide, int backSide, int flags) {}

    public static final int ML_DONTPEGTOP = 0x0008;
    public static final int ML_DONTPEGBOTTOM = 0x0010;

    public record Thing(int x, int y, int angle, int type, int flags) {}

    /** One convex piece of a sector's floor plan: map-space vertices and the owning sector. */
    public record SubPoly(double[] xs, double[] ys, int sector) {}

    public final List<Sector> sectors = new ArrayList<>();
    public final List<Side> sides = new ArrayList<>();
    public final List<Line> lines = new ArrayList<>();
    public final List<Thing> things = new ArrayList<>();
    private int[] sectorTags = new int[0];
    private int[] sectorSpecials = new int[0];

    // Current sector heights, which doors, lifts and moving floors change at runtime. The
    // parsed Sector records keep the values as authored, while movement, combat and
    // rendering all read the current ones.
    private int[] liveFloor = new int[0];
    private int[] liveCeil = new int[0];
    // Current floor flat and sector special. The change-on-arrival floor specials copy a
    // neighbouring sector's flat and special onto this one when the floor reaches its
    // target, so that a floor lowering into a damaging pit becomes damaging itself. The
    // parsed values remain as authored.
    private String[] liveFloorFlat = new String[0];
    private int[] liveSpecial = new int[0];
    /** Lines whose switch texture has been flipped to its pressed state, read by the
     * mesh builder. */
    public final java.util.Set<Integer> switchedLines =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public int floorNow(int sector) {
        return sector >= 0 && sector < liveFloor.length ? liveFloor[sector] : 0;
    }

    public int ceilNow(int sector) {
        return sector >= 0 && sector < liveCeil.length ? liveCeil[sector] : 0;
    }

    public void setLive(int sector, int floor, int ceil) {
        if (sector >= 0 && sector < liveFloor.length) {
            liveFloor[sector] = floor;
            liveCeil[sector] = ceil;
        }
    }

    public int sectorSpecial(int sector) {
        return sector >= 0 && sector < sectorSpecials.length ? sectorSpecials[sector] : 0;
    }

    /** The current floor flat, falling back to the authored value before any change. */
    public String floorFlatNow(int sector) {
        if (sector >= 0 && sector < liveFloorFlat.length && liveFloorFlat[sector] != null) {
            return liveFloorFlat[sector];
        }
        return sector >= 0 && sector < sectors.size() ? sectors.get(sector).floorFlat() : "";
    }

    public void setFloorFlat(int sector, String flat) {
        if (sector >= 0 && sector < liveFloorFlat.length && flat != null) {
            liveFloorFlat[sector] = flat;
        }
    }

    /** The current sector special; change-on-arrival floors modify it, while light effects
     * are spawned from the authored value. */
    public int sectorSpecialNow(int sector) {
        return sector >= 0 && sector < liveSpecial.length ? liveSpecial[sector] : 0;
    }

    public void setSectorSpecial(int sector, int special) {
        if (sector >= 0 && sector < liveSpecial.length) {
            liveSpecial[sector] = special;
        }
    }

    /** Resets to the authored state for a freshly loaded level: doors closed, lifts raised
     * and switches unpressed. */
    public void resetLive() {
        for (int i = 0; i < liveFloor.length; i++) {
            liveFloor[i] = sectors.get(i).floorH();
            liveCeil[i] = sectors.get(i).ceilH();
            liveFloorFlat[i] = sectors.get(i).floorFlat();
            liveSpecial[i] = i < sectorSpecials.length ? sectorSpecials[i] : 0;
        }
        switchedLines.clear();
    }

    // Raw BSP data, present when the map provides SEGS, SSECTORS and NODES.
    private int[] segV1, segV2, segLine, segSide;
    private int[] ssNumSegs, ssFirstSeg;
    private int[] ndX, ndY, ndDx, ndDy, ndRight, ndLeft;
    private int[] vertX, vertY;
    private List<SubPoly> polys;

    // Boom sky transfer (linedef specials 271 and 272). The assignment is made once when a
    // level spawns its specials and never changes, so it is resolved here from the map data
    // rather than carried in the snapshot. Each entry holds the index of the transferring
    // linedef, or -1 when the sector uses the map's own sky.
    private int[] sectorSkyLine = new int[0];

    /** Resolves specials 271 and 272 onto their tagged sectors. */
    void buildSkyTransfers() {
        sectorSkyLine = new int[sectors.size()];
        java.util.Arrays.fill(sectorSkyLine, -1);
        for (int li = 0; li < lines.size(); li++) {
            final Line l = lines.get(li);
            if (l.special() != 271 && l.special() != 272) {
                continue;
            }
            // A tag of zero is meaningful here: P_FindSectorFromLineTag matches sectors
            // whose tag is also zero, which is how a map sets one sky for everything it
            // has not tagged otherwise.
            for (int sec = 0; sec < sectorSkyLine.length; sec++) {
                if (sectorTag(sec) == l.tag()) {
                    sectorSkyLine[sec] = li;
                }
            }
        }
    }

    private int skyLine(int sector) {
        return sector >= 0 && sector < sectorSkyLine.length ? sectorSkyLine[sector] : -1;
    }

    /** The sky transferred onto this sector, or null for the map's own sky. Boom takes it
     * from the upper texture of the transferring linedef's front sidedef. */
    public String skyTextureFor(int sector) {
        final int li = skyLine(sector);
        if (li < 0) {
            return null;
        }
        final int sd = lines.get(li).frontSide();
        if (sd < 0 || sd >= sides.size()) {
            return null;
        }
        final String up = sides.get(sd).upper();
        return up == null || up.isEmpty() || "-".equals(up) ? null : up;
    }

    /** Whether this sector's transferred sky is mirrored: special 272 rather than 271. */
    public boolean skyFlippedFor(int sector) {
        final int li = skyLine(sector);
        return li >= 0 && lines.get(li).special() == 272;
    }

    /** The transferring sidedef's horizontal offset, applied as a sky column shift. */
    public int skyXOffsetFor(int sector) {
        final int li = skyLine(sector);
        if (li < 0) {
            return 0;
        }
        final int sd = lines.get(li).frontSide();
        return sd >= 0 && sd < sides.size() ? sides.get(sd).xofs() : 0;
    }

    /** Whether any sector in this map carries a transferred sky. */
    public boolean hasSkyTransfers() {
        for (int v : sectorSkyLine) {
            if (v >= 0) {
                return true;
            }
        }
        return false;
    }

    public int sectorTag(int sector) {
        return sector >= 0 && sector < sectorTags.length ? sectorTags[sector] : 0;
    }
    public int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
    public int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

    /** A source of a map's named lumps, backed by a WAD loaded at runtime. */
    @FunctionalInterface
    public interface LumpSource {
        ByteBuffer get(String lumpName) throws IOException;
    }

    public static DoomMap load(String mapName) throws IOException {
        // No maps are bundled with the mod: every level comes from a WAD the user supplied.
        throw new IOException("no bundled map '" + mapName + "': use a wadId#MAP reference");
    }

    /**
     * Resolves a map reference of the form {@code wadId#MAPNAME}, loading the map from the
     * runtime WAD registered under that identifier, which is the same handle on the client
     * and on the integrated server. This is the single entry point used by everything that
     * raises a level.
     */
    public static DoomMap loadRef(String ref) throws IOException {
        final int hash = ref.indexOf('#');
        if (hash < 0) {
            return load(ref);
        }
        final String wadId = ref.substring(0, hash);
        final String mapName = ref.substring(hash + 1);
        final WadFile wad = WadFile.cached(wadId);
        if (wad == null) {
            throw new IOException("WAD '" + wadId + "' is not loaded on this side");
        }
        return loadFromWad(wad, mapName);
    }

    /** Load a map's geometry straight from an in-memory runtime WAD the player supplied. */
    public static DoomMap loadFromWad(WadFile wad, String mapName) throws IOException {
        final int marker = wad.markerOf(mapName);
        if (marker < 0) {
            throw new IOException("WAD has no map " + mapName);
        }
        return parse(name -> {
            final ByteBuffer b = wad.mapLump(marker, name);
            if (b == null) {
                throw new IOException("map " + mapName + " missing lump " + name);
            }
            return b;
        });
    }

    private static DoomMap parse(LumpSource src) throws IOException {
        final DoomMap map = new DoomMap();

        final ByteBuffer vt = src.get("vertexes");
        final int[] vx = new int[vt.remaining() / 4];
        final int[] vy = new int[vx.length];
        for (int i = 0; i < vx.length; i++) {
            vx[i] = vt.getShort();
            vy[i] = vt.getShort();
            map.minX = Math.min(map.minX, vx[i]);
            map.maxX = Math.max(map.maxX, vx[i]);
            map.minY = Math.min(map.minY, vy[i]);
            map.maxY = Math.max(map.maxY, vy[i]);
        }

        final ByteBuffer st = src.get("sectors");
        final List<Integer> tags = new ArrayList<>();
        final List<Integer> specials = new ArrayList<>();
        while (st.remaining() >= 26) {
            final int floor = st.getShort();
            final int ceil = st.getShort();
            final String ff = name8(st);
            final String cf = name8(st);
            final int light = st.getShort();
            specials.add((int) st.getShort());
            tags.add((int) st.getShort());
            map.sectors.add(new Sector(floor, ceil, ff, cf, light));
        }
        map.sectorTags = new int[tags.size()];
        map.sectorSpecials = new int[tags.size()];
        map.liveFloor = new int[tags.size()];
        map.liveCeil = new int[tags.size()];
        map.liveFloorFlat = new String[tags.size()];
        map.liveSpecial = new int[tags.size()];
        for (int i = 0; i < tags.size(); i++) {
            map.sectorTags[i] = tags.get(i);
            map.sectorSpecials[i] = specials.get(i);
            map.liveFloor[i] = map.sectors.get(i).floorH();
            map.liveCeil[i] = map.sectors.get(i).ceilH();
            map.liveFloorFlat[i] = map.sectors.get(i).floorFlat();
            map.liveSpecial[i] = specials.get(i);
        }

        final ByteBuffer sd = src.get("sidedefs");
        final List<Integer> sideSector = new ArrayList<>();
        while (sd.remaining() >= 30) {
            final int xofs = sd.getShort();
            final int yofs = sd.getShort();
            final String upper = name8(sd);
            final String lower = name8(sd);
            final String middle = name8(sd);
            final int sec = sd.getShort();
            map.sides.add(new Side(xofs, yofs, upper, lower, middle, sec));
            sideSector.add(sec);
        }

        final ByteBuffer ld = src.get("linedefs");
        while (ld.remaining() >= 14) {
            final int v1 = ld.getShort() & 0xFFFF;
            final int v2 = ld.getShort() & 0xFFFF;
            final int flags = ld.getShort() & 0xFFFF;
            final int special = ld.getShort() & 0xFFFF;
            final int tag = ld.getShort() & 0xFFFF;
            final int right = ld.getShort() & 0xFFFF;
            final int left = ld.getShort() & 0xFFFF;
            map.lines.add(new Line(vx[v1], vy[v1], vx[v2], vy[v2],
                right == 0xFFFF ? -1 : sideSector.get(right),
                left == 0xFFFF ? -1 : sideSector.get(left),
                special, tag,
                right == 0xFFFF ? -1 : right,
                left == 0xFFFF ? -1 : left,
                flags));
        }
        map.vertX = vx;
        map.vertY = vy;

        // BSP data, when the map provides SEGS, SSECTORS and NODES.
        try {
            final ByteBuffer sg = src.get("segs");
            final int nSegs = sg.remaining() / 12;
            map.segV1 = new int[nSegs];
            map.segV2 = new int[nSegs];
            map.segLine = new int[nSegs];
            map.segSide = new int[nSegs];
            for (int i = 0; i < nSegs; i++) {
                map.segV1[i] = sg.getShort() & 0xFFFF;
                map.segV2[i] = sg.getShort() & 0xFFFF;
                sg.getShort(); // angle
                map.segLine[i] = sg.getShort() & 0xFFFF;
                map.segSide[i] = sg.getShort() & 0xFFFF;
                sg.getShort(); // offset
            }
            final ByteBuffer ss = src.get("ssectors");
            final int nSs = ss.remaining() / 4;
            map.ssNumSegs = new int[nSs];
            map.ssFirstSeg = new int[nSs];
            for (int i = 0; i < nSs; i++) {
                map.ssNumSegs[i] = ss.getShort() & 0xFFFF;
                map.ssFirstSeg[i] = ss.getShort() & 0xFFFF;
            }
            final ByteBuffer nd = src.get("nodes");
            final int nNodes = nd.remaining() / 28;
            map.ndX = new int[nNodes];
            map.ndY = new int[nNodes];
            map.ndDx = new int[nNodes];
            map.ndDy = new int[nNodes];
            map.ndRight = new int[nNodes];
            map.ndLeft = new int[nNodes];
            for (int i = 0; i < nNodes; i++) {
                map.ndX[i] = nd.getShort();
                map.ndY[i] = nd.getShort();
                map.ndDx[i] = nd.getShort();
                map.ndDy[i] = nd.getShort();
                nd.position(nd.position() + 16); // both bboxes
                map.ndRight[i] = nd.getShort() & 0xFFFF;
                map.ndLeft[i] = nd.getShort() & 0xFFFF;
            }
        } catch (IOException noBsp) {
            // Map data without BSP lumps: the mesh builder has no fallback polygons.
        }

        final ByteBuffer th = src.get("things");
        while (th.remaining() >= 10) {
            map.things.add(new Thing(th.getShort(), th.getShort(),
                th.getShort(), th.getShort() & 0xFFFF, th.getShort() & 0xFFFF));
        }
        map.buildSkyTransfers(); // needs the linedefs and sidedefs, so it runs last
        return map;
    }

    /** Sector index containing DOOM-space point (x, y), or -1 for the void. */
    public int sectorAt(double x, double y) {
        double best = Double.MAX_VALUE;
        int sector = -1;
        for (Line l : lines) {
            if ((l.y1 > y) != (l.y2 > y)) {
                final double t = (y - l.y1) / (double) (l.y2 - l.y1);
                final double xi = l.x1 + t * (l.x2 - l.x1);
                final double d = xi - x;
                if (d > 1.0e-6 && d < best) {
                    best = d;
                    // Upward line: its front side faces +x, so the point lies on its back side.
                    sector = l.y2 > l.y1 ? l.backSector : l.frontSector;
                }
            }
        }
        return sector;
    }

    private int minFloorCache = Integer.MAX_VALUE, maxCeilCache = Integer.MIN_VALUE;

    /** The level's lowest base floor height: the bottom of its vertical extent. */
    public int minFloor() {
        if (minFloorCache == Integer.MAX_VALUE) {
            for (Sector s : sectors) {
                minFloorCache = Math.min(minFloorCache, s.floorH());
            }
        }
        return minFloorCache;
    }

    /** The level's highest base ceiling height: the top of its vertical extent. */
    public int maxCeil() {
        if (maxCeilCache == Integer.MIN_VALUE) {
            for (Sector s : sectors) {
                maxCeilCache = Math.max(maxCeilCache, s.ceilH());
            }
        }
        return maxCeilCache;
    }

    /**
     * The sector of the subsector polygon containing the point, or -1 outside
     * the map, matching {@code R_PointInSubsector}. Unlike {@link #sectorAt}, which takes
     * the nearest crossing in the +x direction, this never returns a sector for a point
     * beyond the walls or inside a solid pillar, so a thing authored outside the map is
     * discarded rather than spawned in mid-air.
     */
    public int sectorAtPoly(double x, double y) {
        for (SubPoly sp : subsectorPolys()) {
            if (pointInPoly(x, y, sp.xs(), sp.ys())) {
                return sp.sector();
            }
        }
        return -1;
    }

    private static boolean pointInPoly(double x, double y, double[] xs, double[] ys) {
        boolean in = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((ys[i] > y) != (ys[j] > y)) {
                final double xc = xs[i] + (y - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i]);
                if (x < xc) {
                    in = !in;
                }
            }
        }
        return in;
    }

    private List<List<Line>> sectorLines;

    /** Every linedef touching this sector: the adjacency P_RecursiveSound floods along. */
    public synchronized List<Line> linesOf(int sector) {
        if (sectorLines == null) {
            sectorLines = new ArrayList<>(sectors.size());
            for (int i = 0; i < sectors.size(); i++) {
                sectorLines.add(new ArrayList<>());
            }
            for (Line l : lines) {
                if (l.frontSector() >= 0) {
                    sectorLines.get(l.frontSector()).add(l);
                }
                if (l.backSector() >= 0 && l.backSector() != l.frontSector()) {
                    sectorLines.get(l.backSector()).add(l);
                }
            }
        }
        return sector >= 0 && sector < sectorLines.size() ? sectorLines.get(sector) : List.of();
    }

    /**
     * The subsector floorplans: the map bounding box clipped down the BSP by
     * each node's partition half-plane, then by the leaf's own segs: every
     * piece convex by construction (this is how GL nodes are born).
     */
    public synchronized List<SubPoly> subsectorPolys() {
        if (polys != null) {
            return polys;
        }
        polys = new ArrayList<>();
        if (ndX == null || ndX.length == 0) {
            return polys;
        }
        final double m = 64;
        final List<double[]> box = new ArrayList<>(List.of(
            new double[]{minX - m, minY - m}, new double[]{maxX + m, minY - m},
            new double[]{maxX + m, maxY + m}, new double[]{minX - m, maxY + m}));
        descend(ndX.length - 1, box);
        healTJunctions(polys);
        return polys;
    }

    // The SectorTriangulator and LatteMesh path shares vertices by construction, so no
    // wall-edge welding is needed for it. subsectorPolys below remains as the fallback for
    // sectors that fail to triangulate, and as the probe's area cross-check.

    /**
     * Weld T-junctions between adjacent subsector floor/ceiling polygons. Each BSP leaf is
     * clipped independently, so a partition/seg split can drop a vertex into the middle of a
     * neighbour's straight edge. The GPU then fills the straight edge and the two-segment
     * edge differently, leaving a hairline crack, clustered at corners. For every polygon
     * edge, any other polygon's vertex lying on it within a tight perpendicular tolerance is
     * inserted at that vertex's exact coordinates, so both polygons share an identical point.
     * Inserted points are collinear, so area()/pointInPoly()/sectorAtPoly() are unchanged, and no
     * vertex is moved (which would open a new floor↔wall-base crack).
     */
    private static void healTJunctions(List<SubPoly> polys) {
        if (polys.size() < 2) {
            return;
        }
        // A T-junction vertex is welded within this perpendicular distance of an edge. 1/64 is
        // too tight: in a deep BSP tree one side of a junction comes from a long chain of clip()
        // divides and the other from a raw integer vertex, and the two can differ by more than
        // that, leaving the crack in place. 0.1 map units is still sub-pixel at the /32 world
        // scale and tolerates the floating-point drift.
        final double perp = 0.1;          // max off-edge distance to weld (doom units)
        final double cell = 64.0;         // spatial-hash cell size
        final java.util.Map<Long, List<double[]>> grid = new java.util.HashMap<>();
        for (SubPoly sp : polys) {
            for (int i = 0; i < sp.xs().length; i++) {
                gridAdd(grid, sp.xs()[i], sp.ys()[i], cell);
            }
        }
        for (int pi = 0; pi < polys.size(); pi++) {
            final SubPoly sp = polys.get(pi);
            final int n = sp.xs().length;
            final List<double[]> out = new ArrayList<>(n + 4);
            for (int i = 0; i < n; i++) {
                final double ax = sp.xs()[i], ay = sp.ys()[i];
                final double bx = sp.xs()[(i + 1) % n], by = sp.ys()[(i + 1) % n];
                out.add(new double[]{ax, ay});
                final double ex = bx - ax, ey = by - ay;
                final double len2 = ex * ex + ey * ey;
                if (len2 < 1.0e-9) {
                    continue;
                }
                final List<double[]> ins = new ArrayList<>();
                for (double[] v : gridQuery(grid, ax, ay, bx, by, cell)) {
                    final double t = ((v[0] - ax) * ex + (v[1] - ay) * ey) / len2;
                    if (t <= 1.0e-4 || t >= 1.0 - 1.0e-4) {
                        continue; // an endpoint, not an interior T-junction
                    }
                    final double dx2 = v[0] - (ax + t * ex), dy2 = v[1] - (ay + t * ey);
                    if (dx2 * dx2 + dy2 * dy2 > perp * perp) {
                        continue; // not on this edge
                    }
                    ins.add(new double[]{t, v[0], v[1]});
                }
                ins.sort((p, q) -> Double.compare(p[0], q[0]));
                double lastT = -1;
                for (double[] w : ins) {
                    if (w[0] - lastT < 1.0e-4) {
                        continue; // dedup coincident insertions
                    }
                    out.add(new double[]{w[1], w[2]});
                    lastT = w[0];
                }
            }
            if (out.size() != n) {
                final double[] nxs = new double[out.size()];
                final double[] nys = new double[out.size()];
                for (int i = 0; i < out.size(); i++) {
                    nxs[i] = out.get(i)[0];
                    nys[i] = out.get(i)[1];
                }
                polys.set(pi, new SubPoly(nxs, nys, sp.sector()));
            }
        }
    }

    private static long cellKey(double x, double y, double cell) {
        final long cx = (long) Math.floor(x / cell);
        final long cy = (long) Math.floor(y / cell);
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    private static void gridAdd(java.util.Map<Long, List<double[]>> grid,
                                double x, double y, double cell) {
        grid.computeIfAbsent(cellKey(x, y, cell), k -> new ArrayList<>()).add(new double[]{x, y});
    }

    private static List<double[]> gridQuery(java.util.Map<Long, List<double[]>> grid,
                                            double ax, double ay, double bx, double by, double cell) {
        final List<double[]> out = new ArrayList<>();
        final long cx0 = (long) Math.floor(Math.min(ax, bx) / cell) - 1;
        final long cx1 = (long) Math.floor(Math.max(ax, bx) / cell) + 1;
        final long cy0 = (long) Math.floor(Math.min(ay, by) / cell) - 1;
        final long cy1 = (long) Math.floor(Math.max(ay, by) / cell) + 1;
        for (long cx = cx0; cx <= cx1; cx++) {
            for (long cy = cy0; cy <= cy1; cy++) {
                final List<double[]> bucket = grid.get((cx << 32) ^ (cy & 0xffffffffL));
                if (bucket != null) {
                    out.addAll(bucket);
                }
            }
        }
        return out;
    }

    private void descend(int child, List<double[]> poly) {
        if (poly.size() < 3) {
            return;
        }
        if ((child & 0x8000) != 0) {
            emitSubsector(child & 0x7FFF, poly);
            return;
        }
        // Right child = front (side 0) = cross <= 0 half-plane
        descend(ndRight[child], clip(poly, ndX[child], ndY[child], ndDx[child], ndDy[child], true));
        descend(ndLeft[child], clip(poly, ndX[child], ndY[child], ndDx[child], ndDy[child], false));
    }

    private void emitSubsector(int ss, List<double[]> region) {
        List<double[]> poly = region;
        int sector = -1;
        for (int i = 0; i < ssNumSegs[ss]; i++) {
            final int seg = ssFirstSeg[ss] + i;
            final int x1 = vertX[segV1[seg]], y1 = vertY[segV1[seg]];
            final int x2 = vertX[segV2[seg]], y2 = vertY[segV2[seg]];
            // The subsector lies on the seg's right side (v1->v2)
            poly = clip(poly, x1, y1, x2 - x1, y2 - y1, true);
            if (sector < 0) {
                final Line l = lines.get(segLine[seg]);
                sector = segSide[seg] == 0 ? l.frontSector() : l.backSector();
            }
        }
        // Drop numerical noise only, not real geometry. A threshold of 1.0 discards legitimate
        // thin subsectors, since a 2x1 triangle has area 1.0, which leaves no floor or ceiling
        // there and shows a hole through the level. Dense geometry produces many such slivers.
        if (sector >= 0 && poly.size() >= 3 && Math.abs(area(poly)) > 1.0e-3) {
            final double[] xs = new double[poly.size()];
            final double[] ys = new double[poly.size()];
            for (int i = 0; i < poly.size(); i++) {
                xs[i] = poly.get(i)[0];
                ys[i] = poly.get(i)[1];
            }
            polys.add(new SubPoly(xs, ys, sector));
        }
    }

    /** Sutherland-Hodgman: keep the side of (ox,oy)+(dx,dy) with cross<=0 (right) or >0. */
    private static List<double[]> clip(List<double[]> poly, double ox, double oy,
                                       double dx, double dy, boolean keepRight) {
        final List<double[]> out = new ArrayList<>(poly.size() + 2);
        final int n = poly.size();
        for (int i = 0; i < n; i++) {
            final double[] a = poly.get(i);
            final double[] b = poly.get((i + 1) % n);
            final double ca = dx * (a[1] - oy) - dy * (a[0] - ox);
            final double cb = dx * (b[1] - oy) - dy * (b[0] - ox);
            final boolean inA = keepRight ? ca <= 1.0e-7 : ca >= -1.0e-7;
            final boolean inB = keepRight ? cb <= 1.0e-7 : cb >= -1.0e-7;
            if (inA) {
                out.add(a);
            }
            if (inA != inB) {
                final double t = ca / (ca - cb);
                out.add(new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])});
            }
        }
        return out;
    }

    private static double area(List<double[]> poly) {
        double s = 0;
        for (int i = 0; i < poly.size(); i++) {
            final double[] a = poly.get(i);
            final double[] b = poly.get((i + 1) % poly.size());
            s += a[0] * b[1] - b[0] * a[1];
        }
        return s / 2;
    }

    private static ByteBuffer lump(String path) throws IOException {
        try (InputStream in = DoomMap.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing map lump " + path + ": run gradlew extractAssets");
            }
            return ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private static String name8(ByteBuffer bb) {
        final byte[] b = new byte[8];
        bb.get(b);
        int end = 0;
        while (end < 8 && b[end] != 0) {
            end++;
        }
        return new String(b, 0, end, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }
}
