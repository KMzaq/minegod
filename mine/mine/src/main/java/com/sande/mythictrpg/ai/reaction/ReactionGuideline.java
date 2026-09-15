package com.sande.mythictrpg.ai.reaction;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Advisory content selected from supplied situation facts. It is never an executable game action. */
public record ReactionGuideline(ReactionGuidelineId id, Set<SituationSignal> triggers, List<String> guidelines,
        List<String> avoid, List<String> requiredContext, GuidelineRuleType ruleType, int baseScore,
        List<TagRelevanceBoost> tagBoosts) {
    public ReactionGuideline {
        Objects.requireNonNull(id, "id");
        triggers = Set.copyOf(Objects.requireNonNull(triggers, "triggers"));
        if (triggers.isEmpty()) {
            throw new IllegalArgumentException("Reaction guideline requires at least one trigger: " + id);
        }
        guidelines = clean(guidelines);
        avoid = clean(avoid);
        requiredContext = clean(requiredContext);
        Objects.requireNonNull(ruleType, "ruleType");
        tagBoosts = tagBoosts == null ? List.of() : List.copyOf(tagBoosts);
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).toList();
    }
}
