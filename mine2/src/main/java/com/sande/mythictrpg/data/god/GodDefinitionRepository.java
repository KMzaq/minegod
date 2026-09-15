package com.sande.mythictrpg.data.god;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface GodDefinitionRepository {
    Map<ResourceLocation, GodDefinition> definitions();

    default Optional<GodDefinition> find(ResourceLocation id) {
        return Optional.ofNullable(definitions().get(id));
    }

    default Set<ResourceLocation> ids() {
        return definitions().keySet();
    }
}
