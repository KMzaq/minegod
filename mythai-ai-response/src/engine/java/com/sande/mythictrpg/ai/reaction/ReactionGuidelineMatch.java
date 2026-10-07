package com.sande.mythictrpg.ai.reaction;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A selected guideline plus explainable score and reasons for operator diagnostics. */
public record ReactionGuidelineMatch(ReactionGuideline guideline, int score, Set<SituationSignal> matchedSignals,
        List<String> reasons) {
    public ReactionGuidelineMatch {
        Objects.requireNonNull(guideline, "guideline");
        matchedSignals = Set.copyOf(Objects.requireNonNull(matchedSignals, "matchedSignals"));
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
