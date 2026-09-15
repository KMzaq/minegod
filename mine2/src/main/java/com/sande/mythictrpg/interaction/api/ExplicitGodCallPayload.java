package com.sande.mythictrpg.interaction.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ExplicitGodCallPayload(ResourceLocation targetGodId, ResourceLocation policyId)
        implements InteractionPayload {
    public ExplicitGodCallPayload {
        Objects.requireNonNull(targetGodId, "targetGodId");
        Objects.requireNonNull(policyId, "policyId");
    }
}
