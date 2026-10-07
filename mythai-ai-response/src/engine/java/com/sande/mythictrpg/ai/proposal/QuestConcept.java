package com.sande.mythictrpg.ai.proposal;

/**
 * Narrative quest data. Empty optional fields mean that the game did not provide a concrete catalogue/constraint for
 * that detail, so the proposal remains a concept rather than an executable quest definition.
 */
public record QuestConcept(String title, String objectiveType, String targetConcept, String concreteItemId,
        int suggestedAmount, int difficulty) {
    public QuestConcept {
        title = requireText(title, "title");
        objectiveType = optionalText(objectiveType);
        targetConcept = requireText(targetConcept, "targetConcept");
        concreteItemId = optionalText(concreteItemId);
        if (suggestedAmount < 0 || suggestedAmount > 9_999) {
            throw new IllegalArgumentException("suggestedAmount must be between 0 and 9999");
        }
        if (difficulty < 0 || difficulty > 100) {
            throw new IllegalArgumentException("difficulty must be between 0 and 100");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = optionalText(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
