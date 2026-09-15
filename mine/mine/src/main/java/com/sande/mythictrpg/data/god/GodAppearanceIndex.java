package com.sande.mythictrpg.data.god;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable lazy-evaluation candidate set built with each successful God reload. */
public final class GodAppearanceIndex {
    private static final GodAppearanceIndex EMPTY = new GodAppearanceIndex(Set.of());

    private final Set<ResourceLocation> conditionalGods;

    private GodAppearanceIndex(Set<ResourceLocation> conditionalGods) {
        this.conditionalGods = conditionalGods;
    }

    public static GodAppearanceIndex empty() {
        return EMPTY;
    }

    public static GodAppearanceIndex build(Map<ResourceLocation, GodDefinition> definitions) {
        Set<ResourceLocation> conditional = new LinkedHashSet<>();
        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> entry.getValue().appearanceConditions().isPresent())
                .map(Map.Entry::getKey)
                .forEach(conditional::add);
        return new GodAppearanceIndex(Set.copyOf(conditional));
    }

    public Set<ResourceLocation> conditionalGods() {
        return conditionalGods;
    }
}
