package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record EntityKilledPayload(ResourceLocation killedEntityTypeId, ResourceLocation dimensionId)
        implements GameplayObservationPayload {
    public EntityKilledPayload {
        Objects.requireNonNull(killedEntityTypeId, "killedEntityTypeId");
        Objects.requireNonNull(dimensionId, "dimensionId");
    }

    @Override
    public Optional<ResourceLocation> subjectId() {
        return Optional.of(killedEntityTypeId);
    }
}
