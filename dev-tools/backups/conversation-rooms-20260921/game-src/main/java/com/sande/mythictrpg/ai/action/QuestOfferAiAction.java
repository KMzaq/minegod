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
        var recipient = recipient(context, proposal);
        if (recipient == null) return AiActionValidation.reject("Recipient must be a current conversation participant");
        QuestAssignmentValidation validation = QuestRuntimeService.INSTANCE.validateAssignment(
                recipient, questId, proposal.actingGodId());
        return validation.allowed() ? AiActionValidation.accept()
                : AiActionValidation.reject(validation.reason());
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        ResourceLocation questId = questId(proposal);
        if (questId == null) {
            return AiActionExecution.rejected("Proposal parameter 'quest_id' is not a valid resource ID");
        }
        var recipient = recipient(context, proposal);
        if (recipient == null) return AiActionExecution.rejected("Recipient left the conversation");
        QuestOperationResult result = QuestRuntimeService.INSTANCE.assign(
                recipient, questId, proposal.actingGodId());
        if (!result.succeeded() && result.status() != QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS) {
            return AiActionExecution.rejected(result.reasonOptional().orElse(result.status().name()));
        }
        return AiActionExecution.executed(Map.of(
                "quest_id", questId.toString(),
                "quest_status", result.status().name()));
    }

    private static ResourceLocation questId(AiActionProposal proposal) {
        return ResourceLocation.tryParse(proposal.parameters().getOrDefault("quest_id", ""));
    }

    private static net.minecraft.server.level.ServerPlayer recipient(AiActionContext context, AiActionProposal proposal) {
        String requested = proposal.parameters().get("recipient_id");
        if (requested == null) return context.targetPlayer();
        try {
            java.util.UUID id = java.util.UUID.fromString(requested);
            var runtime = com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE;
            if (!runtime.conversationPlayers(context.targetPlayer()).contains(id)) return null;
            return context.server().getPlayerList().getPlayer(id);
        } catch (IllegalArgumentException exception) { return null; }
    }
}
