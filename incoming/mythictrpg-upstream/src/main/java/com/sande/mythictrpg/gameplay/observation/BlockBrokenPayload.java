package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record BlockBrokenPayload(ResourceLocation blockId, ResourceLocation dimensionId, BlockPos position)
        implements GameplayObservationPayload {
    public BlockBrokenPayload {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(position, "position");
        position = position.immutable();
    }

    @Override
    public Optional<ResourceLocation> subjectId() {
        return Optional.of(blockId);
    }
}
