package com.sande.mythictrpg.relation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Set;

public record GodRelationTransitionChange(ResourceLocation sourceGodId, ResourceLocation targetGodId,
        int scoreDelta, Set<GodRelationTag> addTags, Set<GodRelationTag> removeTags) {
    public GodRelationTransitionChange {
        Objects.requireNonNull(sourceGodId, "sourceGodId");
        Objects.requireNonNull(targetGodId, "targetGodId");
        if (sourceGodId.equals(targetGodId) || scoreDelta < -1000 || scoreDelta > 1000) {
            throw new IllegalArgumentException("Invalid God relation transition change");
        }
        addTags = Set.copyOf(Objects.requireNonNull(addTags, "addTags"));
        removeTags = Set.copyOf(Objects.requireNonNull(removeTags, "removeTags"));
        if (scoreDelta == 0 && addTags.isEmpty() && removeTags.isEmpty()) {
            throw new IllegalArgumentException("God relation transition change must do something");
        }
        if (addTags.stream().anyMatch(removeTags::contains)) {
            throw new IllegalArgumentException("A relation tag cannot be added and removed by the same change");
        }
    }

    public GodRelationKey key() {
        return new GodRelationKey(sourceGodId, targetGodId);
    }
}

