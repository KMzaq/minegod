package com.sande.mythictrpg.ai.reaction;

import com.sande.mythictrpg.ai.tag.ExampleStyleContext;
import com.sande.mythictrpg.ai.tag.NpcTagClassification;

import java.util.List;
import java.util.Objects;

/** Read-only diagnostic data for operators and tests; it is not sent to ordinary players. */
public record ReactionDebugSnapshot(List<String> rawNpcTags, NpcTagClassification classifiedNpcTags,
        SituationContext situation, ReactionGuidelineSelection guidelines, ExampleStyleContext exampleStyleContext,
        List<String> selectedExampleIds) {
    public ReactionDebugSnapshot {
        rawNpcTags = List.copyOf(Objects.requireNonNull(rawNpcTags, "rawNpcTags"));
        Objects.requireNonNull(classifiedNpcTags, "classifiedNpcTags");
        Objects.requireNonNull(situation, "situation");
        Objects.requireNonNull(guidelines, "guidelines");
        Objects.requireNonNull(exampleStyleContext, "exampleStyleContext");
        selectedExampleIds = List.copyOf(Objects.requireNonNull(selectedExampleIds, "selectedExampleIds"));
    }
}
