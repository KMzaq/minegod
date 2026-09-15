package com.sande.mythictrpg.gameplay.metric;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record GameplayMetricKey(ResourceLocation metricType, Optional<ResourceLocation> subjectId)
        implements Comparable<GameplayMetricKey> {
    public GameplayMetricKey {
        Objects.requireNonNull(metricType, "metricType");
        Objects.requireNonNull(subjectId, "subjectId");
    }

    public static GameplayMetricKey aggregate(ResourceLocation metricType) {
        return new GameplayMetricKey(metricType, Optional.empty());
    }

    public static GameplayMetricKey subject(ResourceLocation metricType, ResourceLocation subjectId) {
        return new GameplayMetricKey(metricType, Optional.of(subjectId));
    }

    @Override
    public int compareTo(GameplayMetricKey other) {
        int typeComparison = metricType.compareTo(other.metricType);
        if (typeComparison != 0) {
            return typeComparison;
        }
        if (subjectId.isEmpty()) {
            return other.subjectId.isEmpty() ? 0 : -1;
        }
        return other.subjectId.isEmpty() ? 1 : subjectId.orElseThrow().compareTo(other.subjectId.orElseThrow());
    }
}
