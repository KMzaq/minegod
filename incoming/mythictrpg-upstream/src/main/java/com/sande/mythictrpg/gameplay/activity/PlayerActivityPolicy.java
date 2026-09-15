package com.sande.mythictrpg.gameplay.activity;

public record PlayerActivityPolicy(long idleThresholdTicks,
                                   double positionEpsilon,
                                   double rotationEpsilonDegrees) {
    public static final long MIN_IDLE_THRESHOLD_TICKS = 1_200L;
    public static final long MAX_IDLE_THRESHOLD_TICKS = 72_000L;
    public static final PlayerActivityPolicy PRODUCTION_DEFAULT =
            new PlayerActivityPolicy(6_000L, 0.01D, 0.5D);

    public PlayerActivityPolicy {
        if (idleThresholdTicks < MIN_IDLE_THRESHOLD_TICKS
                || idleThresholdTicks > MAX_IDLE_THRESHOLD_TICKS) {
            throw new IllegalArgumentException("idleThresholdTicks must be between "
                    + MIN_IDLE_THRESHOLD_TICKS + " and " + MAX_IDLE_THRESHOLD_TICKS);
        }
        if (!Double.isFinite(positionEpsilon) || positionEpsilon <= 0.0D
                || !Double.isFinite(positionEpsilon * positionEpsilon)) {
            throw new IllegalArgumentException("positionEpsilon must be finite, positive, and safely squareable");
        }
        if (!Double.isFinite(rotationEpsilonDegrees) || rotationEpsilonDegrees <= 0.0D
                || rotationEpsilonDegrees > 180.0D) {
            throw new IllegalArgumentException("rotationEpsilonDegrees must be in (0, 180]");
        }
    }

    public double positionEpsilonSquared() {
        return positionEpsilon * positionEpsilon;
    }
}
