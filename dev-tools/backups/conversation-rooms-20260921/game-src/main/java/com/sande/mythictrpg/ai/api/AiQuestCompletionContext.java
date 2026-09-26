package com.sande.mythictrpg.ai.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** A server-verified quest completion that may be narrated by the optional AI engine. */
public record AiQuestCompletionContext(UUID playerId, ResourceLocation npcId, ResourceLocation questId) {
    public AiQuestCompletionContext {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(questId, "questId");
    }
}
