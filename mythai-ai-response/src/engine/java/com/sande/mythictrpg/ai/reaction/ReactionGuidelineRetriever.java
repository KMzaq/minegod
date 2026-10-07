package com.sande.mythictrpg.ai.reaction;

import com.sande.mythictrpg.ai.tag.NpcTagProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Selects advisory guidance using only supplied SituationContext facts and raw NPC tags. */
public final class ReactionGuidelineRetriever {
    private final ReactionGuidelineRepository repository;

    public ReactionGuidelineRetriever(ReactionGuidelineRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public ReactionGuidelineSelection retrieve(SituationContext situation, NpcTagProfile npc) {
        Objects.requireNonNull(situation, "situation");
        Objects.requireNonNull(npc, "npc");
        Set<SituationSignal> signals = situation.signals();
        List<ReactionGuidelineMatch> matches = new ArrayList<>();
        for (ReactionGuideline guideline : repository.all().values()) {
            Set<SituationSignal> matchedSignals = new LinkedHashSet<>(guideline.triggers());
            matchedSignals.retainAll(signals);
            if (matchedSignals.isEmpty()) {
                continue;
            }
            int score = guideline.baseScore() + matchedSignals.size() * 100;
            List<String> reasons = new ArrayList<>();
            reasons.add("matched situation signal(s): " + matchedSignals);
            for (TagRelevanceBoost boost : guideline.tagBoosts()) {
                if (npc.rawTags().containsAll(boost.requiredTags())) {
                    score += boost.score();
                    reasons.add(boost.reason());
                }
            }
            matches.add(new ReactionGuidelineMatch(guideline, score, matchedSignals, reasons));
        }
        matches.sort(Comparator.comparingInt(ReactionGuidelineMatch::score).reversed()
                .thenComparing(match -> match.guideline().id().name()));
        return new ReactionGuidelineSelection(matches);
    }
}
