package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record EntityKilledPromotionMatcher(
        Optional<ResourceLocation> entityTypeId,
        Optional<ResourceLocation> dimensionId
) implements PromotionMatcher {
    public EntityKilledPromotionMatcher {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        Objects.requireNonNull(dimensionId, "dimensionId");
    }
}
