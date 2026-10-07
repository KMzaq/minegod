package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.godavatar.GodHomeVisitService;
import java.util.Map;
import java.util.Optional;

/** Requests a separate bounded decision; EXECUTED here means consideration queued, never arrival. */
final class NpcVisitAiAction {
    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.NPC_VISIT_REQUEST, AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                NpcVisitAiAction::validate, (context, proposal) -> {
                    var validation = validate(context, proposal);
                    if (!validation.accepted()) return AiActionExecution.rejected(validation.reason());
                    return GodHomeVisitService.INSTANCE.request(context.targetPlayer(), proposal.actingGodId(), Optional.of(proposal.sessionId()))
                            ? new AiActionExecution(true, "방문 판단 요청만 접수됨. 아직 이동·도착하지 않음 (VISIT_CONSIDERATION_QUEUED).",
                                    Map.of("status", "VISIT_CONSIDERATION_QUEUED", "arrived", "false", "movement_started", "false"))
                            : AiActionExecution.rejected("No eligible registered building or visit planner unavailable");
                });
    }
    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!proposal.parameters().isEmpty() || context.roomScope().isEmpty()
                || !context.roomScope().get().sessionId().equals(proposal.sessionId())
                || !context.roomScope().get().actingGodId().equals(proposal.actingGodId()))
            return AiActionValidation.reject("Visit requires an exact live room scope and empty parameters");
        return GodHomeVisitService.INSTANCE.canRequest(context.targetPlayer(), proposal.actingGodId(), true)
                ? AiActionValidation.accept() : AiActionValidation.reject("Visit policy, progress, avatar, cooldown or availability rejects this request");
    }
    private NpcVisitAiAction() { }
}
