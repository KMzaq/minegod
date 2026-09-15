package com.sande.mythictrpg.gameplay.stat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

import java.util.Objects;

public final class MinecraftPlayerGameplayStatisticsView implements PlayerGameplayStatisticsView {
    private final ServerStatsCounter stats;

    private MinecraftPlayerGameplayStatisticsView(ServerStatsCounter stats) {
        this.stats = stats;
    }

    public static PlayerGameplayStatisticsView online(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return new MinecraftPlayerGameplayStatisticsView(player.getStats());
    }

    public static PlayerGameplayStatisticsView unavailable() {
        return UnavailablePlayerGameplayStatisticsView.INSTANCE;
    }

    @Override
    public StatisticValue blockMined(Block block) {
        Objects.requireNonNull(block, "block");
        return StatisticValue.available(stats.getValue(Stats.BLOCK_MINED.get(block)));
    }

    @Override
    public StatisticValue entityKilled(EntityType<?> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        return StatisticValue.available(stats.getValue(Stats.ENTITY_KILLED.get(entityType)));
    }

    @Override
    public StatisticValue totalMobKills() {
        return StatisticValue.available(stats.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)));
    }

    @Override
    public StatisticValue animalsBred() {
        return StatisticValue.available(stats.getValue(Stats.CUSTOM.get(Stats.ANIMALS_BRED)));
    }

    @Override
    public StatisticValue deaths() {
        return StatisticValue.available(stats.getValue(Stats.CUSTOM.get(Stats.DEATHS)));
    }

    @Override
    public StatisticValue playTime() {
        return custom(Stats.PLAY_TIME);
    }

    @Override
    public StatisticValue distance(DistanceStatistic statistic) {
        Objects.requireNonNull(statistic, "statistic");
        return custom(statistic.statisticId());
    }

    @Override
    public StatisticValue custom(ResourceLocation statisticId) {
        Objects.requireNonNull(statisticId, "statisticId");
        if (!BuiltInRegistries.CUSTOM_STAT.containsKey(statisticId)) {
            return StatisticValue.unavailable();
        }
        return StatisticValue.available(stats.getValue(Stats.CUSTOM.get(statisticId)));
    }
}
