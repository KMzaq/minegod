package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.quest.QuestAssignmentValidation;
import com.sande.mythictrpg.quest.QuestOperationResult;
import com.sande.mythictrpg.quest.QuestRuntimeService;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** First production action: offer and authoritatively assign one registered quest. */
final class QuestOfferAiAction {
    private QuestOfferAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.QUEST_OFFER,
                AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                QuestOfferAiAction::validate, QuestOfferAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        ResourceLocation questId = questId(proposal);
        if (questId == null) {
            return AiActionValidation.reject("Proposal parameter 'quest_id' is not a valid resource ID");
        }
        QuestAssignmentValidation validation = QuestRuntimeService.INSTANCE.validateAssignment(
                context.targetPlayer(), questId, proposal.actingGodId());
        return validation.allowed() ? AiActionValidation.accept()
                : AiActionValidation.reject(validation.reason());
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        ResourceLocation questId = questId(proposal);
        if (questId == null) {
            return AiActionExecution.rejected("Proposal parameter 'quest_id' is not a valid resource ID");
        }
        QuestOperationResult result = QuestRuntimeService.INSTANCE.assign(
                context.targetPlayer(), questId, proposal.actingGodId());
        if (!result.succeeded()) {
            return AiActionExecution.rejected(result.reasonOptional().orElse(result.status().name()));
        }
        return AiActionExecution.executed(Map.of(
                "quest_id", questId.toString(),
                "quest_status", result.status().name()));
    }

    private static ResourceLocation questId(AiActionProposal proposal) {
        return ResourceLocation.tryParse(proposal.parameters().getOrDefault("quest_id", ""));
    }
}
