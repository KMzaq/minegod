package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.interaction.context.InteractionContext;

import java.util.Set;
import java.util.UUID;

public record InteractionStartSnapshot(InteractionContext context, Set<UUID> onlinePlayerIds,
        Set<UUID> activePlayerIds) {
    public InteractionStartSnapshot {
        onlinePlayerIds = Set.copyOf(onlinePlayerIds);
        activePlayerIds = Set.copyOf(activePlayerIds);
    }
}
