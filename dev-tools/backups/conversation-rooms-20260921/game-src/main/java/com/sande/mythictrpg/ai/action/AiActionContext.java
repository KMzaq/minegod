package com.sande.mythictrpg.ai.action;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** Server-thread-only context supplied to validators and executors. */
public record AiActionContext(MinecraftServer server, ServerPlayer targetPlayer) {
    public AiActionContext {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(targetPlayer, "targetPlayer");
        if (targetPlayer.server != server) {
            throw new IllegalArgumentException("Target player does not belong to the supplied server");
        }
    }
}
