package com.sande.mythictrpg.interaction.policy;

import com.sande.mythictrpg.interaction.api.InteractionMode;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

public interface InteractionRuntimeView {
    InteractionRuntimeView AVAILABLE = new InteractionRuntimeView() {
    };

    default Optional<ResourceLocation> candidateBlockReason(
            UUID playerId, ResourceLocation godId, InteractionMode mode) {
        return Optional.empty();
    }

    default Optional<ResourceLocation> planningBlockReason(UUID playerId, InteractionMode mode) {
        return Optional.empty();
    }
}
