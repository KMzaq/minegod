package com.sande.mythictrpg.quest.reward;

import java.util.List;

public record ResolvedQuestReward(String selectionTitle, List<RewardEntry> automaticRewards,
        List<RewardChoiceOption> choices) {
    public ResolvedQuestReward {
        selectionTitle = selectionTitle == null ? "보상을 선택하세요" : selectionTitle;
        automaticRewards = List.copyOf(automaticRewards);
        choices = List.copyOf(choices);
        var automaticDefinitions = definitions(automaticRewards);
        for (var choice : choices) {
            var selectedDefinitions = definitions(choice.rewards());
            if (selectedDefinitions.stream().anyMatch(automaticDefinitions::contains))
                throw new IllegalArgumentException("A blessing definition cannot occur in both automatic and selected rewards");
        }
    }

    private static java.util.Set<net.minecraft.resources.ResourceLocation> definitions(List<RewardEntry> rewards) {
        var seen = new java.util.HashSet<net.minecraft.resources.ResourceLocation>();
        for (var entry : rewards) {
            var id = entry instanceof AdaptiveBlessingRewardEntry value ? value.definitionId()
                    : entry instanceof ResolvedBlessingRewardEntry value ? value.blessing().definitionId() : null;
            if (id != null && !seen.add(id)) throw new IllegalArgumentException("Duplicate blessing definition in reward bundle: " + id);
        }
        return seen;
    }
}
