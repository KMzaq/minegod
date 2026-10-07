package com.sande.mythictrpg.ai.proposal;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Read-only quest boundaries supplied by the game/RPG layer for one conversation turn. */
public record QuestProposalConstraints(int maxDifficulty, Set<String> allowedObjectiveTypes,
        Set<String> allowedConcreteItemIds) {
    public QuestProposalConstraints {
        if (maxDifficulty < 0 || maxDifficulty > 100) {
            throw new IllegalArgumentException("maxDifficulty must be between 0 and 100");
        }
        allowedObjectiveTypes = normalizedLabels(allowedObjectiveTypes, "allowedObjectiveTypes");
        allowedConcreteItemIds = normalizedIds(allowedConcreteItemIds, "allowedConcreteItemIds");
    }

    public static QuestProposalConstraints conceptOnly() {
        return new QuestProposalConstraints(0, Set.of(), Set.of());
    }

    public boolean allowsObjectiveType(String objectiveType) {
        return objectiveType != null && allowedObjectiveTypes.contains(objectiveType.trim().toLowerCase(Locale.ROOT));
    }

    public boolean allowsConcreteItemId(String itemId) {
        return itemId != null && allowedConcreteItemIds.contains(itemId.trim());
    }

    private static Set<String> normalizedLabels(Set<String> values, String name) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain a blank value");
            }
            normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizedIds(Set<String> values, String name) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain a blank value");
            }
            normalized.add(value.trim());
        }
        return Set.copyOf(normalized);
    }
}
