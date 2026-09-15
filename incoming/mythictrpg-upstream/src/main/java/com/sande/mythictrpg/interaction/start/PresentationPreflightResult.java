package com.sande.mythictrpg.interaction.start;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record PresentationPreflightResult(Status status, Optional<ResourceLocation> reason) {
    public PresentationPreflightResult {
        reason = reason == null ? Optional.empty() : reason;
        if ((status == Status.READY) == reason.isPresent()) {
            throw new IllegalArgumentException("Preflight result invariant violated");
        }
    }

    public static PresentationPreflightResult ready() {
        return new PresentationPreflightResult(Status.READY, Optional.empty());
    }

    public static PresentationPreflightResult rejected(Status status, ResourceLocation reason) {
        if (status == Status.READY) {
            throw new IllegalArgumentException("READY has no rejection reason");
        }
        return new PresentationPreflightResult(status, Optional.of(reason));
    }

    public enum Status {
        READY,
        AUDIENCE_UNAVAILABLE,
        CONTENT_REJECTED
    }
}
