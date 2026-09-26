package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.ai.action.AiActionGateway;
import com.sande.mythictrpg.ai.action.AiActionResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Stable public boundary used by the separate AI response mod. */
public final class QuestProposalGateway {
    private QuestProposalGateway() {
    }

    public static QuestOperationResult acceptOffer(ServerPlayer player,
            ResourceLocation giverGodId, ResourceLocation questId) {
        QuestAssignmentValidation validation = QuestRuntimeService.INSTANCE.validateAssignment(
                player, questId, giverGodId);
        if (!validation.allowed()) {
            return new QuestOperationResult(validation.rejectionStatus(), questId, validation.reason());
        }
        AiActionResult action = AiActionGateway.submit(player, giverGodId, "quest_offer",
                "Quest offer", questId.toString(), Map.of("quest_id", questId.toString()));
        if (action.succeeded()) {
            boolean consent = FtbQuestBindingManager.INSTANCE.find(questId).flatMap(FtbQuestBinding::participation).isPresent();
            return new QuestOperationResult(consent ? QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS
                    : QuestOperationResult.Status.ASSIGNED, questId, "");
        }
        return new QuestOperationResult(QuestOperationResult.Status.INTERNAL_ERROR, questId,
                action.reason().isBlank() ? action.status().name() : action.reason());
    }
}
