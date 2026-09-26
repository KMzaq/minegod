package com.sande.mythictrpg.ai.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AiConversationControlContext(UUID playerId, boolean enabled,
        Optional<ResourceLocation> currentGodId) {
    public AiConversationControlContext {
        Objects.requireNonNull(playerId, "playerId");
        currentGodId = Objects.requireNonNull(currentGodId, "currentGodId");
    }
}
