package com.sande.mythictrpg.quest;

import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-wide completion audit record used for idempotency and history. */
public record QuestCompletionRecord(ResourceLocation questId, UUID completedBy,
        Optional<ResourceLocation> completionNpcId, Instant completedAt,
        Set<UUID> assignedPlayersAtCompletion) {
    public QuestCompletionRecord {
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(completedBy, "completedBy");
        completionNpcId = completionNpcId == null ? Optional.empty() : completionNpcId;
        Objects.requireNonNull(completedAt, "completedAt");
        assignedPlayersAtCompletion = assignedPlayersAtCompletion == null
                ? Set.of() : Set.copyOf(assignedPlayersAtCompletion);
    }
}
