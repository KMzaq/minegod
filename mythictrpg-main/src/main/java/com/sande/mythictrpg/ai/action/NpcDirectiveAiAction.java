package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.godavatar.directive.NpcDirectiveRuntime;
import java.util.Set;

/** Actual physical commands use opaque game-issued choices, not model-authored targets or powers. */
final class NpcDirectiveAiAction {
    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.NPC_DIRECTIVE, AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                NpcDirectiveAiAction::validate, (context, proposal) -> {
                    var validation = validate(context, proposal);
                    return validation.accepted() ? NpcDirectiveRuntime.INSTANCE.execute(context, proposal)
                            : AiActionExecution.rejected(validation.reason());
                });
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!proposal.parameters().keySet().equals(Set.of("choice_id"))
                || !proposal.targetPlayerId().equals(context.targetPlayer().getUUID())
                || context.roomScope().filter(scope -> scope.sessionId().equals(proposal.sessionId())
                        && scope.actingGodId().equals(proposal.actingGodId())).isEmpty())
            return AiActionValidation.reject("Physical directive requires exact game room scope and choice_id only");
        return NpcDirectiveRuntime.INSTANCE.canChoose(context, proposal)
                ? AiActionValidation.accept()
                : AiActionValidation.reject("Physical choice expired, target unavailable, or physical order changed");
    }
    private NpcDirectiveAiAction() { }
}
