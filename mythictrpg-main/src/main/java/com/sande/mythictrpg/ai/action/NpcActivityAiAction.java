package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.godavatar.activity.NpcActivityRuntime;
import java.util.Map;
import java.util.Set;

/** A game-issued, turn-scoped choice; never an arbitrary item, target or movement executor. */
final class NpcActivityAiAction {
    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.NPC_ACTIVITY_REQUEST, AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                NpcActivityAiAction::validate, (context, proposal) -> {
                    var checked = validate(context, proposal);
                    if (!checked.accepted()) return AiActionExecution.rejected(checked.reason());
                    String token = proposal.parameters().get("choice_id");
                    return NpcActivityRuntime.INSTANCE.chooseScope(context.targetPlayer(), proposal.actingGodId(), proposal.sessionId(), token)
                            ? new AiActionExecution(true, "활동 선택을 접수했습니다. 작업 산출물·물품 소비·도착의 완료를 뜻하지 않습니다.",
                                    Map.of("status", "ACTIVITY_CHOICE_ACCEPTED", "work_completed", "false"))
                            : AiActionExecution.rejected("Activity choice expired, blocked, occupied or unreachable; no completed work");
                });
    }
    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!proposal.parameters().keySet().equals(Set.of("choice_id")) || context.roomScope().isEmpty()
                || !context.roomScope().get().sessionId().equals(proposal.sessionId())
                || !context.roomScope().get().actingGodId().equals(proposal.actingGodId()))
            return AiActionValidation.reject("Activity requires exact live room scope and choice_id only");
        return NpcActivityRuntime.INSTANCE.canChooseScope(context.targetPlayer(), proposal.actingGodId(), proposal.sessionId(), proposal.parameters().get("choice_id"))
                ? AiActionValidation.accept() : AiActionValidation.reject("No current game-issued activity choice for this room, player and God");
    }
    private NpcActivityAiAction() { }
}
