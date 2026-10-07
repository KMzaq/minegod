package com.sande.mythictrpg.ai.knowledge;

import com.sande.mythictrpg.MythicTrpg;

/** AI-only knowledge facade. It returns permitted context snippets but has no world-state mutation capability. */
public final class KnowledgeEngine {
    public static final KnowledgeEngine INSTANCE = new KnowledgeEngine(JsonKnowledgeRepository.INSTANCE,
            new DefaultKnowledgeDisclosurePolicy());

    private final KnowledgeRetriever retriever;

    public KnowledgeEngine(KnowledgeRepository repository, KnowledgeDisclosurePolicy policy) {
        retriever = new KnowledgeRetriever(repository, policy);
    }

    public KnowledgeRetrievalResult relevant(KnowledgeQuery query) {
        try {
            return retriever.retrieve(query);
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("AI knowledge retrieval failed for NPC {}: {}", query.access().npcId(),
                    exception.getMessage());
            return new KnowledgeRetrievalResult(java.util.List.of(), java.util.List.of());
        }
    }
}
