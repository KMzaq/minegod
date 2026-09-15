package com.sande.mythictrpg.ai.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small deterministic repository intended for tests or an externally supplied storage replacement. */
public final class InMemoryMemoryRepository implements MemoryRepository {
    private final Map<UUID, NpcMemory> memories = new LinkedHashMap<>();

    @Override
    public synchronized List<NpcMemory> findByPair(String npcId, String playerId) {
        return memories.values().stream().filter(memory -> memory.npcId().equals(npcId)
                && memory.playerId().equals(playerId)).toList();
    }

    @Override
    public synchronized void upsert(NpcMemory memory) {
        memories.put(memory.id(), memory);
    }

    @Override
    public synchronized void remove(UUID memoryId) {
        memories.remove(memoryId);
    }
}
