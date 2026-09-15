package com.sande.mythictrpg.condition.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.Objects;

public record ConditionEnvironment(ResourceKey<Level> level, BlockPos position,
        Optional<net.minecraft.resources.ResourceLocation> biomeId, int dayTime) {
    public ConditionEnvironment {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(biomeId, "biomeId");
        if (dayTime < 0 || dayTime >= 24000) {
            throw new IllegalArgumentException("dayTime must be in range 0..23999");
        }
        position = position.immutable();
    }
}
