package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.server.MinecraftServer;

public interface GameplayObservationSink {
    GameplayObservationSink NO_OP = (server, observation) -> {
    };

    void accept(MinecraftServer server, GameplayObservation<?> observation);
}
