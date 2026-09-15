package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ItemFirstObtainedEvidence(ResourceLocation itemId) implements GameplayActionEvidence {
    public ItemFirstObtainedEvidence {
        Objects.requireNonNull(itemId, "itemId");
    }
}
