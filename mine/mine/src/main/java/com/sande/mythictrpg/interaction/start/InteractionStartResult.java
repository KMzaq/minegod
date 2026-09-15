package com.sande.mythictrpg.interaction.start;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record InteractionStartResult(InteractionStartStatus status, Optional<UUID> interactionId,
        Optional<DeliverySummary> delivery, Optional<ResourceLocation> reason) {
    public InteractionStartResult {
        Objects.requireNonNull(status, "status");
        interactionId = interactionId == null ? Optional.empty() : interactionId;
        delivery = delivery == null ? Optional.empty() : delivery;
        reason = reason == null ? Optional.empty() : reason;
        boolean started = status == InteractionStartStatus.STARTED;
        if (started) {
            if (interactionId.isEmpty() || delivery.isEmpty() || reason.isPresent()) {
                throw new IllegalArgumentException(
                        "STARTED requires an interaction ID and delivery, with no rejection reason");
            }
        } else if (interactionId.isPresent() || delivery.isPresent() || reason.isEmpty()) {
            throw new IllegalArgumentException(
                    "Rejected starts require a reason and may not expose an interaction ID or delivery");
        }
    }

    public static InteractionStartResult started(UUID interactionId, DeliverySummary delivery) {
        return new InteractionStartResult(InteractionStartStatus.STARTED,
                Optional.of(interactionId), Optional.of(delivery), Optional.empty());
    }

    public static InteractionStartResult rejected(InteractionStartStatus status, ResourceLocation reason) {
        if (status == InteractionStartStatus.STARTED) {
            throw new IllegalArgumentException("Use started factory");
        }
        return new InteractionStartResult(status, Optional.empty(), Optional.empty(), Optional.of(reason));
    }
}
