package com.sande.mythictrpg.interaction.preview;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record InteractionPreviewResult(InteractionPreviewStatus status,
        ResourceLocation signalTypeId, Optional<ResourceLocation> primaryGodId,
        Optional<ResourceLocation> reasonId) {
    public InteractionPreviewResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(signalTypeId, "signalTypeId");
        Objects.requireNonNull(primaryGodId, "primaryGodId");
        Objects.requireNonNull(reasonId, "reasonId");
        boolean providerEvaluated = switch (status) {
            case PROVIDER_UNAVAILABLE, PROVIDER_AVAILABLE, PROVIDER_FAILED -> true;
            default -> false;
        };
        if (providerEvaluated != primaryGodId.isPresent()) {
            throw new IllegalArgumentException(
                    "Provider evaluation requires exactly one planned primary God");
        }
        if ((status == InteractionPreviewStatus.PROVIDER_AVAILABLE) == reasonId.isPresent()) {
            throw new IllegalArgumentException(
                    "Only an available provider may omit the preview reason");
        }
    }

    static InteractionPreviewResult rejected(InteractionPreviewStatus status,
            ResourceLocation signalTypeId, ResourceLocation reasonId) {
        return new InteractionPreviewResult(status, signalTypeId, Optional.empty(),
                Optional.of(Objects.requireNonNull(reasonId, "reasonId")));
    }

    static InteractionPreviewResult provider(InteractionPreviewStatus status,
            ResourceLocation signalTypeId, ResourceLocation primaryGodId,
            Optional<ResourceLocation> reasonId) {
        return new InteractionPreviewResult(status, signalTypeId,
                Optional.of(Objects.requireNonNull(primaryGodId, "primaryGodId")), reasonId);
    }
}
