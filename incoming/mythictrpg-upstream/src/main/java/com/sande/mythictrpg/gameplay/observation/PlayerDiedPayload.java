package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record PlayerDiedPayload(Optional<ResourceLocation> damageTypeId)
        implements GameplayObservationPayload {
    public PlayerDiedPayload {
        Objects.requireNonNull(damageTypeId, "damageTypeId");
    }

    @Override
    public Optional<ResourceLocation> subjectId() {
        return damageTypeId;
    }
}
