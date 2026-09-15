package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.gameplay.observation.FeedingOutcome;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record AnimalFedPromotionMatcher(
        Optional<ResourceLocation> entityTypeId,
        Optional<ResourceLocation> foodItemId,
        Optional<FeedingOutcome> outcome
) implements PromotionMatcher {
    public AnimalFedPromotionMatcher {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        Objects.requireNonNull(foodItemId, "foodItemId");
        Objects.requireNonNull(outcome, "outcome");
    }
}
