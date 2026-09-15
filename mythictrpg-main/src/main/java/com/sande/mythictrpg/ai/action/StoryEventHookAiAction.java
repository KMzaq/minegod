package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.story.presentation.StoryAiHookTokenService;

import java.util.Map;

/** Executes only a server-issued opaque Hook token, never a model-supplied Story ID. */
final class StoryEventHookAiAction {
    private StoryEventHookAiAction() {}

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.STORY_EVENT_HOOK,
                AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED,
                StoryEventHookAiAction::validate, StoryEventHookAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!AiActionParameters.hasOnly(proposal, "token")) {
            return AiActionValidation.reject("Story event hook requires only an opaque 'token'");
        }
        var result = StoryAiHookTokenService.INSTANCE.validate(context.targetPlayer(), proposal);
        return result.accepted() ? AiActionValidation.accept() : AiActionValidation.reject(result.reason());
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        var result = StoryAiHookTokenService.INSTANCE.consume(context.targetPlayer(), proposal);
        return result.accepted()
                ? AiActionExecution.executed(Map.of("started_instances", Integer.toString(result.instanceIds().size())))
                : AiActionExecution.rejected(result.reason());
    }
}
