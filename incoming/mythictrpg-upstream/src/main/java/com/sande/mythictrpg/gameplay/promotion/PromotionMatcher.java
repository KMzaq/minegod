package com.sande.mythictrpg.gameplay.promotion;

public sealed interface PromotionMatcher permits AnimalBredPromotionMatcher,
        AnimalFedPromotionMatcher, BlockBrokenPromotionMatcher,
        EntityKilledPromotionMatcher, ItemFirstObtainedPromotionMatcher,
        MatureCropHarvestPromotionMatcher, PlayerDiedPromotionMatcher,
        VanillaStatThresholdPromotionMatcher {
}
