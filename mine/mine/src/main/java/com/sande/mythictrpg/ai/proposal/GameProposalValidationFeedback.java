package com.sande.mythictrpg.ai.proposal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A prior game-validator result fed back into a later AI turn. It reports a decision only; it does not make the AI
 * validator or the conversation engine authoritative over quests, rewards, or player state.
 */
public record GameProposalValidationFeedback(String proposalType, Status status, String reason,
        Map<String, String> allowedAdjustments) {
    public GameProposalValidationFeedback {
        if (proposalType == null || proposalType.isBlank()) {
            throw new IllegalArgumentException("proposalType must not be blank");
        }
        proposalType = proposalType.trim();
        status = Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason.trim();
        allowedAdjustments = allowedAdjustments == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(allowedAdjustments));
    }

    public enum Status {
        ACCEPTED,
        REJECTED,
        MODIFIED
    }
}
