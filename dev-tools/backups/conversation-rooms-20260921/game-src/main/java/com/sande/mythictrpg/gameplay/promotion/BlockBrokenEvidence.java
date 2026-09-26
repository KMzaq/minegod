package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record BlockBrokenEvidence(ResourceLocation blockId, ResourceLocation dimensionId)
        implements GameplayActionEvidence {
    public BlockBrokenEvidence {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(dimensionId, "dimensionId");
    }
}
