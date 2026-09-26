package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Authored temporary registered Minecraft effect. */
public record BlessingRewardEntry(ResourceLocation effectId, int durationTicks,
        int amplifier) implements RewardEntry {
    public BlessingRewardEntry {
        Objects.requireNonNull(effectId, "effectId");
        if (durationTicks < 20 || durationTicks > 72_000) {
            throw new IllegalArgumentException("Blessing reward duration must be between 20 and 72000 ticks");
        }
        if (amplifier < 0 || amplifier > 4) {
            throw new IllegalArgumentException("Blessing reward amplifier must be between 0 and 4");
        }
    }

    @Override
    public String description() {
        return "가호 " + effectId + " " + (amplifier + 1) + "단계 / " + durationTicks + "틱";
    }
}
