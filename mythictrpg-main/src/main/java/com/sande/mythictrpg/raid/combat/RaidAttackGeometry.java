package com.sande.mythictrpg.raid.combat;

/** Shared server hit and telegraph geometry; heights are offsets from the shape's center. */
public final class RaidAttackGeometry {
    public static final double MAX_EXTENT = 256;
    public static final double MAX_COORDINATE = 30_000_000;
    private static final double EDGE_EPSILON = 1.0e-9;

    private RaidAttackGeometry() { }

    /** Inclusive vertical cylinder. Invalid or unbounded input never contains a target. */
    public static boolean containsCircle(double px, double py, double pz,
            double cx, double cy, double cz, double radius, double minY, double maxY) {
        return validPointAndHeight(px, py, pz, cx, cy, cz, minY, maxY)
                && validExtent(radius) && withinHeight(py - cy, minY, maxY)
                && Math.hypot(px - cx, pz - cz) <= radius + EDGE_EPSILON;
    }

    /** Both ring edges are included; the hollow center is excluded when innerRadius is positive. */
    public static boolean containsRing(double px, double py, double pz,
            double cx, double cy, double cz, double innerRadius, double outerRadius,
            double minY, double maxY) {
        if (!validPointAndHeight(px, py, pz, cx, cy, cz, minY, maxY)
                || !validExtent(innerRadius) || !validExtent(outerRadius)
                || innerRadius > outerRadius || !withinHeight(py - cy, minY, maxY)) return false;
        double distance = Math.hypot(px - cx, pz - cz);
        return distance + EDGE_EPSILON >= innerRadius && distance <= outerRadius + EDGE_EPSILON;
    }

    /** Horizontal sector with inclusive range/angle edges; direction magnitude does not affect the shape. */
    public static boolean containsCone(double px, double py, double pz,
            double cx, double cy, double cz, double dirX, double dirZ,
            double range, double halfAngleDegrees, double minY, double maxY) {
        if (!validPointAndHeight(px, py, pz, cx, cy, cz, minY, maxY)
                || !validExtent(range) || !Double.isFinite(halfAngleDegrees)
                || halfAngleDegrees < 0 || halfAngleDegrees > 180
                || !Double.isFinite(dirX) || !Double.isFinite(dirZ)
                || !withinHeight(py - cy, minY, maxY)) return false;
        double directionLength = Math.hypot(dirX, dirZ);
        if (!Double.isFinite(directionLength) || directionLength == 0) return false;
        double dx = px - cx, dz = pz - cz;
        double distance = Math.hypot(dx, dz);
        if (distance > range + EDGE_EPSILON) return false;
        if (distance == 0) return true;
        double cosine = (dx / distance) * (dirX / directionLength)
                + (dz / distance) * (dirZ / directionLength);
        return cosine + EDGE_EPSILON >= Math.cos(Math.toRadians(halfAngleDegrees));
    }

    private static boolean validPointAndHeight(double px, double py, double pz,
            double cx, double cy, double cz, double minY, double maxY) {
        return validCoordinate(px) && validCoordinate(py) && validCoordinate(pz)
                && validCoordinate(cx) && validCoordinate(cy) && validCoordinate(cz)
                && Double.isFinite(minY) && Double.isFinite(maxY)
                && Math.abs(minY) <= MAX_EXTENT && Math.abs(maxY) <= MAX_EXTENT && minY <= maxY;
    }

    private static boolean validCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }

    private static boolean validExtent(double value) {
        return Double.isFinite(value) && value >= 0 && value <= MAX_EXTENT;
    }

    private static boolean withinHeight(double height, double minY, double maxY) {
        return height + EDGE_EPSILON >= minY && height <= maxY + EDGE_EPSILON;
    }
}
