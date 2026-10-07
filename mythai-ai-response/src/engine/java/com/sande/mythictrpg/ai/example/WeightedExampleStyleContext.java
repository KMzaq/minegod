package com.sande.mythictrpg.ai.example;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable weighted Style/Context query derived from one NPC and one conversation turn. */
public record WeightedExampleStyleContext(Map<DialogueExampleTag, Integer> tagWeights) {
    public WeightedExampleStyleContext {
        Objects.requireNonNull(tagWeights, "tagWeights");
        EnumMap<DialogueExampleTag, Integer> checked = new EnumMap<>(DialogueExampleTag.class);
        for (Map.Entry<DialogueExampleTag, Integer> entry : tagWeights.entrySet()) {
            DialogueExampleTag tag = Objects.requireNonNull(entry.getKey(), "example tag");
            Integer weight = entry.getValue();
            if (weight == null || weight < 1 || weight > 100) {
                throw new IllegalArgumentException("Example tag weight for " + tag + " must be between 1 and 100");
            }
            checked.put(tag, weight);
        }
        tagWeights = Map.copyOf(checked);
    }

    public int weight(DialogueExampleTag tag) {
        return tagWeights.getOrDefault(tag, 0);
    }

    public Set<DialogueExampleTag> tags() {
        return tagWeights.keySet();
    }

    public static WeightedExampleStyleContext equalWeight(Set<DialogueExampleTag> tags) {
        Objects.requireNonNull(tags, "tags");
        EnumMap<DialogueExampleTag, Integer> values = new EnumMap<>(DialogueExampleTag.class);
        tags.forEach(tag -> values.put(tag, 1));
        return new WeightedExampleStyleContext(values);
    }
}
