package com.sande.mythictrpg.quest.reward;

import java.util.List;

public record ResolvedQuestReward(String selectionTitle, List<RewardEntry> automaticRewards,
        List<RewardChoiceOption> choices) {
    public ResolvedQuestReward {
        selectionTitle = selectionTitle == null ? "보상을 선택하세요" : selectionTitle;
        automaticRewards = List.copyOf(automaticRewards);
        choices = List.copyOf(choices);
    }
}
