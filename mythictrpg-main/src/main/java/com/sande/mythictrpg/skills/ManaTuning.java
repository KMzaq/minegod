package com.sande.mythictrpg.skills;

/** Shared skill resource balance. Edit these values together with the balance guide, then rebuild. */
public final class ManaTuning {
    /** Base maximum and initial mana for a newly created account, in mana points. */
    public static final int BASE_MAX_MANA = 200;
    /** Percentage of the current maximum regenerated per online second; 1.0 means 1%, 0.5 means 0.5%. */
    public static final double REGEN_PERCENT_PER_SECOND = 1.0;

    private ManaTuning() { }

    /**
     * Fixed-point numerator before division by 20 ticks/second * 100 percent.
     * The stored remainder therefore keeps its original denominator of 2000 across balance changes.
     * The explicit percentage also permits pure arithmetic tests without mutable global settings.
     */
    public static long regenerationNumerator(long maximumMicroMana, double percentPerSecond) {
        if (maximumMicroMana < 0 || !Double.isFinite(percentPerSecond)
                || percentPerSecond < 0 || percentPerSecond > 100
                || maximumMicroMana > Long.MAX_VALUE / 100)
            throw new IllegalArgumentException("Invalid mana regeneration inputs");
        return Math.round(maximumMicroMana * percentPerSecond);
    }
}
