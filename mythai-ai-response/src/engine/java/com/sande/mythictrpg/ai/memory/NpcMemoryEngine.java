package com.sande.mythictrpg.ai.memory;

import com.sande.mythictrpg.MythicTrpg;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** AI-owned memory facade. It does not read or mutate game progression data. */
public final class NpcMemoryEngine {
    public static final NpcMemoryEngine INSTANCE = new NpcMemoryEngine(new JsonFileMemoryRepository(defaultFile()));
    private static final int MAX_RECENT_PER_PAIR = 40;

    private final MemoryRepository repository;
    private final MemoryRetriever retriever;

    public NpcMemoryEngine(MemoryRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.retriever = new MemoryRetriever(repository);
    }

    public List<MemorySnippet> relevant(MemoryQuery query) {
        try {
            return retriever.retrieve(query);
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("AI memory retrieval failed for NPC {} and player {}: {}", query.npcId(),
                    query.playerId(), exception.getMessage());
            return List.of();
        }
    }

    public void recordRecent(String npcId, String playerId, String summary, List<String> tags) {
        try {
            NpcMemory memory = NpcMemory.recent(npcId, playerId, summary, tags);
            repository.upsert(memory);
            pruneRecent(npcId, playerId);
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("AI recent-memory write failed for NPC {} and player {}: {}", npcId, playerId,
                    exception.getMessage());
        }
    }

    /** Called by a game event or later importance-extraction layer; it never changes Minecraft state. */
    public void rememberLongTerm(NpcMemory memory) {
        if (memory.type() != MemoryType.LONG_TERM) {
            throw new IllegalArgumentException("rememberLongTerm requires LONG_TERM memory");
        }
        try {
            repository.upsert(memory);
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("AI long-term memory write failed for NPC {} and player {}: {}", memory.npcId(),
                    memory.playerId(), exception.getMessage());
        }
    }

    private void pruneRecent(String npcId, String playerId) {
        List<NpcMemory> old = repository.findByPair(npcId, playerId).stream()
                .filter(memory -> memory.type() == MemoryType.RECENT)
                .sorted(Comparator.comparing(NpcMemory::createdAt).reversed()).skip(MAX_RECENT_PER_PAIR).toList();
        old.forEach(memory -> repository.remove(memory.id()));
    }

    private static Path defaultFile() {
        return FMLPaths.GAMEDIR.get().resolve("mythictrpg-ai-data/memories.json");
    }
}
