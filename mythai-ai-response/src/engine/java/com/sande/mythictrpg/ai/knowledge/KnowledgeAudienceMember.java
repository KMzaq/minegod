package com.sande.mythictrpg.ai.knowledge;

import com.sande.mythictrpg.ai.relationship.RelationshipSnapshot;

import java.util.Objects;

/** One player who may hear disclosure, with that player's own relationship to the speaking NPC. */
public record KnowledgeAudienceMember(String playerId, KnowledgeAudienceState state,
        RelationshipSnapshot relationship) {
    public KnowledgeAudienceMember {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("Audience playerId must not be blank");
        }
        playerId = playerId.trim();
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(relationship, "relationship");
        if (!relationship.playerId().equals(playerId)) {
            throw new IllegalArgumentException("Audience relationship player ID does not match audience player");
        }
    }
}
