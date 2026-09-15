package com.sande.mythictrpg.gameplay.stat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

final class UnavailablePlayerGameplayStatisticsView implements PlayerGameplayStatisticsView {
    static final UnavailablePlayerGameplayStatisticsView INSTANCE = new UnavailablePlayerGameplayStatisticsView();

    private UnavailablePlayerGameplayStatisticsView() {
    }

    @Override
    public StatisticValue blockMined(Block block) {
        return StatisticValue.unavailable();
    }

    @Override
    public StatisticValue entityKilled(EntityType<?> entityType) {
        return StatisticValue.unavailable();
    }

    @Override
    public StatisticValue totalMobKills() {
        return StatisticValue.unavailable();
    }

    @Override
    public StatisticValue animalsBred() {
        return StatisticValue.unavailable();
    }

    @Override
    public StatisticValue deaths() {
        return StatisticValue.unavailable();
    }

    @Override
    public StatisticValue playTime() {
        return StatisticValue.unavailable();
    }

    @Override
    public StatisticValue distance(DistanceStatistic statistic) {
        return StatisticValue.unavailable();
    }

    @Override
    public StatisticValue custom(ResourceLocation statisticId) {
        return StatisticValue.unavailable();
    }
}
