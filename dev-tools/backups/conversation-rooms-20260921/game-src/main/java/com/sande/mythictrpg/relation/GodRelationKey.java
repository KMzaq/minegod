package com.sande.mythictrpg.relation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record GodRelationKey(ResourceLocation sourceGodId, ResourceLocation targetGodId)
        implements Comparable<GodRelationKey> {
    public GodRelationKey {
        Objects.requireNonNull(sourceGodId, "sourceGodId");
        Objects.requireNonNull(targetGodId, "targetGodId");
        if (sourceGodId.equals(targetGodId)) {
            throw new IllegalArgumentException("A God relation requires two different Gods");
        }
    }

    public GodRelationKey reversed() {
        return new GodRelationKey(targetGodId, sourceGodId);
    }

    @Override
    public int compareTo(GodRelationKey other) {
        int source = sourceGodId.compareTo(other.sourceGodId);
        return source != 0 ? source : targetGodId.compareTo(other.targetGodId);
    }
}

