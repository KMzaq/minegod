package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record PlayerDiedEvidence(Optional<ResourceLocation> damageTypeId)
        implements GameplayActionEvidence {
    public PlayerDiedEvidence {
        Objects.requireNonNull(damageTypeId, "damageTypeId");
        damageTypeId.ifPresent(id -> Objects.requireNonNull(id, "damageTypeId value"));
    }
}
