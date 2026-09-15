package com.sande.mythictrpg.gameplay.promotion;

public sealed interface GameplayActionEvidence permits AnimalBredEvidence,
        AnimalFeedingEvidence, BlockBrokenEvidence, EntityKilledEvidence,
        ItemFirstObtainedEvidence, MatureCropHarvestEvidence, PlayerDiedEvidence,
        VanillaStatMilestoneEvidence {
}
