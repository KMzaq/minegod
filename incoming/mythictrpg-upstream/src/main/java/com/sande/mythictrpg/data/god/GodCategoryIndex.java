package com.sande.mythictrpg.data.god;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable category lookup derived once for each successful God definition reload. */
public final class GodCategoryIndex {
    private static final GodCategoryIndex EMPTY = new GodCategoryIndex(Map.of());

    private final Map<ResourceLocation, Set<ResourceLocation>> godsByCategory;

    private GodCategoryIndex(Map<ResourceLocation, Set<ResourceLocation>> godsByCategory) {
        this.godsByCategory = godsByCategory;
    }

    public static GodCategoryIndex empty() {
        return EMPTY;
    }

    public static GodCategoryIndex build(Map<ResourceLocation, GodDefinition> definitions) {
        Map<ResourceLocation, Set<ResourceLocation>> mutable = new LinkedHashMap<>();
        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                entry.getValue().categories().stream().sorted().forEach(category ->
                        mutable.computeIfAbsent(category, ignored -> new LinkedHashSet<>()).add(entry.getKey())));

        Map<ResourceLocation, Set<ResourceLocation>> immutable = new LinkedHashMap<>();
        mutable.forEach((category, gods) -> immutable.put(category, Set.copyOf(gods)));
        return new GodCategoryIndex(Map.copyOf(immutable));
    }

    public Set<ResourceLocation> godsInCategory(ResourceLocation categoryId) {
        return godsByCategory.getOrDefault(categoryId, Set.of());
    }
}
