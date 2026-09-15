package com.sande.mythictrpg.ai.reaction;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Optional data-defined relevance increase when all listed NPC source tags are present. */
public record TagRelevanceBoost(Set<String> requiredTags, int score, String reason) {
    public TagRelevanceBoost {
        Objects.requireNonNull(requiredTags, "requiredTags");
        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : requiredTags) {
            if (tag != null && !tag.isBlank()) {
                normalized.add(tag.trim());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Tag relevance boost requires at least one tag");
        }
        requiredTags = Set.copyOf(normalized);
        reason = reason == null ? "NPC tag relevance" : reason.trim();
    }
}
