package com.sande.mythictrpg.ai.knowledge;

import java.util.List;

/** Allowed snippets and safe diagnostic decisions are kept separate so withheld content cannot leak into context. */
public record KnowledgeRetrievalResult(List<KnowledgeSnippet> allowed, List<KnowledgeDisclosureDecision> decisions) {
    public KnowledgeRetrievalResult {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}
