package com.sande.mythictrpg.ai.proposal;

import java.util.List;

/** Immutable game-to-AI input for the Quest/Reward proposal layer. */
public record QuestRewardContext(QuestRewardConstraints constraints,
        List<GameProposalValidationFeedback> validationFeedback) {
    public QuestRewardContext {
        constraints = constraints == null ? QuestRewardConstraints.safeDefaults() : constraints;
        validationFeedback = validationFeedback == null ? List.of() : List.copyOf(validationFeedback);
    }

    public static QuestRewardContext safeDefaults() {
        return new QuestRewardContext(QuestRewardConstraints.safeDefaults(), List.of());
    }
}
