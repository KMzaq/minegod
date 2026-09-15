package com.sande.mythictrpg.ai.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AiConversationStartContext(UUID interactionId, UUID initiatingPlayerId,
        Set<UUID> audiencePlayerIds, List<ResourceLocation> godIds,
        List<AiConversationTurnSnapshot> initialTurns) {
    public AiConversationStartContext {
        Objects.requireNonNull(interactionId, "interactionId");
        Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
        audiencePlayerIds = Set.copyOf(Objects.requireNonNull(audiencePlayerIds, "audiencePlayerIds"));
        godIds = List.copyOf(Objects.requireNonNull(godIds, "godIds"));
        initialTurns = List.copyOf(Objects.requireNonNull(initialTurns, "initialTurns"));
    }
}
