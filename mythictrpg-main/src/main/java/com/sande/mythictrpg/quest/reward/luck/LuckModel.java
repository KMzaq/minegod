package com.sande.mythictrpg.quest.reward.luck;

/** Configurable initial tuning. Used only by explicitly authored lucky pools/outcomes. */
public record LuckModel(double rarityPerLevel, double quantityPerLevel, double eventQualityPerLevel, int maxBonusCount) {
    public static final LuckModel DEFAULT = new LuckModel(0.04, 0.02, 0.03, 16);
    public LuckModel {
        coefficient(rarityPerLevel);
        coefficient(quantityPerLevel);
        coefficient(eventQualityPerLevel);
        if (maxBonusCount < 0 || maxBonusCount > 63) throw new IllegalArgumentException("maxBonusCount must be 0..63");
    }
    private static void coefficient(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Luck coefficient must be finite within 0..1");
    }
    public static int level(int level) { return Math.max(0, Math.min(17, level)); }
    public static int quality(int quality) {
        if (quality < -5 || quality > 5) throw new IllegalArgumentException("Luck quality must be within -5..5");
        return quality;
    }
    public double rewardWeight(int base, int quality, int level) { return weighted(base, quality, level, rarityPerLevel); }
    public double eventWeight(int base, int quality, int level) { return weighted(base, quality, level, eventQualityPerLevel); }
    private static double weighted(int base, int quality, int level, double coefficient) {
        if (base < 1 || base > 1_000_000) throw new IllegalArgumentException("Luck base weight must be 1..1000000");
        double exponent = LuckModel.level(level) * quality(quality) * coefficient;
        return base * Math.exp(Math.max(-20, Math.min(20, exponent)));
    }
    public int quantity(int base, int level, boolean bonusAllowed) {
        if (base < 1 || base > 64) throw new IllegalArgumentException("Lucky item count must be 1..64");
        int bonus = bonusAllowed ? Math.min(maxBonusCount, (int) Math.floor(base * LuckModel.level(level) * quantityPerLevel)) : 0;
        return Math.min(64, base + bonus);
    }
}
