package com.sande.mythictrpg.ai.knowledge;

import java.util.Objects;

/** Safe diagnostic metadata. It never carries the withheld knowledge content. */
public record KnowledgeDisclosureDecision(String knowledgeId, KnowledgeDisclosureStatus status, String guidance) {
    public KnowledgeDisclosureDecision {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            throw new IllegalArgumentException("knowledgeId must not be blank");
        }
        knowledgeId = knowledgeId.trim();
        Objects.requireNonNull(status, "status");
        guidance = guidance == null ? "" : guidance.trim();
    }

    public boolean allowed() {
        return status == KnowledgeDisclosureStatus.ALLOWED;
    }
}
