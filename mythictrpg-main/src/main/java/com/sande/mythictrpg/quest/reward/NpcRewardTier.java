package com.sande.mythictrpg.quest.reward;

import java.util.List;
import java.util.Objects;

public record NpcRewardTier(int tier, List<RewardEntry> rewards) {
    public NpcRewardTier {
        if (tier < 1 || tier > 100) {
            throw new IllegalArgumentException("Reward tier must be between 1 and 100");
        }
        rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
        if (rewards.isEmpty()) {
            throw new IllegalArgumentException("Reward tier must contain at least one reward");
        }
    }
}
