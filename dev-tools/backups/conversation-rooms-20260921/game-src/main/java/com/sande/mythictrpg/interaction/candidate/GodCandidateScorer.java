package com.sande.mythictrpg.interaction.candidate;

import com.sande.mythictrpg.interaction.context.InteractionContext;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public interface GodCandidateScorer {
    ResourceLocation id();

    ScoreContribution score(InteractionContext context, CandidateSeed seed);

    record ScoreContribution(long score, List<ResourceLocation> reasons, List<ScoreDetail> details) {
        public ScoreContribution {
            reasons = List.copyOf(reasons);
            details = List.copyOf(details);
        }
    }

    record ScoreDetail(long score, ResourceLocation reason) {
    }
}
