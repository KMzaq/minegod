package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.interaction.content.ValidatedInteractionContent;
import com.sande.mythictrpg.interaction.director.InteractionAudience;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public interface InteractionDialogueOutput {
    PresentationPreflightResult preflight(MinecraftServer server, InteractionAudience audience,
            ValidatedInteractionContent content);

    DeliverySummary deliver(MinecraftServer server, UUID interactionId, InteractionAudience audience,
            ValidatedInteractionContent content);
}
