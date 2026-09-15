package com.sande.mythictrpg.interaction.director;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Planning-only audience. Shared recipient expansion is deferred. */
public record InteractionAudience(UUID initiatingPlayerId, Set<UUID> recipientPlayerIds) {
    public InteractionAudience {
        Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
        recipientPlayerIds = Set.copyOf(Objects.requireNonNull(recipientPlayerIds, "recipientPlayerIds"));
        if (!recipientPlayerIds.contains(initiatingPlayerId)) {
            throw new IllegalArgumentException("Interaction audience must contain the initiating player");
        }
    }

    public static InteractionAudience initiatorOnly(UUID playerId) {
        return new InteractionAudience(playerId, Set.of(playerId));
    }
}
