package com.sande.mythictrpg.ai.tag;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Normalized AI-facing view of a NPC's raw character tags. */
public final class NpcTagClassification {
    private final Map<CharacterTagCategory, List<String>> tagsByCategory;

    public NpcTagClassification(Map<CharacterTagCategory, ? extends List<String>> source) {
        Objects.requireNonNull(source, "source");
        EnumMap<CharacterTagCategory, List<String>> normalized = new EnumMap<>(CharacterTagCategory.class);
        for (Map.Entry<CharacterTagCategory, ? extends List<String>> entry : source.entrySet()) {
            CharacterTagCategory category = Objects.requireNonNull(entry.getKey(), "tag category");
            Set<String> unique = new LinkedHashSet<>();
            if (entry.getValue() != null) {
                for (String value : entry.getValue()) {
                    if (value != null && !value.isBlank()) {
                        unique.add(value.trim());
                    }
                }
            }
            if (!unique.isEmpty()) {
                normalized.put(category, List.copyOf(unique));
            }
        }
        tagsByCategory = Map.copyOf(normalized);
    }

    public static NpcTagClassification empty() {
        return new NpcTagClassification(Map.of());
    }

    public Map<CharacterTagCategory, List<String>> asMap() {
        return tagsByCategory;
    }

    public List<String> tags(CharacterTagCategory category) {
        return tagsByCategory.getOrDefault(category, List.of());
    }

    public boolean hasCategory(CharacterTagCategory category) {
        return tagsByCategory.containsKey(category);
    }

    public boolean contains(String tag) {
        return tag != null && tagsByCategory.values().stream().anyMatch(values -> values.contains(tag));
    }
}
