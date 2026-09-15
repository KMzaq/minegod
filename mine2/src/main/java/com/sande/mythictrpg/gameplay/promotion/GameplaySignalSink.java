package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.interaction.api.InteractionSignal;
import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface GameplaySignalSink {
    GameplaySignalSink UNAVAILABLE = (server, signal) -> GameplaySignalResult.UNAVAILABLE;

    GameplaySignalResult accept(
            MinecraftServer server,
            InteractionSignal<GameplayActionPayload> signal);
}
