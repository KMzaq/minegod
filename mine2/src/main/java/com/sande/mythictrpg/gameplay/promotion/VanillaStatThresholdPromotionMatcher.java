package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticKey;
import com.sande.mythictrpg.gameplay.sampling.WatchedMetricDefinition;

import java.util.Objects;

public record VanillaStatThresholdPromotionMatcher(
        VanillaStatisticKey vanillaStatisticKey,
        GameplayMetricKey metricKey,
        long threshold,
        int samplingIntervalTicks
) implements PromotionMatcher {
    public VanillaStatThresholdPromotionMatcher {
        Objects.requireNonNull(vanillaStatisticKey, "vanillaStatisticKey");
        Objects.requireNonNull(metricKey, "metricKey");
        if (threshold < 1 || threshold > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Threshold must be between 1 and " + Integer.MAX_VALUE);
        }
        if (samplingIntervalTicks < WatchedMetricDefinition.MIN_INTERVAL_TICKS
                || samplingIntervalTicks > WatchedMetricDefinition.MAX_INTERVAL_TICKS) {
            throw new IllegalArgumentException("Sampling interval must be between "
                    + WatchedMetricDefinition.MIN_INTERVAL_TICKS + " and "
                    + WatchedMetricDefinition.MAX_INTERVAL_TICKS + " ticks");
        }
    }
}
