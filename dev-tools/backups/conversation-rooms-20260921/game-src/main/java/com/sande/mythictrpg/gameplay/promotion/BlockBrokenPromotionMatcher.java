package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record BlockBrokenPromotionMatcher(
        Optional<ResourceLocation> blockId,
        Optional<ResourceLocation> dimensionId
) implements PromotionMatcher {
    public BlockBrokenPromotionMatcher {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(dimensionId, "dimensionId");
    }
}
