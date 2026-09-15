package com.sande.mythictrpg.quest.dynamic;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Future extension point for best-reward gear, blessing and world-progress power scoring. */
@FunctionalInterface
public interface CombatPowerProvider {
    CombatPowerAssessment assess(MinecraftServer server, ServerPlayer player,
            Map<ResourceLocation, Integer> worldProgress);
}
