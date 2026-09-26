package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.interaction.context.InteractionContextFactory;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import net.minecraft.server.MinecraftServer;

import java.util.Set;
import java.util.stream.Collectors;

@FunctionalInterface
public interface InteractionStartStateProvider {
    InteractionStartStateProvider LIVE = (server, request, runtime) -> {
        Set<java.util.UUID> online = server.getPlayerList().getPlayers().stream()
                .map(player -> player.getUUID()).collect(Collectors.toUnmodifiableSet());
        Set<java.util.UUID> active = PlayerMythDataService.get(server).activeProfiles().entrySet().stream()
                .filter(entry -> entry.getValue().participationStatus() == ParticipationStatus.ACTIVE)
                .map(java.util.Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
        return new InteractionStartSnapshot(
                InteractionContextFactory.create(server, request.signal(), Set.of(), runtime, runtime),
                online, active);
    };

    InteractionStartSnapshot capture(MinecraftServer server, InteractionStartRequest request,
            InteractionRuntimeState runtime);
}
