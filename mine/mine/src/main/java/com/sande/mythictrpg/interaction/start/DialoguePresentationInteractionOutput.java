package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationBuilder;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.dialogue.server.DialogueSendStatus;
import com.sande.mythictrpg.interaction.content.PreparedDialogueTurn;
import com.sande.mythictrpg.interaction.content.ValidatedInteractionContent;
import com.sande.mythictrpg.interaction.director.InteractionAudience;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public final class DialoguePresentationInteractionOutput implements InteractionDialogueOutput {
    public static final DialoguePresentationInteractionOutput INSTANCE =
            new DialoguePresentationInteractionOutput();
    private static final ResourceLocation AUDIENCE_OFFLINE = id("presentation_audience_offline");
    private static final ResourceLocation CONTENT_REJECTED = id("presentation_content_rejected");
    private static final UUID PREFLIGHT_MESSAGE_ID = new UUID(0L, 0L);

    private DialoguePresentationInteractionOutput() {
    }

    @Override
    public PresentationPreflightResult preflight(MinecraftServer server, InteractionAudience audience,
            ValidatedInteractionContent content) {
        try {
            for (UUID playerId : audience.recipientPlayerIds()) {
                if (server.getPlayerList().getPlayer(playerId) == null) {
                    return PresentationPreflightResult.rejected(
                            PresentationPreflightResult.Status.AUDIENCE_UNAVAILABLE, AUDIENCE_OFFLINE);
                }
                for (PreparedDialogueTurn turn : content.turns()) {
                    var request = request(turn);
                    var payload = DialoguePresentationBuilder.INSTANCE.buildGod(
                            server, playerId, request, PREFLIGHT_MESSAGE_ID);
                    payload.encodedSize(server.registryAccess());
                }
            }
            return PresentationPreflightResult.ready();
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Interaction presentation preflight rejected: {}", exception.getMessage());
            return PresentationPreflightResult.rejected(
                    PresentationPreflightResult.Status.CONTENT_REJECTED, CONTENT_REJECTED);
        }
    }

    @Override
    public DeliverySummary deliver(MinecraftServer server, UUID interactionId,
            InteractionAudience audience, ValidatedInteractionContent content) {
        int attempted = audience.recipientPlayerIds().size() * content.turns().size();
        int sent = 0;
        for (UUID playerId : audience.recipientPlayerIds()) {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            for (PreparedDialogueTurn turn : content.turns()) {
                if (DialoguePresentationService.INSTANCE.sendTo(player, request(turn)).status()
                        == DialogueSendStatus.SENT) {
                    sent++;
                }
            }
        }
        return DeliverySummary.of(attempted, sent);
    }

    private static GodDialogueRequest request(PreparedDialogueTurn turn) {
        return new GodDialogueRequest(turn.speakerGodId(), turn.text(),
                turn.priority(), turn.displayOptions());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
