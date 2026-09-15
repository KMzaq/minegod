package com.sande.mythictrpg.gameplay.observation;

import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record VanillaStatThresholdCrossedPayload(ResourceLocation watchId,
                                                 GameplayMetricKey metricKey,
                                                 VanillaStatisticKey vanillaStatisticKey,
                                                 long previousValue,
                                                 long currentValue,
                                                 long delta,
                                                 long threshold)
        implements GameplayObservationPayload {
    public VanillaStatThresholdCrossedPayload {
        Objects.requireNonNull(watchId, "watchId");
        Objects.requireNonNull(metricKey, "metricKey");
        Objects.requireNonNull(vanillaStatisticKey, "vanillaStatisticKey");
        if (previousValue < 0) {
            throw new IllegalArgumentException("previousValue must be non-negative");
        }
        if (currentValue <= previousValue) {
            throw new IllegalArgumentException("currentValue must exceed previousValue");
        }
        if (currentValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("currentValue exceeds vanilla statistic range");
        }
        if (delta != currentValue - previousValue) {
            throw new IllegalArgumentException("delta must equal currentValue - previousValue");
        }
        if (threshold <= previousValue || threshold > currentValue) {
            throw new IllegalArgumentException("threshold must be crossed by previous/current values");
        }
    }

    @Override
    public Optional<ResourceLocation> subjectId() {
        return metricKey.subjectId();
    }

    @Override
    public Optional<ResourceLocation> coalescingDiscriminator() {
        return Optional.of(watchId);
    }
}
