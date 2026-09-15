package com.sande.mythictrpg.interaction.director;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record InteractionDecision(Status status, Optional<InteractionPlan> plan,
        Optional<ResourceLocation> reason) {
    public InteractionDecision {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(reason, "reason");
        if (status == Status.START && (plan.isEmpty() || reason.isPresent())) {
            throw new IllegalArgumentException("START requires a plan and no rejection reason");
        }
        if (status == Status.NO_START && (plan.isPresent() || reason.isEmpty())) {
            throw new IllegalArgumentException("NO_START requires a reason and no plan");
        }
    }

    public static InteractionDecision start(InteractionPlan plan) {
        return new InteractionDecision(Status.START, Optional.of(plan), Optional.empty());
    }

    public static InteractionDecision noStart(ResourceLocation reason) {
        return new InteractionDecision(Status.NO_START, Optional.empty(), Optional.of(reason));
    }

    public enum Status {
        START,
        NO_START
    }
}
