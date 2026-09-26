package com.sande.mythictrpg.relation;

import java.util.Set;

public record GodRelationAppliedChange(GodRelationKey key, int previousScore, int currentScore,
        Set<GodRelationTag> previousTags, Set<GodRelationTag> currentTags, long revision) {
    public GodRelationAppliedChange {
        previousTags = Set.copyOf(previousTags);
        currentTags = Set.copyOf(currentTags);
    }
}

