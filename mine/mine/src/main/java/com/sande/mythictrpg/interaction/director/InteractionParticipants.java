package com.sande.mythictrpg.interaction.director;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record InteractionParticipants(ResourceLocation primaryGodId,
        List<ResourceLocation> secondaryGodIds) {
    public static final int MAX_SECONDARY = 2;

    public InteractionParticipants {
        Objects.requireNonNull(primaryGodId, "primaryGodId");
        secondaryGodIds = List.copyOf(Objects.requireNonNull(secondaryGodIds, "secondaryGodIds"));
        if (secondaryGodIds.size() > MAX_SECONDARY) {
            throw new IllegalArgumentException("At most " + MAX_SECONDARY + " secondary Gods are allowed");
        }
        Set<ResourceLocation> unique = new LinkedHashSet<>(secondaryGodIds);
        if (unique.size() != secondaryGodIds.size() || unique.contains(primaryGodId)) {
            throw new IllegalArgumentException("Interaction participants must be unique");
        }
    }

    public static InteractionParticipants primaryOnly(ResourceLocation godId) {
        return new InteractionParticipants(godId, List.of());
    }
}
