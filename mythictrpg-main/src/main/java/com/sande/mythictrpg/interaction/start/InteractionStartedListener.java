package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.interaction.content.ValidatedInteractionContent;
import com.sande.mythictrpg.interaction.director.InteractionPlan;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

@FunctionalInterface
public interface InteractionStartedListener {
    InteractionStartedListener NONE = (server, interactionId, plan, content) -> {
    };

    void onStarted(MinecraftServer server, UUID interactionId, InteractionPlan plan,
            ValidatedInteractionContent content);
}
