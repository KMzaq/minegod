package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record AnimalParentPair(ResourceLocation first, ResourceLocation second) {
    public AnimalParentPair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.compareTo(second) > 0) {
            ResourceLocation swap = first;
            first = second;
            second = swap;
        }
    }

    public static AnimalParentPair of(ResourceLocation parentA, ResourceLocation parentB) {
        return new AnimalParentPair(parentA, parentB);
    }
}
