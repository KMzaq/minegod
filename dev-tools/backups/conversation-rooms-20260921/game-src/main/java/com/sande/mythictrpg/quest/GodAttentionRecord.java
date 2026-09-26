package com.sande.mythictrpg.quest;

import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Persistent record of the players chosen through one God's first authored quest. */
public record GodAttentionRecord(ResourceLocation godId, ResourceLocation entryQuestId,
        Instant firstAssignedAt, Set<UUID> focusedPlayerIds) {
    public GodAttentionRecord {
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(entryQuestId, "entryQuestId");
        Objects.requireNonNull(firstAssignedAt, "firstAssignedAt");
        focusedPlayerIds = Set.copyOf(Objects.requireNonNull(focusedPlayerIds, "focusedPlayerIds"));
        if (focusedPlayerIds.isEmpty()) {
            throw new IllegalArgumentException("God attention record requires at least one focused player");
        }
    }

    GodAttentionRecord withPlayer(UUID playerId) {
        if (focusedPlayerIds.contains(playerId)) {
            return this;
        }
        java.util.LinkedHashSet<UUID> changed = new java.util.LinkedHashSet<>(focusedPlayerIds);
        changed.add(playerId);
        return new GodAttentionRecord(godId, entryQuestId, firstAssignedAt, changed);
    }
}
