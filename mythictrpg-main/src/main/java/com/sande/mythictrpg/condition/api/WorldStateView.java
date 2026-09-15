package com.sande.mythictrpg.condition.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public interface WorldStateView {
    default boolean isReady() {
        return true;
    }

    int dataVersion();

    Set<ResourceLocation> unlockedGods();

    default boolean isGodUnlocked(ResourceLocation godId) {
        return unlockedGods().contains(godId);
    }
}
