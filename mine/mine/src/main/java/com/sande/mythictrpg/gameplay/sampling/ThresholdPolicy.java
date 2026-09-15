package com.sande.mythictrpg.gameplay.sampling;

public record ThresholdPolicy(long milestone) {
    public ThresholdPolicy {
        if (milestone <= 0) {
            throw new IllegalArgumentException("Milestone threshold must be positive");
        }
    }

    public static ThresholdPolicy milestone(long value) {
        return new ThresholdPolicy(value);
    }
}
