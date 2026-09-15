package com.sande.mythictrpg.ai.proposal;

/** A game-agnostic reward concept. A concrete item ID is only valid when the game supplied it in the allowed catalogue. */
public record RewardConcept(String category, String theme, String description, String suggestedName,
        String concreteItemId, int powerLevel) {
    public RewardConcept {
        category = requireText(category, "category");
        theme = requireText(theme, "theme");
        description = requireText(description, "description");
        suggestedName = suggestedName == null ? "" : suggestedName.trim();
        concreteItemId = concreteItemId == null ? "" : concreteItemId.trim();
        if (powerLevel < 0 || powerLevel > 100) {
            throw new IllegalArgumentException("powerLevel must be between 0 and 100");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
