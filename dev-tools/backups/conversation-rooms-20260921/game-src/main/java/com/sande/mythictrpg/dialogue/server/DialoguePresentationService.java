package com.sande.mythictrpg.dialogue.server;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.dialogue.api.DialogueSources;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.presentation.DialogueComponentSanitizer;
import com.sande.mythictrpg.network.ClientDialoguePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

public final class DialoguePresentationService {
    public static final DialoguePresentationService INSTANCE = new DialoguePresentationService();

    private DialoguePresentationService() {
    }

    public DialogueSendResult sendTo(ServerPlayer player, GodDialogueRequest request) {
        if (GodDefinitionManager.INSTANCE.find(request.godId()).isEmpty()) {
            return DialogueSendResult.failed(DialogueSendStatus.UNKNOWN_GOD);
        }

        UUID messageId = UUID.randomUUID();
        int length = DialogueComponentSanitizer.codePointCount(request.dialogueText().getString());
        try {
            ClientDialoguePayload payload = DialoguePresentationBuilder.INSTANCE.buildGod(
                    player.server, player.getUUID(), request, messageId);
            int encodedBytes = payload.encodedSize(player.server.registryAccess());
            PacketDistributor.sendToPlayer(player, payload);
            MythicTrpg.LOGGER.debug("Sent dialogue presentation {} from source {} (length={}, bytes={})",
                    messageId, DialogueSources.GOD, length, encodedBytes);
            return DialogueSendResult.sent(messageId);
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Rejected dialogue presentation {} from source {} (length={}): {}",
                    messageId, DialogueSources.GOD, length, exception.getMessage());
            return DialogueSendResult.failed(DialogueSendStatus.REJECTED);
        }
    }
}
