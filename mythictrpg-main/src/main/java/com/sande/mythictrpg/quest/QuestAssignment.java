package com.sande.mythictrpg.quest;

import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record QuestAssignment(ResourceLocation questId, UUID playerId,
        ResourceLocation giverGodId, Instant assignedAt) {
    public QuestAssignment {
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(giverGodId, "giverGodId");
        Objects.requireNonNull(assignedAt, "assignedAt");
    }
}
