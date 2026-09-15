package com.sande.mythictrpg.interaction.candidate;

import com.sande.mythictrpg.interaction.rule.InteractionRuleSeedIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record CandidateSeed(ResourceLocation godId, long baseScore,
        List<ResourceLocation> reasons, List<InteractionRuleSeedIndex.ScorePart> scoreParts) {
    public CandidateSeed {
        Objects.requireNonNull(godId, "godId");
        reasons = List.copyOf(reasons);
        scoreParts = List.copyOf(scoreParts);
    }

    public static CandidateSeed explicit(ResourceLocation godId) {
        return new CandidateSeed(godId, 0, List.of(CandidateReasons.EXPLICIT_TARGET), List.of());
    }
}
