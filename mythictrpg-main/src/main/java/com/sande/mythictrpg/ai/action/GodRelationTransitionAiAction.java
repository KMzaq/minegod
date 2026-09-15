package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.relation.GodRelationService;
import com.sande.mythictrpg.relation.GodRelationTransition;
import com.sande.mythictrpg.relation.GodRelationTransitionManager;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** AI may select one authored transition, but execution always requires player confirmation and server revalidation. */
final class GodRelationTransitionAiAction {
    private GodRelationTransitionAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.GOD_RELATION_TRANSITION,
                AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED,
                GodRelationTransitionAiAction::validate, GodRelationTransitionAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!AiActionParameters.hasOnly(proposal, "transition_id")) {
            return AiActionValidation.reject("God relation transition requires only 'transition_id'");
        }
        GodRelationTransition transition = transition(proposal);
        if (transition == null || !transition.aiEnabled()) {
            return AiActionValidation.reject("God relation transition is not registered for AI use");
        }
        if (!transition.actingGodId().equals(proposal.actingGodId())) {
            return AiActionValidation.reject("The acting God does not own this relation transition");
        }
        var preview = GodRelationService.INSTANCE.preview(context.server(), transition);
        return preview.succeeded() ? AiActionValidation.accept() : AiActionValidation.reject(preview.reason());
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        GodRelationTransition transition = transition(proposal);
        if (transition == null || !transition.aiEnabled()
                || !transition.actingGodId().equals(proposal.actingGodId())) {
            return AiActionExecution.rejected("God relation transition is no longer authorized");
        }
        var result = GodRelationService.INSTANCE.apply(context.server(), transition);
        return result.succeeded() ? AiActionExecution.executed(Map.of(
                "transition_id", transition.id().toString(),
                "changed_directions", Integer.toString(result.changes().size())))
                : AiActionExecution.rejected(result.reason());
    }

    private static GodRelationTransition transition(AiActionProposal proposal) {
        ResourceLocation id = ResourceLocation.tryParse(proposal.parameters().getOrDefault("transition_id", ""));
        return id == null ? null : GodRelationTransitionManager.INSTANCE.find(id).orElse(null);
    }
}

