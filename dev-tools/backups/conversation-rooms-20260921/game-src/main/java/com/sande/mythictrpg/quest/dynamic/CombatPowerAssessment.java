package com.sande.mythictrpg.quest.dynamic;

/** Result of an optional external combat-power integration. */
public record CombatPowerAssessment(Status status, double actualPower, double recommendedPower,
        int catchUpRewardTierBonus, String evidence) {
    public CombatPowerAssessment {
        if (status == null || evidence == null) {
            throw new NullPointerException("status and evidence are required");
        }
        if (!Double.isFinite(actualPower) || actualPower < 0.0D
                || !Double.isFinite(recommendedPower) || recommendedPower < 0.0D) {
            throw new IllegalArgumentException("combat power values must be finite and non-negative");
        }
        if (catchUpRewardTierBonus < 0 || catchUpRewardTierBonus > 1) {
            throw new IllegalArgumentException("catch-up bonus must be 0 or 1");
        }
        if (status == Status.UNAVAILABLE && catchUpRewardTierBonus != 0) {
            throw new IllegalArgumentException("unavailable assessment cannot grant a bonus");
        }
    }

    public static CombatPowerAssessment unavailable(String reason) {
        return new CombatPowerAssessment(Status.UNAVAILABLE, 0.0D, 0.0D, 0, reason);
    }

    public enum Status {
        AVAILABLE,
        UNAVAILABLE
    }
}
