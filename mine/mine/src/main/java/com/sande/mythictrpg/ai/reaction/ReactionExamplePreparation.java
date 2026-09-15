package com.sande.mythictrpg.ai.reaction;

import com.sande.mythictrpg.ai.tag.ExampleRetrievalResult;

import java.util.Objects;

/** Future prompt assembly input: guidance and examples remain intentionally distinct values. */
public record ReactionExamplePreparation(ReactionGuidelineSelection guidelines, ExampleRetrievalResult examples) {
    public ReactionExamplePreparation {
        Objects.requireNonNull(guidelines, "guidelines");
        Objects.requireNonNull(examples, "examples");
    }
}
