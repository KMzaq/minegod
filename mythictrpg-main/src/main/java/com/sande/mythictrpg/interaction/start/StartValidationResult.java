package com.sande.mythictrpg.interaction.start;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record StartValidationResult(Status status, Optional<ResourceLocation> reason) {
    public StartValidationResult {
        reason = reason == null ? Optional.empty() : reason;
        if ((status == Status.ACCEPTED) == reason.isPresent()) {
            throw new IllegalArgumentException("Start validation result invariant violated");
        }
    }

    public static StartValidationResult accepted() {
        return new StartValidationResult(Status.ACCEPTED, Optional.empty());
    }

    public static StartValidationResult rejected(Status status, ResourceLocation reason) {
        return new StartValidationResult(status, Optional.of(reason));
    }

    public enum Status {
        ACCEPTED,
        STALE_PLAN,
        AUDIENCE_UNAVAILABLE,
        LEGALITY_FAILED,
        RUNTIME_BUSY
    }
}
