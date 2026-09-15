package com.sande.mythictrpg.interaction.api;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record InteractionSignal<P extends InteractionPayload>(
        InteractionSignalType<P> type,
        UUID initiatingPlayerId,
        Set<UUID> involvedPlayerIds,
        P payload
) {
    public InteractionSignal {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
        Objects.requireNonNull(involvedPlayerIds, "involvedPlayerIds");
        Objects.requireNonNull(payload, "payload");
        type.validatePayload(payload);
        LinkedHashSet<UUID> involved = new LinkedHashSet<>(involvedPlayerIds);
        involved.remove(initiatingPlayerId);
        involvedPlayerIds = Set.copyOf(involved);
    }

    public InteractionMode mode() {
        return type.mode();
    }
}
