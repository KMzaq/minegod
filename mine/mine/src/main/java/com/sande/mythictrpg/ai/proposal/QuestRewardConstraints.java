package com.sande.mythictrpg.ai.proposal;

/** Aggregate read-only Quest/Reward constraint snapshot supplied by the game. */
public record QuestRewardConstraints(QuestProposalConstraints quest, RewardProposalConstraints reward) {
    public QuestRewardConstraints {
        quest = quest == null ? QuestProposalConstraints.conceptOnly() : quest;
        reward = reward == null ? RewardProposalConstraints.none() : reward;
    }

    public static QuestRewardConstraints safeDefaults() {
        return new QuestRewardConstraints(QuestProposalConstraints.conceptOnly(), RewardProposalConstraints.none());
    }
}
