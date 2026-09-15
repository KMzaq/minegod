package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record MatureCropHarvestEvidence(ResourceLocation cropBlockId)
        implements GameplayActionEvidence {
    public MatureCropHarvestEvidence {
        Objects.requireNonNull(cropBlockId, "cropBlockId");
    }
}
