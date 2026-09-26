package com.sande.mythictrpg.interaction.policy;

import com.sande.mythictrpg.interaction.api.InteractionMode;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface CooldownView {
    CooldownView NONE = (playerId, godId, mode) -> Optional.empty();

    Optional<ResourceLocation> blockReason(UUID playerId, ResourceLocation godId, InteractionMode mode);
}
