package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record ItemFirstObtainedPayload(ResourceLocation itemId)
        implements GameplayObservationPayload {
    public ItemFirstObtainedPayload {
        Objects.requireNonNull(itemId, "itemId");
    }

    @Override
    public Optional<ResourceLocation> subjectId() {
        return Optional.of(itemId);
    }
}
