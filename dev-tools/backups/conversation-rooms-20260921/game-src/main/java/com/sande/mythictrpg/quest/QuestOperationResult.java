package com.sande.mythictrpg.quest;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record QuestOperationResult(Status status, ResourceLocation questId, String reason) {
    public QuestOperationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(questId, "questId");
        reason = reason == null ? "" : reason;
    }

    public boolean succeeded() {
        return status == Status.ASSIGNED || status == Status.COMPLETED;
    }

    public Optional<String> reasonOptional() {
        return reason.isBlank() ? Optional.empty() : Optional.of(reason);
    }

    public enum Status {
        ASSIGNED,
        WAITING_FOR_PARTICIPANTS,
        SUBMITTED,
        PARTICIPATION_CLOSED,
        COMPLETED,
        ALREADY_ASSIGNED,
        ALREADY_COMPLETED,
        UNKNOWN_QUEST,
        FTB_DEFINITION_MISSING,
        NOT_ASSIGNED,
        OBJECTIVES_NOT_READY,
        AFFINITY_TOO_LOW,
        MAIN_QUEST_RESTRICTED,
        ATTENTION_STATE_UNAVAILABLE,
        WRONG_NPC,
        WRONG_INTERACTION_MODE,
        REWARD_INVALID,
        INTERNAL_ERROR
    }
}
