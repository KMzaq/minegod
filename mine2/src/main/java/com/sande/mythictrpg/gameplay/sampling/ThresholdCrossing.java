package com.sande.mythictrpg.gameplay.sampling;

import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record ThresholdCrossing(
        ResourceLocation watchId,
        GameplayMetricKey metricKey,
        VanillaStatisticSource source,
        UUID playerId,
        long previousValue,
        long currentValue,
        long threshold,
        long delta
) {
    public ThresholdCrossing {
        Objects.requireNonNull(watchId, "watchId");
        Objects.requireNonNull(metricKey, "metricKey");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(playerId, "playerId");
        if (currentValue < previousValue || delta != currentValue - previousValue) {
            throw new IllegalArgumentException("Threshold crossing values are inconsistent");
        }
        if (!(previousValue < threshold && currentValue >= threshold)) {
            throw new IllegalArgumentException("Values do not cross threshold " + threshold);
        }
    }
}
