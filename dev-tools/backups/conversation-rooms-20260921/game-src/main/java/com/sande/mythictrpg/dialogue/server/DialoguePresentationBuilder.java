package com.sande.mythictrpg.dialogue.server;

import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.god.GodIdentityService;
import com.sande.mythictrpg.dialogue.api.DialogueSources;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.presentation.DialogueComponentSanitizer;
import com.sande.mythictrpg.dialogue.presentation.DialogueTiming;
import com.sande.mythictrpg.dialogue.presentation.DialogueTimingPolicy;
import com.sande.mythictrpg.dialogue.presentation.DialogueValidationException;
import com.sande.mythictrpg.network.ClientDialoguePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public final class DialoguePresentationBuilder {
    public static final DialoguePresentationBuilder INSTANCE = new DialoguePresentationBuilder();

    private DialoguePresentationBuilder() {
    }

    public ClientDialoguePayload buildGod(MinecraftServer server, UUID targetPlayerId,
            GodDialogueRequest request, UUID messageId) {
        if (GodDefinitionManager.INSTANCE.find(request.godId()).isEmpty()) {
            throw new DialogueValidationException("Unknown God definition");
        }
        Component speaker = DialogueComponentSanitizer.sanitize(
                GodIdentityService.INSTANCE.getDisplayName(server, targetPlayerId, request.godId()),
                ClientDialoguePayload.MAX_SPEAKER_CODE_POINTS, "speakerDisplayName");
        Component dialogue = DialogueComponentSanitizer.sanitize(
                request.dialogueText(), ClientDialoguePayload.MAX_DIALOGUE_CODE_POINTS, "dialogueText");
        DialogueTiming timing = DialogueTimingPolicy.calculate(dialogue, request.displayOptions());
        return new ClientDialoguePayload(messageId, speaker, dialogue, DialogueSources.GOD,
                request.priority(), timing.fadeInTicks(), timing.holdTicks(), timing.fadeOutTicks());
    }
}
