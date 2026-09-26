package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.action.AiActionGateway;
import com.sande.mythictrpg.ai.action.AiActionResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Converts untrusted model proposal fields into the bounded MythicTRPG action ingress. */
public final class MythicAiActionDispatcher {
    private MythicAiActionDispatcher() {
    }

    public static AiActionResult dispatch(ServerPlayer player, ResourceLocation actingGodId,
            AiDialogueModels.Proposal proposal) {
        return dispatch(player, actingGodId, proposal, "");
    }

    public static AiActionResult dispatch(ServerPlayer player, ResourceLocation actingGodId,
            AiDialogueModels.Proposal proposal, String currentPlayerText) {
        return AiActionGateway.submit(player, actingGodId, proposal.type(), proposal.title(),
                proposal.summary(), proposal.parameters(),
                AiActionCapabilityBridge.playerDeclaredItemReady(currentPlayerText));
    }
}
