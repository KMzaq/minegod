package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record EntityKilledEvidence(ResourceLocation entityTypeId, ResourceLocation dimensionId)
        implements GameplayActionEvidence {
    public EntityKilledEvidence {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        Objects.requireNonNull(dimensionId, "dimensionId");
    }
}
