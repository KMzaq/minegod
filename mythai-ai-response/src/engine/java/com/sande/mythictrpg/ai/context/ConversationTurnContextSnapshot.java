package com.sande.mythictrpg.ai.context;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.vouch.PendingVouchInteraction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable input for one queued conversation turn. It binds a turn and request identity to exactly one session
 * snapshot and exactly one game snapshot, preventing another session's audience, relationship, or Quest/Reward data
 * from being reused accidentally while an LLM request waits in the scheduler.
 */
public record ConversationTurnContextSnapshot(UUID sessionId, long turnId, long generation, UUID requestId,
        Instant queuedAt, Instant dispatchedAt, String triggeringParticipantId, UUID triggeringPlayerId,
        AiDialogueModels.SessionSnapshot session, GameConversationSnapshot gameSnapshot,
        java.util.Optional<PendingVouchInteraction> pendingVouchInteraction) {
    public ConversationTurnContextSnapshot {
        Objects.requireNonNull(sessionId, "sessionId");
        if (turnId < 1L || generation < 1L) {
            throw new IllegalArgumentException("turnId and generation must be positive");
        }
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(queuedAt, "queuedAt");
        Objects.requireNonNull(dispatchedAt, "dispatchedAt");
        if (triggeringParticipantId == null || triggeringParticipantId.isBlank()) {
            throw new IllegalArgumentException("triggeringParticipantId must not be blank");
        }
        triggeringParticipantId = triggeringParticipantId.trim();
        Objects.requireNonNull(triggeringPlayerId, "triggeringPlayerId");
        session = Objects.requireNonNull(session, "session");
        gameSnapshot = Objects.requireNonNull(gameSnapshot, "gameSnapshot");
        pendingVouchInteraction = pendingVouchInteraction == null ? java.util.Optional.empty()
                : pendingVouchInteraction;
        if (!sessionId.equals(session.sessionId())) {
            throw new IllegalArgumentException("Conversation turn snapshot sessionId must match session");
        }
    }

    /** Compatibility constructor for callers that do not capture AI-owned pending social interactions. */
    public ConversationTurnContextSnapshot(UUID sessionId, long turnId, long generation, UUID requestId,
            Instant queuedAt, Instant dispatchedAt, String triggeringParticipantId, UUID triggeringPlayerId,
            AiDialogueModels.SessionSnapshot session, GameConversationSnapshot gameSnapshot) {
        this(sessionId, turnId, generation, requestId, queuedAt, dispatchedAt, triggeringParticipantId,
                triggeringPlayerId, session, gameSnapshot, java.util.Optional.empty());
    }
}
