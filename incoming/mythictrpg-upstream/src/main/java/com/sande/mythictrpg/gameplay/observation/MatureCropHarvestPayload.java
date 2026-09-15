package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record MatureCropHarvestPayload(ResourceLocation cropBlockId) implements GameplayObservationPayload {
    public MatureCropHarvestPayload {
        Objects.requireNonNull(cropBlockId, "cropBlockId");
    }

    @Override
    public Optional<ResourceLocation> subjectId() {
        return Optional.of(cropBlockId);
    }
}
