package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.quest.QuestReorganizationService;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.Set;

/** Opens game-owned choices. The request itself never changes the roster. */
final class QuestRosterAiAction {
    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.QUEST_ROSTER_REQUEST, AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                (context, proposal) -> {
                    var id = quest(proposal);
                    if (id == null || context.roomScope().isEmpty()) return AiActionValidation.reject("A live NPC room and exact quest_id are required");
                    String problem = QuestReorganizationService.INSTANCE.validate(context.targetPlayer(), id, context.roomScope().orElseThrow());
                    return problem.isEmpty() ? AiActionValidation.accept() : AiActionValidation.reject(problem);
                }, (context, proposal) -> {
                    var id = quest(proposal);
                    if (id == null || context.roomScope().isEmpty()) return AiActionExecution.rejected("Quest or room is unavailable");
                    String problem = QuestReorganizationService.INSTANCE.open(context.targetPlayer(), id, context.roomScope().orElseThrow());
                    return problem.isEmpty() ? AiActionExecution.executed(Map.of("quest_id", id.toString(), "roster_status", "MENU_OPENED_AWAITING_PLAYER_SELECTION"))
                            : AiActionExecution.rejected(problem);
                });
    }
    private static ResourceLocation quest(AiActionProposal proposal) {
        if (!proposal.parameters().keySet().equals(Set.of("quest_id"))) return null;
        return ResourceLocation.tryParse(proposal.parameters().get("quest_id"));
    }
}
