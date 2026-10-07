package com.sande.mythictrpg.ai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local JSON-file implementation. The format is intentionally independent from Minecraft SavedData. */
public final class JsonFileMemoryRepository implements MemoryRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<UUID, NpcMemory> memories = new LinkedHashMap<>();
    private boolean loaded;

    public JsonFileMemoryRepository(Path file) {
        this.file = java.util.Objects.requireNonNull(file, "file");
    }

    @Override
    public synchronized List<NpcMemory> findByPair(String npcId, String playerId) {
        ensureLoaded();
        return memories.values().stream().filter(memory -> memory.npcId().equals(npcId)
                && memory.playerId().equals(playerId)).toList();
    }

    @Override
    public synchronized void upsert(NpcMemory memory) {
        ensureLoaded();
        memories.put(memory.id(), memory);
        write();
    }

    @Override
    public synchronized void remove(UUID memoryId) {
        ensureLoaded();
        if (memories.remove(memoryId) != null) {
            write();
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    RawStore raw = GSON.fromJson(reader, RawStore.class);
                    if (raw != null && raw.memories != null) {
                        for (RawMemory entry : raw.memories) {
                            NpcMemory memory = entry.toMemory();
                            if (memories.putIfAbsent(memory.id(), memory) != null) {
                                throw new IllegalArgumentException("Duplicate memory ID " + memory.id());
                            }
                        }
                    }
                }
            }
            loaded = true;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load AI memory file " + file + ": " + exception.getMessage(),
                    exception);
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            RawStore raw = new RawStore();
            raw.schemaVersion = 1;
            raw.memories = memories.values().stream().map(RawMemory::from).toList();
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(raw, writer);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not write AI memory file " + file + ": " + exception.getMessage(),
                    exception);
        }
    }

    private static final class RawStore {
        private int schemaVersion;
        private List<RawMemory> memories = new ArrayList<>();
    }

    private static final class RawMemory {
        private String id;
        private String npcId;
        private String playerId;
        private String type;
        private double importance;
        private String summary;
        private List<String> tags;
        private String createdAt;

        private NpcMemory toMemory() {
            return new NpcMemory(UUID.fromString(id), npcId, playerId, MemoryType.valueOf(type), importance, summary,
                    tags, Instant.parse(createdAt));
        }

        private static RawMemory from(NpcMemory memory) {
            RawMemory raw = new RawMemory();
            raw.id = memory.id().toString();
            raw.npcId = memory.npcId();
            raw.playerId = memory.playerId();
            raw.type = memory.type().name();
            raw.importance = memory.importance();
            raw.summary = memory.summary();
            raw.tags = memory.tags();
            raw.createdAt = memory.createdAt().toString();
            return raw;
        }
    }
}
