package com.sande.mythictrpg.gameplay.sampling;

import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record WatchedMetricDefinition(
        ResourceLocation watchId,
        GameplayMetricKey metricKey,
        VanillaStatisticSource source,
        ThresholdPolicy thresholdPolicy,
        int intervalTicks
) {
    public static final int DEFAULT_INTERVAL_TICKS = 100;
    public static final int MIN_INTERVAL_TICKS = 20;
    public static final int MAX_INTERVAL_TICKS = 12_000;

    public WatchedMetricDefinition {
        Objects.requireNonNull(watchId, "watchId");
        Objects.requireNonNull(metricKey, "metricKey");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(thresholdPolicy, "thresholdPolicy");
        if (intervalTicks < MIN_INTERVAL_TICKS || intervalTicks > MAX_INTERVAL_TICKS) {
            throw new IllegalArgumentException("Sampling interval must be between " + MIN_INTERVAL_TICKS
                    + " and " + MAX_INTERVAL_TICKS + " ticks: " + intervalTicks);
        }
    }

    public static WatchedMetricDefinition milestone(ResourceLocation watchId, GameplayMetricKey metricKey,
            VanillaStatisticSource source, long threshold) {
        return milestone(watchId, metricKey, source, threshold, DEFAULT_INTERVAL_TICKS);
    }

    public static WatchedMetricDefinition milestone(ResourceLocation watchId, GameplayMetricKey metricKey,
            VanillaStatisticSource source, long threshold, int intervalTicks) {
        return new WatchedMetricDefinition(watchId, metricKey, source,
                ThresholdPolicy.milestone(threshold), intervalTicks);
    }
}
