package com.sande.mythictrpg.ai.knowledge;

/** Replaceable policy owned by the AI orchestration layer; it cannot alter game state. */
@FunctionalInterface
public interface KnowledgeDisclosurePolicy {
    KnowledgeDisclosureDecision decide(KnowledgeEntry entry, KnowledgeAccessContext context);
}
