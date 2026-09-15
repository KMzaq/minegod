package com.sande.mythictrpg.gameplay.stat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

public interface PlayerGameplayStatisticsView {
    StatisticValue blockMined(Block block);

    StatisticValue entityKilled(EntityType<?> entityType);

    StatisticValue totalMobKills();

    StatisticValue animalsBred();

    StatisticValue deaths();

    StatisticValue playTime();

    StatisticValue distance(DistanceStatistic statistic);

    StatisticValue custom(ResourceLocation statisticId);
}
