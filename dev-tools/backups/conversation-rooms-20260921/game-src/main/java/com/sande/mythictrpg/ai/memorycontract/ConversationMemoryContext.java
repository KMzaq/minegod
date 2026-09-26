package com.sande.mythictrpg.ai.memorycontract;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Game-issued, immutable knowledge boundary. Strings are game IDs, never model-selected identities. */
public record ConversationMemoryContext(UUID worldId, UUID interactionId, UUID generation,
        UUID playerId, String godId, Set<UUID> audience, boolean readOnly) {
    public ConversationMemoryContext {
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(interactionId);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(godId);
        audience = Set.copyOf(audience);
        if (godId.isBlank() || audience.isEmpty() || !audience.contains(playerId)) {
            throw new IllegalArgumentException("Missing memory identity/audience");
        }
    }
}
