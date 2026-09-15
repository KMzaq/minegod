package com.sande.mythictrpg.condition.api;

import com.sande.mythictrpg.data.player.PlayerMythQueryService;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ConditionContext(
        WorldStateView world,
        PlayerMythQueryService players,
        ServerStateView server,
        GodDefinitionView gods,
        Optional<UUID> targetPlayerId,
        Optional<ConditionEnvironment> environment,
        Optional<ConditionEventContext> event
) {
    public ConditionContext {
        Objects.requireNonNull(world);
        Objects.requireNonNull(players);
        Objects.requireNonNull(server);
        Objects.requireNonNull(gods);
        Objects.requireNonNull(targetPlayerId);
        Objects.requireNonNull(environment);
        Objects.requireNonNull(event);
    }
}
