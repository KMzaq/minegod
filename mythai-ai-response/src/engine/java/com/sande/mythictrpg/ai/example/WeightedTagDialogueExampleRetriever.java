package com.sande.mythictrpg.ai.example;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Initial retriever: each matching tag contributes its StyleContext weight. It is deliberately stateless and keeps
 * the shared example repository independent from NPC data, ready to be replaced with embedding retrieval.
 */
public final class WeightedTagDialogueExampleRetriever implements DialogueExampleRetriever {
    private final DialogueExampleRepository repository;

    public WeightedTagDialogueExampleRetriever(DialogueExampleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<DialogueExampleSnippet> retrieve(ExampleRetrievalQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.styleContext().tagWeights().isEmpty()) {
            return List.of();
        }
        return repository.all().stream().filter(example -> example.availableTo(query.npcId()))
                .map(example -> ranked(example, query.styleContext()))
                .filter(RankedExample::relevant).sorted(Comparator.comparingInt(RankedExample::score).reversed()
                        .thenComparing(Comparator.comparingInt(RankedExample::matchedTags).reversed())
                        .thenComparing(ranked -> ranked.example().exampleId()))
                .limit(query.maximumExamples()).map(ranked -> DialogueExampleSnippet.from(ranked.example())).toList();
    }

    private static RankedExample ranked(DialogueExample example, WeightedExampleStyleContext context) {
        int score = 0;
        int matchedTags = 0;
        for (DialogueExampleTag tag : example.tags()) {
            int weight = context.weight(tag);
            if (weight > 0) {
                matchedTags++;
                score += weight;
            }
        }
        return new RankedExample(example, score, matchedTags, matchedTags > 0);
    }

    private record RankedExample(DialogueExample example, int score, int matchedTags, boolean relevant) {
    }
}
