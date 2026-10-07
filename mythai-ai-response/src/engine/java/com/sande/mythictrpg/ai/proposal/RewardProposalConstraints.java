package com.sande.mythictrpg.ai.proposal;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Read-only reward boundaries supplied by the game/RPG layer for one conversation turn. */
public record RewardProposalConstraints(int maxPowerLevel, Set<String> allowedCategories,
        Set<String> allowedConcreteItemIds) {
    public RewardProposalConstraints {
        if (maxPowerLevel < 0 || maxPowerLevel > 100) {
            throw new IllegalArgumentException("maxPowerLevel must be between 0 and 100");
        }
        allowedCategories = normalizedLabels(allowedCategories, "allowedCategories");
        allowedConcreteItemIds = normalizedIds(allowedConcreteItemIds, "allowedConcreteItemIds");
    }

    public static RewardProposalConstraints none() {
        return new RewardProposalConstraints(0, Set.of(), Set.of());
    }

    public boolean allowsCategory(String category) {
        return category != null && allowedCategories.contains(category.trim().toLowerCase(Locale.ROOT));
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
