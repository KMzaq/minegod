package com.sande.mythictrpg.ai.memory;

import java.util.List;
import java.util.Objects;

/** Minimal memory payload sent to an NPC's context; raw storage metadata is deliberately omitted. */
public record MemorySnippet(String memoryId, MemoryType type, String summary, List<String> tags, double importance) {
    public MemorySnippet {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(summary, "summary");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    static MemorySnippet from(NpcMemory memory) {
        return new MemorySnippet(memory.id().toString(), memory.type(), memory.summary(), memory.tags(), memory.importance());
    }
}
