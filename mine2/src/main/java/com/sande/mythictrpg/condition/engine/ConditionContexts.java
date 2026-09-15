package com.sande.mythictrpg.condition.engine;

import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.GodDefinitionView;
import com.sande.mythictrpg.condition.api.ServerStateView;
import com.sande.mythictrpg.condition.api.WorldStateView;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.world.MythicWorldState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ConditionContexts {
    private ConditionContexts() {
    }

    public static ConditionContext forServer(MinecraftServer server, Optional<UUID> targetPlayerId) {
        GodDefinitionManager.ProgressionSnapshot snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        return forServer(server, targetPlayerId, snapshot.definitions(), snapshot.generation() > 0);
    }

    public static ConditionContext forServer(MinecraftServer server, Optional<UUID> targetPlayerId,
            Map<ResourceLocation, com.sande.mythictrpg.data.god.GodDefinition> definitions,
            boolean godsReady) {
        MythicWorldState state = MythicWorldState.get(server);
        WorldStateView world = new WorldStateView() {
            private final Set<net.minecraft.resources.ResourceLocation> unlocked = state.unlockedGods();

            @Override
            public boolean isReady() {
                return !state.isRejected();
            }

            @Override
            public int dataVersion() {
                return state.dataVersion();
            }

            @Override
            public Set<net.minecraft.resources.ResourceLocation> unlockedGods() {
                return unlocked;
            }
        };
        ServerStateView serverView = () -> server.getPlayerList().getPlayers().stream()
                .map(player -> player.getUUID())
                .collect(Collectors.toUnmodifiableSet());
        GodDefinitionView gods = new GodDefinitionView() {
            @Override
            public boolean isReady() {
                return godsReady;
            }

            @Override
            public Optional<com.sande.mythictrpg.data.god.GodDefinition> find(
                    net.minecraft.resources.ResourceLocation godId) {
                return Optional.ofNullable(definitions.get(godId));
            }

            @Override
            public Set<net.minecraft.resources.ResourceLocation> godsInCategory(
                    net.minecraft.resources.ResourceLocation categoryId) {
                return definitions.entrySet().stream()
                        .filter(entry -> entry.getValue().categories().contains(categoryId))
                        .map(java.util.Map.Entry::getKey)
                        .collect(Collectors.toUnmodifiableSet());
            }
        };
        Optional<com.sande.mythictrpg.condition.api.ConditionEnvironment> environment = targetPlayerId
                .flatMap(id -> Optional.ofNullable(server.getPlayerList().getPlayer(id)))
                .map(player -> {
                    net.minecraft.core.BlockPos position = player.blockPosition().immutable();
                    Optional<net.minecraft.resources.ResourceLocation> biomeId = player.serverLevel()
                            .getBiome(position).unwrapKey().map(net.minecraft.resources.ResourceKey::location);
                    int dayTime = (int) Math.floorMod(player.serverLevel().getDayTime(), 24000L);
                    return new com.sande.mythictrpg.condition.api.ConditionEnvironment(
                            player.serverLevel().dimension(), position, biomeId, dayTime);
                });
        return new ConditionContext(world, PlayerMythDataService.get(server), serverView, gods,
                targetPlayerId, environment, Optional.empty());
    }
}
