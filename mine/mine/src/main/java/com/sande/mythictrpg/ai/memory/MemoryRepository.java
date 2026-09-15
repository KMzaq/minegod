package com.sande.mythictrpg.ai.memory;

import java.util.List;
import java.util.UUID;

/** Swappable memory storage boundary. The initial implementation uses a local JSON file. */
public interface MemoryRepository {
    List<NpcMemory> findByPair(String npcId, String playerId);

    void upsert(NpcMemory memory);

    void remove(UUID memoryId);
}
