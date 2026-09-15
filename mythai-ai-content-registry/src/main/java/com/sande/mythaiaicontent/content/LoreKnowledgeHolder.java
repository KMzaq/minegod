package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Reverse-index result: a God that statically knows a lore entry and that God's highest level. */
public record LoreKnowledgeHolder(ResourceLocation godId, int knowledgeLevel) {
    public LoreKnowledgeHolder {
        Objects.requireNonNull(godId, "godId");
        if (knowledgeLevel < 1) {
            throw new IllegalArgumentException("Knowledge holder level must be at least 1");
        }
    }
}
