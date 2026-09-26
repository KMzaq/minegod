package com.sande.mythictrpg.ai.action;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.Optional;

/** Server-thread-only context supplied to validators and executors. */
public record AiActionContext(MinecraftServer server, ServerPlayer targetPlayer, Optional<AiActionScope> roomScope) {
    /** Preserves existing validators and callers that use the legacy conversation path. */
    public AiActionContext(MinecraftServer server, ServerPlayer targetPlayer) {
        this(server, targetPlayer, Optional.empty());
    }

    public AiActionContext {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(targetPlayer, "targetPlayer");
        Objects.requireNonNull(roomScope, "roomScope");
        if (targetPlayer.server != server) {
            throw new IllegalArgumentException("Target player does not belong to the supplied server");
        }
    }
}
