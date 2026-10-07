package com.sande.mythictrpg.ai.reaction;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import com.sande.mythictrpg.ai.tag.ExampleRetrievalResult;
import com.sande.mythictrpg.ai.tag.NpcExampleRetriever;
import com.sande.mythictrpg.ai.tag.NpcTagProfile;

import java.util.Objects;

/**
 * Produces two independent inputs for a future prompt builder. This class never invokes an LLM.
 */
public final class ReactionPreparationService {
    private final ReactionGuidelineRetriever guidelines;
    private final NpcExampleRetriever examples;

    public ReactionPreparationService(ReactionGuidelineRetriever guidelines, NpcExampleRetriever examples) {
        this.guidelines = Objects.requireNonNull(guidelines, "guidelines");
        this.examples = Objects.requireNonNull(examples, "examples");
    }

    public ReactionExamplePreparation prepare(SituationContext situation, AiDialogueModels.GodPersona persona,
            NpcTagProfile npc, RelationshipMetrics relationship, int maximumExamples) {
        ReactionGuidelineSelection selectedGuidelines = guidelines.retrieve(situation, npc);
        ExampleRetrievalResult selectedExamples = examples.retrieve(persona, npc, relationship, maximumExamples);
        return new ReactionExamplePreparation(selectedGuidelines, selectedExamples);
    }
}
