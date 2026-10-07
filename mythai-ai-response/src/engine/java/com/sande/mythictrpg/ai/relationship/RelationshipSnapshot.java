package com.sande.mythictrpg.ai.relationship;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Portable, read-only relationship payload for one NPC/player pair.
 * The game owns this source of truth; the AI only receives a snapshot and may not modify it.
 */
public record RelationshipSnapshot(String npcId, String playerId, Map<String, Integer> axes) {
    public RelationshipSnapshot {
        npcId = requireIdentifier(npcId, "npcId");
        playerId = requireIdentifier(playerId, "playerId");
        Objects.requireNonNull(axes, "axes");
        Map<String, Integer> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : axes.entrySet()) {
            String axisId = RelationshipAxes.requireAxisId(entry.getKey());
            Integer value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException("Relationship axis '" + axisId + "' must have an integer value");
            }
            RelationshipAxes.known(axisId).ifPresent(axis -> axis.validateValue(value));
            checked.put(axisId, value);
        }
        for (String standardAxis : RelationshipAxes.standard().keySet()) {
            if (!checked.containsKey(standardAxis)) {
                throw new IllegalArgumentException("Relationship snapshot is missing standard axis '" + standardAxis + "'");
            }
        }
        axes = Map.copyOf(checked);
    }

    public OptionalInt axis(String axisId) {
        Integer value = axes.get(RelationshipAxes.requireAxisId(axisId));
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
