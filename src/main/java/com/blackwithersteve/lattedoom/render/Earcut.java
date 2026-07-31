package com.blackwithersteve.lattedoom.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Java port of mapbox/earcut (https://github.com/mapbox/earcut), the industry-standard
 * polygon-with-holes triangulator — ISC License, Copyright (c) 2016 Mapbox. Ported for
 * Latte Doom from the upstream main-branch earcut.js.
 *
 * Deliberate deviations from upstream, both safe for our small integer-coordinate inputs:
 * 1. The z-order-curve ear hashing and the hole-bridge block index are omitted (pure
 *    performance features for huge polygons; DOOM sectors are tiny).
 * 2. {@code filterPoints} removes only COINCIDENT points, not collinear ones. Upstream
 *    drops collinear vertices as redundant; we must keep every boundary vertex so that
 *    wall quads (built per linedef) share the floor rim's vertices EXACTLY — Latte Doom's
 *    watertight-geometry contract. The stall cascade (cure + split) still terminates.
 */
final class Earcut {

    private static final class Node {
        final int i;
        final double x, y;
        Node prev, next;

        Node(int i, double x, double y) {
            this.i = i;
            this.x = x;
            this.y = y;
        }
    }

    private boolean filteredOut;

    /** Triangulate; data = flat {x,y}*, holeIndices = vertex indices where each hole starts. */
    static int[] earcut(double[] data, int[] holeIndices) {
        return new Earcut().run(data, holeIndices);
    }

    private int[] run(double[] data, int[] holeIndices) {
        final boolean hasHoles = holeIndices != null && holeIndices.length > 0;
        final int outerLen = hasHoles ? holeIndices[0] * 2 : data.length;

        Node outerNode = linkedList(data, 0, outerLen, true);
        final List<Integer> triangles = new ArrayList<>();
        if (outerNode == null || outerNode.next == outerNode.prev) {
            return new int[0];
        }
        if (hasHoles) {
            outerNode = eliminateHoles(data, holeIndices, outerNode);
        }
        earcutLinked(outerNode, triangles);
        final int[] out = new int[triangles.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = triangles.get(i);
        }
        return out;
    }

    // ---------------------------------------------------------------- ring building

    private Node linkedList(double[] data, int start, int end, boolean clockwise) {
        Node last = null;
        if (clockwise == (signedArea(data, start, end) > 0)) {
            for (int i = start; i < end; i += 2) {
                last = insertNode(i / 2, data[i], data[i + 1], last);
            }
        } else {
            for (int i = end - 2; i >= start; i -= 2) {
                last = insertNode(i / 2, data[i], data[i + 1], last);
            }
        }
        if (last != null && equals(last, last.next)) {
            removeNode(last);
            last = last.next;
        }
        return last;
    }

    /**
     * Upstream removes collinear AND coincident points; we remove ONLY coincident ones
     * (see class doc — collinear boundary vertices are load-bearing for watertightness).
     */
    private Node filterPoints(Node start, Node end) {
        final boolean full = end == null || end == start;
        if (end == null) {
            end = start;
        }
        Node p = start;
        boolean again;
        do {
            again = false;
            if (p != p.next && equals(p, p.next)) {
                if (full || p == end) {
                    end = p.prev;
                }
                filteredOut = true;
                removeNode(p);
                p = p.prev;
                again = true;
            } else if (full || p != end) {
                p = p.next;
                again = !full;
            }
        } while (again || p != end);
        return end;
    }

    // ---------------------------------------------------------------- main clipper

    private void earcutLinked(Node ear, List<Integer> triangles) {
        if (ear == null) {
            return;
        }
        Node stop = ear;
        boolean cured = false;

        while (ear.prev != ear.next) {
            final Node prev = ear.prev;
            final Node next = ear.next;

            if (area(prev, ear, next) < 0 && isEar(ear)) {
                triangles.add(prev.i);
                triangles.add(ear.i);
                triangles.add(next.i);
                removeNode(ear);
                ear = next;
                stop = next;
                continue;
            }

            ear = next;
            if (ear == stop) {
                filteredOut = false;
                ear = filterPoints(ear, null);
                if (filteredOut) {
                    stop = ear;
                    continue;
                }
                if (!cured) {
                    ear = cureLocalIntersections(ear, triangles);
                    stop = ear;
                    cured = true;
                    continue;
                }
                splitEarcut(ear, triangles);
                break;
            }
        }
    }

    private boolean isEar(Node ear) {
        final Node a = ear.prev, b = ear, c = ear.next;
        final double ax = a.x, bx = b.x, cx = c.x, ay = a.y, by = b.y, cy = c.y;
        final double x0 = Math.min(ax, Math.min(bx, cx)), y0 = Math.min(ay, Math.min(by, cy));
        final double x1 = Math.max(ax, Math.max(bx, cx)), y1 = Math.max(ay, Math.max(by, cy));

        Node p = c.next;
        while (p != a) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && !(ax == p.x && ay == p.y)
                && pointInTriangle(ax, ay, bx, by, cx, cy, p.x, p.y)
                && area(p.prev, p, p.next) >= 0) {
                return false;
            }
            p = p.next;
        }
        return true;
    }

    private Node cureLocalIntersections(Node start, List<Integer> triangles) {
        Node p = start;
        boolean cured = false;
        do {
            final Node a = p.prev, b = p.next.next;
            if (intersects(a, p, p.next, b, false) && locallyInside(a, b) && locallyInside(b, a)) {
                triangles.add(a.i);
                triangles.add(p.i);
                triangles.add(b.i);
                removeNode(p);
                removeNode(p.next);
                p = start = b;
                cured = true;
            }
            p = p.next;
        } while (p != start);
        return cured ? filterPoints(p, null) : p;
    }

    private void splitEarcut(Node start, List<Integer> triangles) {
        Node a = start;
        do {
            Node b = a.next.next;
            while (b != a.prev) {
                if (a.i != b.i && isValidDiagonal(a, b)) {
                    Node c = splitPolygon(a, b);
                    a = filterPoints(a, a.next);
                    c = filterPoints(c, c.next);
                    earcutLinked(a, triangles);
                    earcutLinked(c, triangles);
                    return;
                }
                b = b.next;
            }
            a = a.next;
        } while (a != start);
    }

    // ---------------------------------------------------------------- holes

    private Node eliminateHoles(double[] data, int[] holeIndices, Node outerNode) {
        final List<Node> queue = new ArrayList<>();
        for (int i = 0; i < holeIndices.length; i++) {
            final int start = holeIndices[i] * 2;
            final int end = i < holeIndices.length - 1 ? holeIndices[i + 1] * 2 : data.length;
            final Node list = linkedList(data, start, end, false);
            if (list != null) {
                queue.add(getLeftmost(list));
            }
        }
        queue.sort(Earcut::compareXYSlope);
        for (Node hole : queue) {
            outerNode = eliminateHole(hole, outerNode);
        }
        return filterPoints(outerNode, null);
    }

    private static int compareXYSlope(Node a, Node b) {
        int c = Double.compare(a.x, b.x);
        if (c != 0) {
            return c;
        }
        c = Double.compare(a.y, b.y);
        if (c != 0) {
            return c;
        }
        final double sa = (a.next.y - a.y) / (a.next.x - a.x);
        final double sb = (b.next.y - b.y) / (b.next.x - b.x);
        return Double.compare(sa, sb);
    }

    private Node eliminateHole(Node hole, Node outerNode) {
        final Node bridge = findHoleBridge(hole, outerNode);
        if (bridge == null) {
            return outerNode;
        }
        final Node bridgeReverse = splitPolygon(bridge, hole);
        filterPoints(bridgeReverse, bridgeReverse.next);
        return filterPoints(bridge, bridge.next);
    }

    /** David Eberly's bridge-finding, upstream logic without the block index. */
    private Node findHoleBridge(Node hole, Node outerNode) {
        Node p = outerNode;
        final double hx = hole.x, hy = hole.y;
        double qx = Double.NEGATIVE_INFINITY;
        Node m = null;
        if (equals(hole, p)) {
            return p;
        }
        do {
            if (equals(hole, p.next)) {
                return p.next;
            } else if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
                final double x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y);
                if (x <= hx && x > qx) {
                    qx = x;
                    m = p.x < p.next.x ? p : p.next;
                    if (x == hx) {
                        return m; // hole touches outer segment; pick the leftmost endpoint
                    }
                }
            }
            p = p.next;
        } while (p != outerNode);

        if (m == null) {
            return null;
        }

        final double mx = m.x, my = m.y;
        double tanMin = Double.POSITIVE_INFINITY;
        p = outerNode;
        do {
            if (hx >= p.x && p.x >= mx && hx != p.x
                && pointInTriangle(hy < my ? hx : qx, hy, mx, my, hy < my ? qx : hx, hy, p.x, p.y)) {
                final double tan = Math.abs(hy - p.y) / (hx - p.x);
                if ((locallyInside(p, hole)
                        || (p.y == hy && p.next.y == hy && p.next.x > hx))
                    && (tan < tanMin || (tan == tanMin
                        && (p.x > m.x || (p.x == m.x && sectorContainsSector(m, p)))))) {
                    m = p;
                    tanMin = tan;
                }
            }
            p = p.next;
        } while (p != outerNode);

        return m;
    }

    private static boolean sectorContainsSector(Node m, Node p) {
        return area(m.prev, m, p.prev) < 0 && area(p.next, m, m.next) < 0;
    }

    private static Node getLeftmost(Node start) {
        Node p = start, leftmost = start;
        do {
            if (p.x < leftmost.x || (p.x == leftmost.x && p.y < leftmost.y)) {
                leftmost = p;
            }
            p = p.next;
        } while (p != start);
        return leftmost;
    }

    // ---------------------------------------------------------------- predicates

    private static boolean pointInTriangle(double ax, double ay, double bx, double by,
                                           double cx, double cy, double px, double py) {
        return (cx - px) * (ay - py) >= (ax - px) * (cy - py)
            && (ax - px) * (by - py) >= (bx - px) * (ay - py)
            && (bx - px) * (cy - py) >= (cx - px) * (by - py);
    }

    private static boolean isValidDiagonal(Node a, Node b) {
        final boolean zeroLength = equals(a, b)
            && area(a.prev, a, a.next) > 0 && area(b.prev, b, b.next) > 0;
        return a.next.i != b.i
            && (zeroLength || (locallyInside(a, b) && locallyInside(b, a)
                && (area(a.prev, a, b.prev) != 0 || area(a, b.prev, b) != 0)))
            && !intersectsPolygon(a, b)
            && (zeroLength || middleInside(a, b));
    }

    private static double area(Node p, Node q, Node r) {
        return (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y);
    }

    private static boolean equals(Node p1, Node p2) {
        return p1.x == p2.x && p1.y == p2.y;
    }

    private static boolean intersects(Node p1, Node q1, Node p2, Node q2, boolean includeBoundary) {
        final double o1 = area(p1, q1, p2);
        final double o2 = area(p1, q1, q2);
        final double o3 = area(p2, q2, p1);
        final double o4 = area(p2, q2, q1);
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0))
            && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) {
            return true;
        }
        if (!includeBoundary) {
            return false;
        }
        return (o1 == 0 && onSegment(p1, p2, q1))
            || (o2 == 0 && onSegment(p1, q2, q1))
            || (o3 == 0 && onSegment(p2, p1, q2))
            || (o4 == 0 && onSegment(p2, q1, q2));
    }

    private static boolean onSegment(Node p, Node q, Node r) {
        return q.x <= Math.max(p.x, r.x) && q.x >= Math.min(p.x, r.x)
            && q.y <= Math.max(p.y, r.y) && q.y >= Math.min(p.y, r.y);
    }

    private static boolean intersectsPolygon(Node a, Node b) {
        final double minX = Math.min(a.x, b.x), maxX = Math.max(a.x, b.x);
        final double minY = Math.min(a.y, b.y), maxY = Math.max(a.y, b.y);
        Node p = a;
        do {
            final Node n = p.next;
            if ((p.x > maxX && n.x > maxX) || (p.x < minX && n.x < minX)
                || (p.y > maxY && n.y > maxY) || (p.y < minY && n.y < minY)) {
                p = n;
                continue;
            }
            if (p.i != a.i && n.i != a.i && p.i != b.i && n.i != b.i
                && intersects(p, n, a, b, true)) {
                return true;
            }
            p = n;
        } while (p != a);
        return false;
    }

    private static boolean locallyInside(Node a, Node b) {
        return area(a.prev, a, a.next) < 0
            ? area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0
            : area(a, b, a.prev) < 0 || area(a, a.next, b) < 0;
    }

    private static boolean middleInside(Node a, Node b) {
        Node p = a;
        boolean inside = false;
        final double px = (a.x + b.x) / 2, py = (a.y + b.y) / 2;
        do {
            final Node n = p.next;
            if (((p.y > py) != (n.y > py))
                && (px < (n.x - p.x) * (py - p.y) / (n.y - p.y) + p.x)) {
                inside = !inside;
            }
            p = n;
        } while (p != a);
        return inside;
    }

    // ---------------------------------------------------------------- ring surgery

    private static Node splitPolygon(Node a, Node b) {
        final Node a2 = new Node(a.i, a.x, a.y);
        final Node b2 = new Node(b.i, b.x, b.y);
        final Node an = a.next;
        final Node bp = b.prev;
        a.next = b;
        b.prev = a;
        a2.next = an;
        an.prev = a2;
        b2.next = a2;
        a2.prev = b2;
        bp.next = b2;
        b2.prev = bp;
        return b2;
    }

    private static Node insertNode(int i, double x, double y, Node last) {
        final Node p = new Node(i, x, y);
        if (last == null) {
            p.prev = p;
            p.next = p;
        } else {
            p.next = last.next;
            p.prev = last;
            last.next.prev = p;
            last.next = p;
        }
        return p;
    }

    private static void removeNode(Node p) {
        p.next.prev = p.prev;
        p.prev.next = p.next;
    }

    private static double signedArea(double[] data, int start, int end) {
        double sum = 0;
        for (int i = start, j = end - 2; i < end; i += 2) {
            sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1]);
            j = i;
        }
        return sum;
    }

    private Earcut() {}
}
