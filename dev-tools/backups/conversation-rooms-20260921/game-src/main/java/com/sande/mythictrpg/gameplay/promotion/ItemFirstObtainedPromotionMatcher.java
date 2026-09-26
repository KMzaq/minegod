package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record ItemFirstObtainedPromotionMatcher(Optional<ResourceLocation> itemId)
        implements PromotionMatcher {
    public ItemFirstObtainedPromotionMatcher {
        Objects.requireNonNull(itemId, "itemId");
    }
}
