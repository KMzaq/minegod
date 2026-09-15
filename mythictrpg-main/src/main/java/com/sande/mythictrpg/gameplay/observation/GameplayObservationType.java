package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record GameplayObservationType<P extends GameplayObservationPayload>(
        ResourceLocation id, Class<P> payloadType
) {
    public GameplayObservationType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payloadType, "payloadType");
    }

    public void validatePayload(GameplayObservationPayload payload) {
        if (!payloadType.isInstance(payload)) {
            throw new IllegalArgumentException("Payload " + payload.getClass().getName()
                    + " is not valid for observation type " + id);
        }
    }
}
