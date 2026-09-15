package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** A God profile's authoritative static declaration of how far that God knows one lore entry. */
public record LoreKnowledge(ResourceLocation loreId, int level) {
    public LoreKnowledge {
        Objects.requireNonNull(loreId, "loreId");
        if (level < 1) {
            throw new IllegalArgumentException("Lore knowledge level must be at least 1");
        }
    }
}
