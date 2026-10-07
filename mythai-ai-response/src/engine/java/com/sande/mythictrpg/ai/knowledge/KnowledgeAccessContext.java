package com.sande.mythictrpg.ai.knowledge;

import com.sande.mythictrpg.ai.relationship.EmotionSnapshot;
import com.sande.mythictrpg.ai.relationship.RelationshipSnapshot;

import java.util.List;
import java.util.Objects;

/** Facts needed for a disclosure decision; this context does not contain a knowledge entry's hidden content. */
public record KnowledgeAccessContext(String npcId, String requesterPlayerId, RelationshipSnapshot requesterRelationship,
        EmotionSnapshot currentEmotion, List<KnowledgeAudienceMember> audience) {
    public KnowledgeAccessContext {
        if (npcId == null || npcId.isBlank()) {
            throw new IllegalArgumentException("npcId must not be blank");
        }
        npcId = npcId.trim();
        if (requesterPlayerId == null || requesterPlayerId.isBlank()) {
            throw new IllegalArgumentException("requesterPlayerId must not be blank");
        }
        requesterPlayerId = requesterPlayerId.trim();
        Objects.requireNonNull(requesterRelationship, "requesterRelationship");
        Objects.requireNonNull(currentEmotion, "currentEmotion");
        if (!requesterRelationship.npcId().equals(npcId) || !requesterRelationship.playerId().equals(requesterPlayerId)) {
            throw new IllegalArgumentException("Requester relationship does not match knowledge access pair");
        }
        if (!currentEmotion.npcId().equals(npcId) || !currentEmotion.playerId().equals(requesterPlayerId)) {
            throw new IllegalArgumentException("Current emotion does not match knowledge access pair");
        }
        audience = audience == null ? List.of() : List.copyOf(audience);
    }
}
