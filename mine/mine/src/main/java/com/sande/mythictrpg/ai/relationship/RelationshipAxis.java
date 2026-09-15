package com.sande.mythictrpg.ai.relationship;

/**
 * Definition of one game-authoritative relationship axis. New axis definitions are data-contract additions,
 * rather than new fields in an NPC class.
 */
public record RelationshipAxis(String id, int minimum, int maximum, int defaultValue) {
    public RelationshipAxis {
        id = RelationshipAxes.requireAxisId(id);
        if (minimum > maximum) {
            throw new IllegalArgumentException("Relationship axis '" + id + "' minimum exceeds maximum");
        }
        if (defaultValue < minimum || defaultValue > maximum) {
            throw new IllegalArgumentException("Relationship axis '" + id + "' default is outside its range");
        }
    }

    public void validateValue(int value) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Relationship axis '" + id + "' must be between " + minimum
                    + " and " + maximum + ": " + value);
        }
    }
}
