package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record MatureCropHarvestPromotionMatcher(Optional<ResourceLocation> cropBlockId)
        implements PromotionMatcher {
    public MatureCropHarvestPromotionMatcher {
        Objects.requireNonNull(cropBlockId, "cropBlockId");
    }
}
