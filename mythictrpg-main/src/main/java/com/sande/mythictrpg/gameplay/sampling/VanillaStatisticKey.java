package com.sande.mythictrpg.gameplay.sampling;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record VanillaStatisticKey(ResourceLocation statTypeId, ResourceLocation valueId)
        implements Comparable<VanillaStatisticKey> {
    public static final ResourceLocation CUSTOM_STAT_TYPE = ResourceLocation.withDefaultNamespace("custom");

    public VanillaStatisticKey {
        Objects.requireNonNull(statTypeId, "statTypeId");
        Objects.requireNonNull(valueId, "valueId");
    }

    public static VanillaStatisticKey custom(ResourceLocation statisticId) {
        return new VanillaStatisticKey(CUSTOM_STAT_TYPE, statisticId);
    }

    @Override
    public int compareTo(VanillaStatisticKey other) {
        int typeComparison = statTypeId.compareTo(other.statTypeId);
        return typeComparison != 0 ? typeComparison : valueId.compareTo(other.valueId);
    }
}
