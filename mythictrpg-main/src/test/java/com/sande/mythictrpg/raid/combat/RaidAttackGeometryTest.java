package com.sande.mythictrpg.raid.combat;

/** Authoritative attack boundaries and malformed inputs; no Minecraft runtime or model calls. */
public final class RaidAttackGeometryTest {
    private static int checks;

    public static void main(String[] args) {
        circleAndHeightEdges();
        ringSafeZone();
        coneFacingAndEdges();
        translationAndRotation();
        malformedShapes();
        System.out.println("RaidAttackGeometryTest: PASS (" + checks + " checks; no server or LLM)");
    }

    private static void circleAndHeightEdges() {
        check(circle(3, 64, 4), "3-4-5 horizontal edge is included");
        check(circle(0, 63, 0) && circle(0, 67, 0), "relative vertical endpoints are included");
        check(!circle(3.0001, 64, 4), "point just beyond radial boundary is excluded");
        check(!circle(0, 62.9999, 0) && !circle(0, 67.0001, 0), "height cannot extend outside the authored band");
        check(RaidAttackGeometry.containsCircle(100, 10, -100, 100, 10, -100, 0, 0, 0), "zero-radius origin is included");
        check(!RaidAttackGeometry.containsCircle(100.0001, 10, -100, 100, 10, -100, 0, 0, 0), "zero-radius shape never becomes a wide attack");
    }

    private static void ringSafeZone() {
        check(!ring(0, 64, 0) && !ring(2.9999, 64, 0), "hollow center remains safe");
        check(ring(3, 64, 0) && ring(5, 64, 0), "both ring edges are included");
        check(!ring(5.0001, 64, 0), "outside ring remains safe");
        check(!ring(4, 67.0001, 0), "ring height follows the same vertical contract");
        check(RaidAttackGeometry.containsRing(3, 0, 0, 0, 0, 0, 3, 3, 0, 0), "equal radii form the authored edge");
        check(!RaidAttackGeometry.containsRing(2.9999, 0, 0, 0, 0, 0, 3, 3, 0, 0), "equal-radius ring retains its hollow center");
    }

    private static void coneFacingAndEdges() {
        check(cone(5, 64, 0, 1, 0, 45), "facing range endpoint is included");
        double diagonal = 5 / Math.sqrt(2);
        check(cone(diagonal, 64, diagonal, 1, 0, 45), "positive angle endpoint is included");
        check(cone(diagonal, 64, -diagonal, 1, 0, 45), "negative angle endpoint is included");
        check(!cone(0, 64, 4, 1, 0, 45) && !cone(-4, 64, 0, 1, 0, 45), "side and rear safe zones remain outside");
        check(!cone(5.0001, 64, 0, 1, 0, 45), "cone range does not grow with angle");
        check(cone(0, 64, 0, 1, 0, 0), "sector origin is included even at zero angle");
        check(cone(4, 64, 0, 1, 0, 0) && !cone(4, 64, 0.01, 1, 0, 0), "zero-angle sector remains a ray");
        check(cone(-4, 64, 0, 1, 0, 180), "180-degree half-angle includes the rear edge");
        check(cone(4, 64, 0, 0.001, 0, 45) && cone(4, 64, 0, 100, 0, 45), "direction magnitude cannot alter the attack");
        check(!cone(4, 67.0001, 0, 1, 0, 45), "cone height excludes upper floor");
    }

    private static void translationAndRotation() {
        for (int degrees = 0; degrees < 360; degrees += 15) {
            double facing = Math.toRadians(degrees);
            double inside = Math.toRadians(degrees + 20), outside = Math.toRadians(degrees + 80);
            double cx = 2_000_000, cy = 70, cz = -2_000_000;
            check(RaidAttackGeometry.containsCone(cx + 4 * Math.cos(inside), cy, cz + 4 * Math.sin(inside),
                    cx, cy, cz, Math.cos(facing), Math.sin(facing), 5, 40, -1, 3), "rotated translated cone includes interior " + degrees);
            check(!RaidAttackGeometry.containsCone(cx + 4 * Math.cos(outside), cy, cz + 4 * Math.sin(outside),
                    cx, cy, cz, Math.cos(facing), Math.sin(facing), 5, 40, -1, 3), "rotated translated cone excludes exterior " + degrees);
            check(RaidAttackGeometry.containsCircle(cx + 4 * Math.cos(facing), cy, cz + 4 * Math.sin(facing),
                    cx, cy, cz, 5, -1, 3), "circle is position-independent " + degrees);
            check(RaidAttackGeometry.containsRing(cx + 4 * Math.cos(facing), cy, cz + 4 * Math.sin(facing),
                    cx, cy, cz, 3, 5, -1, 3), "ring is position-independent " + degrees);
        }
    }

    private static void malformedShapes() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            check(!RaidAttackGeometry.containsCircle(invalid, 64, 0, 0, 64, 0, 5, -1, 3), "invalid target rejected");
            check(!RaidAttackGeometry.containsCircle(0, 64, 0, invalid, 64, 0, 5, -1, 3), "invalid origin rejected");
            check(!RaidAttackGeometry.containsCircle(0, 64, 0, 0, 64, 0, invalid, -1, 3), "invalid extent rejected");
            check(!RaidAttackGeometry.containsRing(4, 64, 0, 0, 64, 0, invalid, 5, -1, 3), "invalid hollow extent rejected");
            check(!cone(4, 64, 0, invalid, 0, 45), "invalid facing rejected");
            check(!cone(4, 64, 0, 1, 0, invalid), "invalid angle rejected");
            check(!RaidAttackGeometry.containsCone(4, 64, 0, 0, 64, 0, 1, 0, 5, 45, -1, invalid), "invalid height rejected");
        }
        check(!cone(0, 64, 0, 0, 0, 45), "zero facing is rejected even at origin");
        check(!cone(4, 64, 0, 1, 0, -1) && !cone(4, 64, 0, 1, 0, 181), "angle must be within supported interval");
        check(!RaidAttackGeometry.containsCircle(0, 64, 0, 0, 64, 0, -1, -1, 3), "negative radius rejected");
        check(!RaidAttackGeometry.containsCircle(0, 64, 0, 0, 64, 0, 257, -1, 3), "excessive extent rejected");
        check(!RaidAttackGeometry.containsCircle(0, 64, 0, 0, 64, 0, 5, 3, -1), "reversed vertical band rejected");
        check(!RaidAttackGeometry.containsRing(4, 64, 0, 0, 64, 0, 5, 3, -1, 3), "reversed ring radii rejected");
        check(!RaidAttackGeometry.containsCircle(0, 64, 0, 0, 64, 0, 5, -257, 3), "excessive vertical band rejected");
        check(!RaidAttackGeometry.containsCircle(30_000_001, 64, 0, 30_000_001, 64, 0, 5, -1, 3), "out-of-world coordinates rejected");
    }

    private static boolean circle(double x, double y, double z) {
        return RaidAttackGeometry.containsCircle(x, y, z, 0, 64, 0, 5, -1, 3);
    }
    private static boolean ring(double x, double y, double z) {
        return RaidAttackGeometry.containsRing(x, y, z, 0, 64, 0, 3, 5, -1, 3);
    }
    private static boolean cone(double x, double y, double z, double dirX, double dirZ, double angle) {
        return RaidAttackGeometry.containsCone(x, y, z, 0, 64, 0, dirX, dirZ, 5, angle, -1, 3);
    }
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
