package com.sande.mythictrpg.ai.example;

import java.util.Objects;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/** Immutable weighted query for the example library, scoped to one NPC whenever an NPC ID is available. */
public record ExampleRetrievalQuery(WeightedExampleStyleContext styleContext, ResourceLocation npcId, int maximumExamples) {
    public ExampleRetrievalQuery {
        Objects.requireNonNull(styleContext, "styleContext");
        if (maximumExamples < 1 || maximumExamples > 5) {
            throw new IllegalArgumentException("maximumExamples must be between 1 and 5");
        }
    }

    /** Compatibility convenience for callers that have not assigned tag weights yet. */
    public ExampleRetrievalQuery(Set<DialogueExampleTag> desiredTags, int maximumExamples) {
        this(WeightedExampleStyleContext.equalWeight(Set.copyOf(Objects.requireNonNull(desiredTags, "desiredTags"))), null,
                maximumExamples);
    }

    /** Compatibility constructor deliberately leaves scope broad for old diagnostics and tests. */
    public ExampleRetrievalQuery(WeightedExampleStyleContext styleContext, int maximumExamples) {
        this(styleContext, null, maximumExamples);
    }
}
