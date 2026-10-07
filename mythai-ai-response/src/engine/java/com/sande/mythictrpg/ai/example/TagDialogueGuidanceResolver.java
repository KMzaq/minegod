package com.sande.mythictrpg.ai.example;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects independent tag rules for one NPC turn.  Rules are all retained within a small category cap, while the
 * bounded dialogue-example budget deliberately reserves one demonstration for situation, relationship, and base
 * voice before filling remaining slots.  This avoids a single high-weight category erasing the others.
 */
public final class TagDialogueGuidanceResolver {
    private static final List<DialogueExampleTagCategory> EXAMPLE_CATEGORY_ORDER = List.of(
            DialogueExampleTagCategory.SITUATION,
            DialogueExampleTagCategory.RELATIONSHIP,
            DialogueExampleTagCategory.PERSONALITY_SPEECH,
            DialogueExampleTagCategory.EMOTION,
            DialogueExampleTagCategory.CONVERSATION_CONTEXT);

    private final TagDialogueGuidanceRepository repository;

    public TagDialogueGuidanceResolver(TagDialogueGuidanceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<TagDialogueGuidance> resolve(WeightedExampleStyleContext context, int maximumExamples) {
        Objects.requireNonNull(context, "context");
        if (maximumExamples < 1 || maximumExamples > 5) {
            throw new IllegalArgumentException("maximumExamples must be between 1 and 5");
        }
        Map<DialogueExampleTagCategory, List<Candidate>> byCategory = new EnumMap<>(DialogueExampleTagCategory.class);
        for (DialogueExampleTag tag : context.tags()) {
            repository.find(tag).ifPresent(profile -> byCategory.computeIfAbsent(tag.category(), unused -> new ArrayList<>())
                    .add(new Candidate(profile, context.weight(tag))));
        }
        for (Map.Entry<DialogueExampleTagCategory, List<Candidate>> entry : byCategory.entrySet()) {
            entry.getValue().sort(candidateOrder());
            int cap = categoryRuleCap(entry.getKey());
            if (entry.getValue().size() > cap) {
                entry.setValue(new ArrayList<>(entry.getValue().subList(0, cap)));
            }
        }

        List<Candidate> selectedForExamples = reserveCategoryExamples(byCategory, maximumExamples);
        List<TagDialogueGuidance> resolved = new ArrayList<>();
        for (List<Candidate> candidates : byCategory.values()) {
            resolved.addAll(candidates.stream().map(candidate -> selectedForExamples.contains(candidate)
                    ? candidate.profile() : candidate.profile().withoutDialogue()).toList());
        }
        resolved.sort(Comparator.comparingInt(TagDialogueGuidance::priority).reversed()
                .thenComparing(guidance -> guidance.tag().name()));
        return List.copyOf(resolved);
    }

    private static List<Candidate> reserveCategoryExamples(Map<DialogueExampleTagCategory, List<Candidate>> byCategory,
            int maximumExamples) {
        List<Candidate> selected = new ArrayList<>();
        for (DialogueExampleTagCategory category : EXAMPLE_CATEGORY_ORDER) {
            if (selected.size() >= maximumExamples) {
                break;
            }
            List<Candidate> candidates = byCategory.getOrDefault(category, List.of());
            if (!candidates.isEmpty() && !candidates.getFirst().profile().dialogue().isEmpty()) {
                selected.add(candidates.getFirst());
            }
        }
        if (selected.size() >= maximumExamples) {
            return List.copyOf(selected);
        }
        return byCategory.values().stream().flatMap(List::stream).filter(candidate -> !selected.contains(candidate))
                .filter(candidate -> !candidate.profile().dialogue().isEmpty()).sorted(candidateOrder())
                .limit(maximumExamples - selected.size()).collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(), extras -> {
                            selected.addAll(extras);
                            return List.copyOf(selected);
                        }));
    }

    private static Comparator<Candidate> candidateOrder() {
        return Comparator.comparingInt((Candidate candidate) -> candidate.profile().priority()).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::weight).reversed())
                .thenComparing(candidate -> candidate.profile().tag().name());
    }

    private static int categoryRuleCap(DialogueExampleTagCategory category) {
        return switch (category) {
            case PERSONALITY_SPEECH -> 2;
            case SITUATION -> 2;
            case RELATIONSHIP, EMOTION, CONVERSATION_CONTEXT -> 1;
        };
    }

    private record Candidate(TagDialogueGuidance profile, int weight) {
    }
}
