package com.sande.mythictrpg.ai.tag;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.relationship.RelationshipExampleRetriever;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;

import java.util.Objects;

/**
 * Small adapter that preserves the existing relationship example retriever while adding a separate
 * character-derived style context for future style-tagged example repositories.
 */
public final class NpcExampleRetriever {
    private final RelationshipExampleRetriever relationshipExamples;
    private final CharacterStyleTagMapper styleMapper;

    public NpcExampleRetriever(RelationshipExampleRetriever relationshipExamples, CharacterStyleTagMapper styleMapper) {
        this.relationshipExamples = Objects.requireNonNull(relationshipExamples, "relationshipExamples");
        this.styleMapper = Objects.requireNonNull(styleMapper, "styleMapper");
    }

    public ExampleRetrievalResult retrieve(AiDialogueModels.GodPersona persona, NpcTagProfile npc,
            RelationshipMetrics relationship, int maximumExamples) {
        return new ExampleRetrievalResult(styleMapper.map(npc),
                relationshipExamples.retrieve(persona, relationship, maximumExamples));
    }
}
