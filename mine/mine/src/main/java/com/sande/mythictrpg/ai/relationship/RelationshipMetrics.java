package com.sande.mythictrpg.ai.relationship;

/**
 * Long-lived, multi-dimensional relationship data between one player and one divine NPC.
 * This value deliberately contains no derived label and no transient emotion.
 */
public record RelationshipMetrics(int affinity, int trust, int respect, int caution) {
    public static final int MIN_SIGNED = -100;
    public static final int MAX_SIGNED = 100;
    public static final int MIN_CAUTION = 0;
    public static final int MAX_CAUTION = 100;

    public RelationshipMetrics {
        requireRange("affinity", affinity, MIN_SIGNED, MAX_SIGNED);
        requireRange("trust", trust, MIN_SIGNED, MAX_SIGNED);
        requireRange("respect", respect, MIN_SIGNED, MAX_SIGNED);
        requireRange("caution", caution, MIN_CAUTION, MAX_CAUTION);
    }

    public static RelationshipMetrics neutral() {
        return new RelationshipMetrics(0, 0, 0, 0);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum
                    + ": " + value);
        }
    }
}
