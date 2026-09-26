package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** One server-executable item reward. */
public record NpcRewardEntry(ResourceLocation itemId, int count) implements RewardEntry {
    public NpcRewardEntry {
        Objects.requireNonNull(itemId, "itemId");
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("Reward item count must be between 1 and 64");
        }
    }

    @Override
    public String description() {
        return itemId + " x" + count;
    }
}
