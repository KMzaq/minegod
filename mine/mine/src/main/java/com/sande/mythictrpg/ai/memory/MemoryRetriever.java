package com.sande.mythictrpg.ai.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Simple relevance search that can later be replaced with an embedding/vector implementation behind MemoryRepository. */
public final class MemoryRetriever {
    private final MemoryRepository repository;

    public MemoryRetriever(MemoryRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
    }

    public List<MemorySnippet> retrieve(MemoryQuery query) {
        Set<String> terms = terms(query.currentText());
        return repository.findByPair(query.npcId(), query.playerId()).stream()
                .map(memory -> new ScoredMemory(memory, score(memory, terms)))
                .filter(scored -> terms.isEmpty() || scored.score() > 0.0D)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed()
                        .thenComparing(scored -> scored.memory().createdAt(), Comparator.reverseOrder()))
                .limit(query.maximumResults()).map(scored -> MemorySnippet.from(scored.memory())).toList();
    }

    private static double score(NpcMemory memory, Set<String> terms) {
        if (terms.isEmpty()) {
            return memory.importance() + recency(memory);
        }
        String summary = memory.summary().toLowerCase(Locale.ROOT);
        double score = 0.0D;
        for (String term : terms) {
            if (summary.contains(term)) {
                score += 2.0D;
            }
            if (memory.tags().stream().map(tag -> tag.toLowerCase(Locale.ROOT)).anyMatch(tag -> tag.equals(term))) {
                score += 3.0D;
            }
        }
        // Importance ranks memories only after the current conversation has established relevance.
        // Otherwise an old but important event would leak into unrelated dialogue.
        return score <= 0.0D ? 0.0D : score + memory.importance() + recency(memory);
    }

    private static double recency(NpcMemory memory) {
        if (memory.type() != MemoryType.RECENT) {
            return 0.0D;
        }
        long hours = Math.max(0L, Duration.between(memory.createdAt(), Instant.now()).toHours());
        return Math.max(0.0D, 0.4D - Math.min(hours, 96L) / 240.0D);
    }

    private static Set<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return Set.copyOf(terms);
    }

    private record ScoredMemory(NpcMemory memory, double score) {
    }
}
