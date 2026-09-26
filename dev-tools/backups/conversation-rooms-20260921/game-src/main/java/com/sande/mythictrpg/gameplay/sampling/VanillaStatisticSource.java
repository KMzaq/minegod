package com.sande.mythictrpg.gameplay.sampling;

import com.sande.mythictrpg.gameplay.stat.DistanceStatistic;
import com.sande.mythictrpg.gameplay.stat.PlayerGameplayStatisticsView;
import com.sande.mythictrpg.gameplay.stat.StatisticValue;
import net.minecraft.stats.Stats;

import java.util.Objects;

public record VanillaStatisticSource(VanillaStatisticKey key) implements Comparable<VanillaStatisticSource> {
    public VanillaStatisticSource {
        Objects.requireNonNull(key, "key");
        if (!key.statTypeId().equals(VanillaStatisticKey.CUSTOM_STAT_TYPE)) {
            throw new IllegalArgumentException("Unsupported Vanilla statistic type: " + key.statTypeId());
        }
    }

    public static VanillaStatisticSource playTime() {
        return custom(Stats.PLAY_TIME);
    }

    public static VanillaStatisticSource distance(DistanceStatistic statistic) {
        Objects.requireNonNull(statistic, "statistic");
        return custom(statistic.statisticId());
    }

    public static VanillaStatisticSource custom(net.minecraft.resources.ResourceLocation statisticId) {
        return new VanillaStatisticSource(VanillaStatisticKey.custom(statisticId));
    }

    public StatisticValue read(PlayerGameplayStatisticsView statistics) {
        return statistics.custom(key.valueId());
    }

    @Override
    public int compareTo(VanillaStatisticSource other) {
        return key.compareTo(other.key);
    }
}
