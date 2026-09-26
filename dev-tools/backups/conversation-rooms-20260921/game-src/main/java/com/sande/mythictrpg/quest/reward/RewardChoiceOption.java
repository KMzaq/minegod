package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record RewardChoiceOption(ResourceLocation optionId, String displayName,
        List<RewardEntry> rewards) {
    public RewardChoiceOption {
        Objects.requireNonNull(optionId, "optionId");
        displayName = displayName == null ? "" : displayName.trim();
        if (displayName.isBlank() || displayName.codePointCount(0, displayName.length()) > 80) {
            throw new IllegalArgumentException("Reward option displayName must contain 1..80 code points");
        }
        rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
        if (rewards.isEmpty() || rewards.size() > 16) {
            throw new IllegalArgumentException("Reward option must contain 1..16 reward entries");
        }
    }

    public String summary() {
        return rewards.stream().map(RewardEntry::description)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
