package com.sande.mythictrpg.quest;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.OptionalInt;

public record QuestEvaluationResult(Status status, ResourceLocation questId,
        int score, OptionalInt rewardTier, String reason) {
    public QuestEvaluationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(questId, "questId");
        rewardTier = rewardTier == null ? OptionalInt.empty() : rewardTier;
        reason = reason == null ? "" : reason;
    }

    public enum Status {
        COMPLETED,
        REVISION_REQUIRED,
        NOT_ASSIGNED,
        NOT_EVALUATION_QUEST,
        WRONG_NPC,
        REWARD_TABLE_MISSING,
        INVALID_REWARD_RANGE,
        COMPLETION_FAILED
    }
}
