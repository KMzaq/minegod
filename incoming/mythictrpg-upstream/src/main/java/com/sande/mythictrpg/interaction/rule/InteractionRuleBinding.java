package com.sande.mythictrpg.interaction.rule;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record InteractionRuleBinding(
        Optional<ResourceLocation> godId,
        Optional<ResourceLocation> godCategoryId,
        int score,
        ResourceLocation reason
) {
    public InteractionRuleBinding {
        godId = Objects.requireNonNull(godId, "godId");
        godCategoryId = Objects.requireNonNull(godCategoryId, "godCategoryId");
        Objects.requireNonNull(reason, "reason");
        if (godId.isPresent() == godCategoryId.isPresent()) {
            throw new IllegalArgumentException("Exactly one of godId and godCategoryId is required");
        }
    }
}
