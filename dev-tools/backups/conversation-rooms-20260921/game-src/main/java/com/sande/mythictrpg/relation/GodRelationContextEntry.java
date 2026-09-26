package com.sande.mythictrpg.relation;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Bounded plain data safe for the optional AI module. */
public record GodRelationContextEntry(ResourceLocation sourceGodId, ResourceLocation targetGodId,
        int score, List<String> stateTags, long revision, ResourceLocation lastCauseId) {
    public GodRelationContextEntry {
        stateTags = List.copyOf(stateTags);
    }
}

