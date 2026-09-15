package com.sande.mythictrpg.interaction.director;

import java.util.OptionalLong;

/** Snapshot generations actually consulted while constructing an interaction plan. */
public record PlanRevisionStamp(long godDefinitionGeneration,
        OptionalLong interactionRuleGeneration) {
    public PlanRevisionStamp {
        if (godDefinitionGeneration <= 0) {
            throw new IllegalArgumentException("God definition generation must be positive");
        }
        interactionRuleGeneration = interactionRuleGeneration == null
                ? OptionalLong.empty() : interactionRuleGeneration;
        if (interactionRuleGeneration.isPresent() && interactionRuleGeneration.orElseThrow() <= 0) {
            throw new IllegalArgumentException("Interaction rule generation must be positive when present");
        }
    }

    public static PlanRevisionStamp spontaneous(long godGeneration, long ruleGeneration) {
        return new PlanRevisionStamp(godGeneration, OptionalLong.of(ruleGeneration));
    }

    public static PlanRevisionStamp explicit(long godGeneration) {
        return new PlanRevisionStamp(godGeneration, OptionalLong.empty());
    }
}
