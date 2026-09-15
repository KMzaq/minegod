package com.sande.mythictrpg.ai.reaction;

import java.util.List;
import java.util.Objects;

/** Kept separate from example retrieval; a future prompt builder may combine the two outputs. */
public record ReactionGuidelineSelection(List<ReactionGuidelineMatch> matches) {
    public ReactionGuidelineSelection {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
    }

    public List<ReactionGuidelineId> ids() {
        return matches.stream().map(match -> match.guideline().id()).toList();
    }
}
