package com.sande.mythictrpg.ai.context;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import com.sande.mythictrpg.ai.tone.NpcSocialAuthorityContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable game-owned input captured before AI prompt construction. It contains observations only; no Minecraft
 * runtime objects or mutation handles may cross into the LLM worker.
 */
public record GameConversationSnapshot(
        Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationshipsByPlayerParticipantId,
        Map<String, Object> gameState,
        QuestRewardContext questRewardContext,
        Map<String, NpcSocialAuthorityContext> socialAuthorityByDivineParticipantId) {
    public GameConversationSnapshot {
        Objects.requireNonNull(relationshipsByPlayerParticipantId, "relationshipsByPlayerParticipantId");
        Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, AiDialogueModels.RelationshipContext>> entry
                : relationshipsByPlayerParticipantId.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Relationship participant ID must not be blank");
            }
            relationships.put(entry.getKey(), Map.copyOf(Objects.requireNonNull(entry.getValue(),
                    "relationship map")));
        }
        relationshipsByPlayerParticipantId = Map.copyOf(relationships);
        gameState = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(gameState, "gameState")));
        questRewardContext = questRewardContext == null ? QuestRewardContext.safeDefaults() : questRewardContext;
        Map<String, NpcSocialAuthorityContext> authority = new LinkedHashMap<>();
        if (socialAuthorityByDivineParticipantId != null) {
            for (Map.Entry<String, NpcSocialAuthorityContext> entry : socialAuthorityByDivineParticipantId.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                    authority.put(entry.getKey().trim(), entry.getValue());
                }
            }
        }
        socialAuthorityByDivineParticipantId = Map.copyOf(authority);
    }

    /** Compatibility constructor for game integrations that have not supplied Quest/Reward constraints yet. */
    public GameConversationSnapshot(
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationshipsByPlayerParticipantId,
            Map<String, Object> gameState) {
        this(relationshipsByPlayerParticipantId, gameState, QuestRewardContext.safeDefaults(), Map.of());
    }

    /** Compatibility constructor for integrations that already provide quest/reward context but no social authority. */
    public GameConversationSnapshot(
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationshipsByPlayerParticipantId,
            Map<String, Object> gameState, QuestRewardContext questRewardContext) {
        this(relationshipsByPlayerParticipantId, gameState, questRewardContext, Map.of());
    }
}
