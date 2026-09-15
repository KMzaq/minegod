package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record AnimalBredEvidence(AnimalParentPair parents, ResourceLocation childEntityTypeId)
        implements GameplayActionEvidence {
    public AnimalBredEvidence {
        Objects.requireNonNull(parents, "parents");
        Objects.requireNonNull(childEntityTypeId, "childEntityTypeId");
    }
}
