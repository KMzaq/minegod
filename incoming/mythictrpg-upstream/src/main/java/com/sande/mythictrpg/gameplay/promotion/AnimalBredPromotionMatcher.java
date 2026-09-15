package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record AnimalBredPromotionMatcher(
        Optional<ResourceLocation> childEntityTypeId,
        Optional<AnimalParentPair> parents
) implements PromotionMatcher {
    public AnimalBredPromotionMatcher {
        Objects.requireNonNull(childEntityTypeId, "childEntityTypeId");
        Objects.requireNonNull(parents, "parents");
    }
}
