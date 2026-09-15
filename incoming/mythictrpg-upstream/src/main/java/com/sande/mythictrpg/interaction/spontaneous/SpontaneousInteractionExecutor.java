package com.sande.mythictrpg.interaction.spontaneous;

import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.content.InteractionContentPreparer;
import com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator;
import com.sande.mythictrpg.interaction.start.InteractionStartResult;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface SpontaneousInteractionExecutor {
    SpontaneousInteractionExecutor ORCHESTRATOR = InteractionOrchestrator.INSTANCE::execute;

    /** Implementations must use the supplied guarded preparer before starting an interaction. */
    CompletionStage<InteractionStartResult> execute(MinecraftServer server,
            InteractionSignal<?> signal, InteractionContentPreparer preparer);
}
