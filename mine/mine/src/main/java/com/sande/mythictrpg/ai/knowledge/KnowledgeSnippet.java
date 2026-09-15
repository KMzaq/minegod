package com.sande.mythictrpg.ai.knowledge;

/** Allowed context-only representation; access control fields are not repeated to the LLM. */
public record KnowledgeSnippet(String id, String title, String content, String category, String parentId) {
    static KnowledgeSnippet from(KnowledgeEntry entry) {
        return new KnowledgeSnippet(entry.id(), entry.title(), entry.content(), entry.category(), entry.parentId());
    }
}
