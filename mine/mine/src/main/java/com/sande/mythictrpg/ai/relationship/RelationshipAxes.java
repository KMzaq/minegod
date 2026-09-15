package com.sande.mythictrpg.ai.relationship;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Standard v1 axes. A game may add other valid axis IDs without requiring an AI code change. */
public final class RelationshipAxes {
    public static final String AFFINITY = "affinity";
    public static final String TRUST = "trust";
    public static final String RESPECT = "respect";
    public static final String CAUTION = "caution";

    private static final Map<String, RelationshipAxis> STANDARD = standardAxes();

    private RelationshipAxes() {
    }

    public static Map<String, RelationshipAxis> standard() {
        return STANDARD;
    }

    public static Optional<RelationshipAxis> known(String axisId) {
        return Optional.ofNullable(STANDARD.get(axisId));
    }

    public static String requireAxisId(String axisId) {
        String normalized = axisId == null ? "" : axisId.trim();
        if (!normalized.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid relationship axis ID: " + axisId);
        }
        return normalized;
    }

    private static Map<String, RelationshipAxis> standardAxes() {
        Map<String, RelationshipAxis> axes = new LinkedHashMap<>();
        axes.put(AFFINITY, new RelationshipAxis(AFFINITY, -100, 100, 0));
        axes.put(TRUST, new RelationshipAxis(TRUST, -100, 100, 0));
        axes.put(RESPECT, new RelationshipAxis(RESPECT, -100, 100, 0));
        axes.put(CAUTION, new RelationshipAxis(CAUTION, 0, 100, 0));
        return Map.copyOf(axes);
    }
}
