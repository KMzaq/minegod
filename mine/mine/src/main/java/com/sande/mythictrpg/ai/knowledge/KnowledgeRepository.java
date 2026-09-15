package com.sande.mythictrpg.ai.knowledge;

import java.util.List;

/** Swappable world-knowledge storage boundary. */
@FunctionalInterface
public interface KnowledgeRepository {
    List<KnowledgeEntry> all();
}
