package com.sande.mythictrpg.interaction.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record InteractionSignalType<P extends InteractionPayload>(
        ResourceLocation id, InteractionMode mode, Class<P> payloadType) {
    public InteractionSignalType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(payloadType, "payloadType");
    }

    public void validatePayload(InteractionPayload payload) {
        if (!payloadType.isInstance(payload)) {
            throw new IllegalArgumentException("Signal " + id + " requires payload "
                    + payloadType.getSimpleName() + ", got " + payload.getClass().getSimpleName());
        }
    }
}
