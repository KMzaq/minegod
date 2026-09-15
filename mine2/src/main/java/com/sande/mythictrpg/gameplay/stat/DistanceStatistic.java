package com.sande.mythictrpg.gameplay.stat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;

public enum DistanceStatistic {
    WALK(Stats.WALK_ONE_CM),
    SPRINT(Stats.SPRINT_ONE_CM),
    CROUCH(Stats.CROUCH_ONE_CM),
    SWIM(Stats.SWIM_ONE_CM),
    WALK_ON_WATER(Stats.WALK_ON_WATER_ONE_CM),
    WALK_UNDER_WATER(Stats.WALK_UNDER_WATER_ONE_CM),
    FALL(Stats.FALL_ONE_CM),
    CLIMB(Stats.CLIMB_ONE_CM),
    FLY(Stats.FLY_ONE_CM),
    AVIATE(Stats.AVIATE_ONE_CM),
    BOAT(Stats.BOAT_ONE_CM),
    MINECART(Stats.MINECART_ONE_CM),
    PIG(Stats.PIG_ONE_CM),
    HORSE(Stats.HORSE_ONE_CM),
    STRIDER(Stats.STRIDER_ONE_CM);

    private final ResourceLocation statisticId;

    DistanceStatistic(ResourceLocation statisticId) {
        this.statisticId = statisticId;
    }

    public ResourceLocation statisticId() {
        return statisticId;
    }
}
