package com.sande.mythictrpg.relation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Set;

public record GodRelationHistoryEntry(long revision, long gameTime, ResourceLocation causeId,
        int previousScore, int currentScore, Set<GodRelationTag> addedTags, Set<GodRelationTag> removedTags) {
    public GodRelationHistoryEntry {
        if (revision < 1 || gameTime < 0) {
            throw new IllegalArgumentException("Relation history revision and game time must be non-negative");
        }
        Objects.requireNonNull(causeId, "causeId");
        addedTags = Set.copyOf(Objects.requireNonNull(addedTags, "addedTags"));
        removedTags = Set.copyOf(Objects.requireNonNull(removedTags, "removedTags"));
    }
}

