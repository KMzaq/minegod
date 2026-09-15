package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record AnimalBredPayload(ResourceLocation parentAEntityTypeId,
                                ResourceLocation parentBEntityTypeId,
                                ResourceLocation childEntityTypeId)
        implements GameplayObservationPayload {
    public AnimalBredPayload {
        Objects.requireNonNull(parentAEntityTypeId, "parentAEntityTypeId");
        Objects.requireNonNull(parentBEntityTypeId, "parentBEntityTypeId");
        Objects.requireNonNull(childEntityTypeId, "childEntityTypeId");
    }

    @Override
    public Optional<ResourceLocation> subjectId() {
        return Optional.of(childEntityTypeId);
    }
}
