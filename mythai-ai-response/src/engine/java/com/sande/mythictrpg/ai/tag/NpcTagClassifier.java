package com.sande.mythictrpg.ai.tag;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies explicit-over-auto classification once during NPC tag data loading. */
public final class NpcTagClassifier {
    private final CharacterTagRegistry registry;

    public NpcTagClassifier(CharacterTagRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public NpcTagClassification classify(List<String> rawTags, NpcTagClassification explicit) {
        Objects.requireNonNull(rawTags, "rawTags");
        Objects.requireNonNull(explicit, "explicit");
        EnumMap<CharacterTagCategory, List<String>> resolved = new EnumMap<>(CharacterTagCategory.class);
        for (CharacterTagCategory category : CharacterTagCategory.values()) {
            if (explicit.hasCategory(category)) {
                resolved.put(category, explicit.tags(category));
            }
        }
        Set<String> explicitlyAssignedTags = new LinkedHashSet<>();
        explicit.asMap().values().forEach(explicitlyAssignedTags::addAll);
        for (String rawTag : rawTags) {
            if (explicitlyAssignedTags.contains(rawTag)) {
                continue;
            }
            registry.find(rawTag).ifPresent(definition -> {
                if (!explicit.hasCategory(definition.category())) {
                    resolved.computeIfAbsent(definition.category(), ignored -> new ArrayList<>()).add(definition.name());
                }
            });
        }
        return new NpcTagClassification(resolved);
    }
}
