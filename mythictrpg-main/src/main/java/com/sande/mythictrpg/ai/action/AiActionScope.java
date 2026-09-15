package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Server-owned identity of the conversation currently allowed to submit actions. */
public record AiActionScope(UUID sessionId, ResourceLocation actingGodId) {
    public AiActionScope {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(actingGodId, "actingGodId");
    }
}
