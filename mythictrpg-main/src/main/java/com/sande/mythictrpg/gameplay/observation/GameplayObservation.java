package com.sande.mythictrpg.gameplay.observation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

public record GameplayObservation<P extends GameplayObservationPayload>(
        GameplayObservationType<P> type,
        UUID initiatingPlayerId,
        long gameTime,
        P payload
) {
    public GameplayObservation {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
        Objects.requireNonNull(payload, "payload");
        type.validatePayload(payload);
    }

    public Optional<ResourceLocation> subjectId() {
        return payload.subjectId();
    }
}
