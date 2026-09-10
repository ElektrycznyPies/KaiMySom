package com.example.cityoverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Przybliżona geometria dla odległości rzędu kilku kilometrów. Bez zależności Androida. */
public final class GeoMath {
    private static final double EARTH = 6371008.8;
    private GeoMath() {}

    public static final class Point {
        public final double lat;
        public final double lon;
        public Point(double lat, double lon) { this.lat = lat; this.lon = lon; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Point)) return false;
            Point p = (Point) other;
            return Double.compare(lat, p.lat) == 0 && Double.compare(lon, p.lon) == 0;
        }
        @Override public int hashCode() { return Objects.hash(lat, lon); }
    }

    public static final class Nearest {
        public final double metres;
        public final double bearing;
        public final boolean inside;
        public Nearest(double metres, double bearing, boolean inside) {
            this.metres = metres; this.bearing = bearing; this.inside = inside;
        }
    }

    public static final class Rings {
        public final List<List<Point>> closed;
        public final boolean complete;
        Rings(List<List<Point>> closed, boolean complete) {
            this.closed = closed; this.complete = complete;
        }
    }

    private static double longitudeDelta(double degrees) {
        return ((degrees + 540.0) % 360.0) - 180.0;
    }

    public static double distance(Point a, Point b) {
        double lat = Math.toRadians(b.lat - a.lat);
        double lon = Math.toRadians(longitudeDelta(b.lon - a.lon));
        double h = Math.pow(Math.sin(lat / 2), 2)
                + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat))
                * Math.pow(Math.sin(lon / 2), 2);
        return 2 * EARTH * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h))));
    }

    /** Łączy odcinki członków relacji, również zapisane w odwrotnej kolejności.
     * Nie domyka sztucznie brakujących fragmentów. Wywołuj osobno dla outer i inner. */
    public static Rings stitchRings(List<List<Point>> parts) {
        Map<Point, List<Integer>> ends = new HashMap<>();
        boolean[] used = new boolean[parts.size()];
        List<List<Point>> rings = new ArrayList<>();
        boolean complete = true;
        for (int i = 0; i < parts.size(); i++) {
            List<Point> part = parts.get(i);
            if (part.size() < 2) { used[i] = true; complete = false; continue; }
            ends.computeIfAbsent(part.get(0), p -> new ArrayList<>()).add(i);
            ends.computeIfAbsent(part.get(part.size() - 1), p -> new ArrayList<>()).add(i);
        }
        for (int i = 0; i < parts.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            List<Point> chain = new ArrayList<>(parts.get(i));
            Point start = chain.get(0);
            while (!chain.get(chain.size() - 1).equals(start)) {
                Point end = chain.get(chain.size() - 1);
                List<Integer> candidates = ends.get(end);
                int next = -1;
                if (candidates != null) {
                    for (int index : candidates) {
                        if (!used[index]) { next = index; break; }
                    }
                }
                if (next < 0) break;
                used[next] = true;
                List<Point> part = parts.get(next);
                if (part.get(0).equals(end)) {
                    for (int j = 1; j < part.size(); j++) chain.add(part.get(j));
                } else {
                    for (int j = part.size() - 2; j >= 0; j--) chain.add(part.get(j));
                }
            }
            if (chain.size() >= 4 && chain.get(chain.size() - 1).equals(start)) rings.add(chain);
            else complete = false;
        }
        return new Rings(rings, complete);
    }

    private static boolean insideRing(Point origin, List<Point> ring) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            Point a = ring.get(i), b = ring.get(j);
            double ax = longitudeDelta(a.lon - origin.lon), ay = a.lat - origin.lat;
            double bx = longitudeDelta(b.lon - origin.lon), by = b.lat - origin.lat;
            if ((ay > 0) != (by > 0) && 0 < ax + (bx - ax) * (-ay) / (by - ay)) inside = !inside;
        }
        return inside;
    }

    /** Reguła parzystości obsługuje odrębne części obszaru, otwory i wyspy w otworach. */
    public static boolean contains(Point origin, List<List<Point>> rings) {
        boolean inside = false;
        for (List<Point> ring : rings) if (insideRing(origin, ring)) inside = !inside;
        return inside;
    }

    /** Szuka punktu na ODCINKU, więc środek mostu nie musi być węzłem OSM. */
    public static Nearest nearest(Point origin, List<List<Point>> lines, List<List<Point>> rings) {
        if (!rings.isEmpty() && contains(origin, rings)) return new Nearest(0, 0, true);
        double scaleY = EARTH * Math.PI / 180;
        double scaleX = scaleY * Math.cos(Math.toRadians(origin.lat));
        double bestSquared = Double.POSITIVE_INFINITY, bestX = 0, bestY = 0;
        for (List<Point> line : lines) {
            if (line.isEmpty()) continue;
            Point first = line.get(0);
            double ax = longitudeDelta(first.lon - origin.lon) * scaleX;
            double ay = (first.lat - origin.lat) * scaleY;
            if (ax * ax + ay * ay < bestSquared) {
                bestSquared = ax * ax + ay * ay; bestX = ax; bestY = ay;
            }
            for (int i = 1; i < line.size(); i++) {
                Point p = line.get(i);
                double bx = longitudeDelta(p.lon - origin.lon) * scaleX;
                double by = (p.lat - origin.lat) * scaleY;
                double dx = bx - ax, dy = by - ay;
                double lengthSquared = dx * dx + dy * dy;
                double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
                double x = ax + t * dx, y = ay + t * dy;
                double squared = x * x + y * y;
                if (squared < bestSquared) { bestSquared = squared; bestX = x; bestY = y; }
                ax = bx; ay = by;
            }
        }
        double bearing = (Math.toDegrees(Math.atan2(bestX, bestY)) + 360) % 360;
        return new Nearest(Math.sqrt(bestSquared), bearing, false);
    }

    public static String approximateDistance(double metres) {
        if (metres < 50) return "";
        return "ok. " + (Math.round(metres / 50) * 50) + " m";
    }
}
