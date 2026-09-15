package com.sande.mythictrpg.interaction.director;

import com.sande.mythictrpg.interaction.candidate.CandidateReasons;
import com.sande.mythictrpg.interaction.candidate.CandidateSelectionResult;
import com.sande.mythictrpg.interaction.candidate.CandidateTraceMode;
import com.sande.mythictrpg.interaction.candidate.GodCandidate;
import com.sande.mythictrpg.interaction.candidate.GodCandidateSelector;
import com.sande.mythictrpg.interaction.context.InteractionContext;
import com.sande.mythictrpg.interaction.api.InteractionMode;

public final class GodInteractionDirector {
    public static final GodInteractionDirector INSTANCE = new GodInteractionDirector(
            GodCandidateSelector.INSTANCE);

    private final GodCandidateSelector selector;

    public GodInteractionDirector(GodCandidateSelector selector) {
        this.selector = selector;
    }

    public InteractionDecision plan(InteractionContext context, CandidateTraceMode traceMode) {
        return plan(context, selector.select(context, traceMode));
    }

    public InteractionDecision plan(InteractionContext context, CandidateSelectionResult selection) {
        boolean staleGods = selection.godDefinitionGeneration() != context.godSnapshot().generation();
        boolean staleRules = context.signal().mode() == InteractionMode.SPONTANEOUS
                && selection.interactionRuleGeneration() != context.ruleSnapshot().generation();
        if (staleGods || staleRules) {
            return InteractionDecision.noStart(CandidateReasons.STALE_SELECTION);
        }
        var runtimeBlock = context.runtime().planningBlockReason(
                context.initiatingPlayerId(), context.signal().mode());
        if (runtimeBlock.isPresent()) {
            return InteractionDecision.noStart(runtimeBlock.orElseThrow());
        }
        if (selection.status() != CandidateSelectionResult.Status.CANDIDATES
                || selection.candidates().isEmpty()) {
            return InteractionDecision.noStart(selection.reason()
                    .orElse(CandidateReasons.DIRECTOR_NO_CANDIDATE));
        }

        GodCandidate primary = selection.candidates().getFirst();
        InteractionPlan plan = new InteractionPlan(
                context.initiatingPlayerId(),
                InteractionAudience.initiatorOnly(context.initiatingPlayerId()),
                context.signal().mode(),
                context.signal().type().id(),
                InteractionParticipants.primaryOnly(primary.godId()),
                context.signal().mode() == InteractionMode.SPONTANEOUS
                        ? PlanRevisionStamp.spontaneous(selection.godDefinitionGeneration(),
                                selection.interactionRuleGeneration())
                        : PlanRevisionStamp.explicit(selection.godDefinitionGeneration()),
                primary.reasonIds());
        return InteractionDecision.start(plan);
    }
}
