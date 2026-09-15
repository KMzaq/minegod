package com.sande.mythictrpg.ai.knowledge;

import java.util.Objects;
import java.util.List;

/** Bounded current-conversation query for one speaking NPC. */
public record KnowledgeQuery(KnowledgeAccessContext access, String currentText, List<String> supplementalSearchTexts,
        int maximumResults) {
    public KnowledgeQuery {
        Objects.requireNonNull(access, "access");
        currentText = currentText == null ? "" : currentText.trim();
        supplementalSearchTexts = supplementalSearchTexts == null ? List.of()
                : supplementalSearchTexts.stream().filter(Objects::nonNull).map(String::trim)
                        .filter(text -> !text.isEmpty()).limit(8).toList();
        if (maximumResults < 1 || maximumResults > 8) {
            throw new IllegalArgumentException("maximumResults must be between 1 and 8");
        }
    }

    /** Compatibility constructor for callers with no prior-turn or intent-derived search hints. */
    public KnowledgeQuery(KnowledgeAccessContext access, String currentText, int maximumResults) {
        this(access, currentText, List.of(), maximumResults);
    }
}
