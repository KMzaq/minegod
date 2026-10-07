package com.sande.mythictrpg.ai.relationship;

import com.sande.mythictrpg.ai.proposal.AiGameProposal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Non-authoritative AI output. Creating this value does not write relationship data and is not an approval.
 * The gameplay owner must independently validate and apply it, if appropriate.
 */
public record RelationshipChangeProposal(String npcId, String targetPlayerId, Map<String, Integer> changes,
        String reason) implements AiGameProposal {
    public static final String TYPE = "relationship_change_proposal";

    public RelationshipChangeProposal {
        npcId = requireText(npcId, "npcId");
        targetPlayerId = requireText(targetPlayerId, "targetPlayerId");
        Objects.requireNonNull(changes, "changes");
        Map<String, Integer> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : changes.entrySet()) {
            String axisId = RelationshipAxes.requireAxisId(entry.getKey());
            Integer delta = entry.getValue();
            if (delta == null || delta == 0) {
                throw new IllegalArgumentException("Relationship proposal change for '" + axisId
                        + "' must be a non-zero integer");
            }
            checked.put(axisId, delta);
        }
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("Relationship proposal requires at least one change");
        }
        changes = Map.copyOf(checked);
        reason = requireText(reason, "reason");
    }

    public String type() {
        return TYPE;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
