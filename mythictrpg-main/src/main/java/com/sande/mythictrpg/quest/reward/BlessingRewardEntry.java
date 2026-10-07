package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Authored registered Minecraft effect; -1 is an owned permanent blessing. */
public record BlessingRewardEntry(ResourceLocation effectId, int durationTicks,
        int amplifier) implements RewardEntry {
    public static final int PERMANENT_DURATION = -1;

    public BlessingRewardEntry {
        Objects.requireNonNull(effectId, "effectId");
        if (durationTicks != PERMANENT_DURATION && (durationTicks < 20 || durationTicks > 72_000)) {
            throw new IllegalArgumentException("Blessing reward duration must be -1 (permanent) or between 20 and 72000 ticks");
        }
        if (amplifier < 0 || amplifier > 4) {
            throw new IllegalArgumentException("Blessing reward amplifier must be between 0 and 4");
        }
    }

    public boolean permanent() { return durationTicks == PERMANENT_DURATION; }

    @Override
    public String description() {
        return "가호 " + effectId + " " + (amplifier + 1) + "단계 / "
                + (permanent() ? "영구 보유" : durationTicks + "틱");
    }
}
