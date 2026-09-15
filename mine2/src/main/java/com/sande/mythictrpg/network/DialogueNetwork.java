package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class DialogueNetwork {
    public static final String PROTOCOL_VERSION = "1";

    private DialogueNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(ClientDialoguePayload.TYPE, ClientDialoguePayload.STREAM_CODEC,
                DialogueNetwork::handleClientDialogue);
    }

    private static void handleClientDialogue(ClientDialoguePayload payload, IPayloadContext context) {
        try {
            if (!ClientDialogueBridge.accept(payload)) {
                MythicTrpg.LOGGER.warn("Dialogue payload {} arrived before the client HUD receiver was installed",
                        payload.messageId());
            }
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Rejected dialogue payload {} from source {}: {}",
                    payload.messageId(), payload.source(), exception.getMessage());
            context.disconnect(Component.translatable("disconnect.mythictrpg.invalid_dialogue_payload"));
        }
    }
}
