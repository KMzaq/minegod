package com.sande.mythictrpg.condition.api;

import net.minecraft.resources.ResourceLocation;

import com.sande.mythictrpg.data.god.GodDefinition;

import java.util.Optional;
import java.util.Set;

public interface GodDefinitionView {
    boolean isReady();

    default Optional<GodDefinition> find(ResourceLocation godId) {
        return Optional.empty();
    }

    Set<ResourceLocation> godsInCategory(ResourceLocation categoryId);
}
