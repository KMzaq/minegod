package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Authored registered Minecraft effect; -1 is an owned permanent blessing. */
public record BlessingRewardEntry(ResourceLocation effectId, int durationTicks,
        int amplifier) implements RewardEntry {
    public static final int PERMANENT_DURATION = -1;
    public static final int MAX_AMPLIFIER = 16;
    public static final int RESISTANCE_MAX_AMPLIFIER = 3;
    private static final ResourceLocation RESISTANCE = ResourceLocation.withDefaultNamespace("resistance");

    public BlessingRewardEntry {
        Objects.requireNonNull(effectId, "effectId");
        if (durationTicks != PERMANENT_DURATION && (durationTicks < 20 || durationTicks > 72_000)) {
            throw new IllegalArgumentException("Blessing reward duration must be -1 (permanent) or between 20 and 72000 ticks");
        }
        validateAmplifier(effectId, amplifier);
    }

    /** Authored game blessings only; this does not cap commands, potions or another mod's effects. */
    public static void validateAmplifier(ResourceLocation effectId, int amplifier) {
        Objects.requireNonNull(effectId, "effectId");
        int maximum = RESISTANCE.equals(effectId) ? RESISTANCE_MAX_AMPLIFIER : MAX_AMPLIFIER;
        if (amplifier < 0 || amplifier > maximum)
            throw new IllegalArgumentException("Blessing amplifier for " + effectId + " must be between 0 and " + maximum);
    }

    public boolean permanent() { return durationTicks == PERMANENT_DURATION; }

    @Override
    public String description() {
        return "가호 " + effectId + " " + (amplifier + 1) + "단계 / "
                + (permanent() ? "영구 보유" : durationTicks + "틱");
    }
}
