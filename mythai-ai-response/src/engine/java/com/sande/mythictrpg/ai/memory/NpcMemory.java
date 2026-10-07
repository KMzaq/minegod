package com.sande.mythictrpg.ai.memory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A player-specific fact remembered by one NPC. It contains no game-side authority. */
public record NpcMemory(UUID id, String npcId, String playerId, MemoryType type, double importance, String summary,
        List<String> tags, Instant createdAt) {
    public NpcMemory {
        Objects.requireNonNull(id, "id");
        npcId = requireText(npcId, "npcId");
        playerId = requireText(playerId, "playerId");
        Objects.requireNonNull(type, "type");
        if (!Double.isFinite(importance) || importance < 0.0D || importance > 1.0D) {
            throw new IllegalArgumentException("Memory importance must be between 0.0 and 1.0: " + importance);
        }
        summary = requireText(summary, "summary");
        if (summary.codePointCount(0, summary.length()) > 1_000) {
            throw new IllegalArgumentException("Memory summary exceeds 1000 code points");
        }
        tags = immutableTags(tags);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static NpcMemory recent(String npcId, String playerId, String summary, List<String> tags) {
        return new NpcMemory(UUID.randomUUID(), npcId, playerId, MemoryType.RECENT, 0.15D, summary, tags,
                Instant.now());
    }

    private static List<String> immutableTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> checked = new LinkedHashSet<>();
        for (String tag : rawTags) {
            String normalized = requireText(tag, "memory tag");
            if (!normalized.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Invalid memory tag: " + tag);
            }
            checked.add(normalized);
        }
        return List.copyOf(checked);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
