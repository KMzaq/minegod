package com.sande.mythictrpg.gameplay.sampling;

import com.sande.mythictrpg.gameplay.stat.MinecraftPlayerGameplayStatisticsView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Collection;
import java.util.Objects;

public final class GameplayStatSamplingService {
    public static final GameplayStatSamplingService INSTANCE = new GameplayStatSamplingService();

    private volatile WatchedMetricSnapshot snapshot = WatchedMetricSnapshot.empty();

    private GameplayStatSamplingService() {
    }

    public WatchedMetricSnapshot snapshot() {
        return snapshot;
    }

    public void installManualSnapshot(WatchedMetricSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public void clearManualSnapshot() {
        snapshot = WatchedMetricSnapshot.empty();
    }

    public void setSnapshotForTesting(WatchedMetricSnapshot snapshot) {
        installManualSnapshot(snapshot);
    }

    public void resetForTesting(MinecraftServer server) {
        clearManualSnapshot();
        SamplingRuntimeState.discard(server);
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        WatchedMetricSnapshot current = snapshot;
        if (current.isEmpty()) {
            SamplingRuntimeState.discard(server);
            return;
        }
        var players = server.getPlayerList().getPlayers().stream().map(this::view).toList();
        SamplingRuntimeState.get(server).sample(current, server.overworld().getGameTime(), players);
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SamplingRuntimeState.removePlayerIfPresent(player.server, player.getUUID());
        }
    }

    SamplingResult sampleForTesting(MinecraftServer server, WatchedMetricSnapshot snapshot,
            long gameTime, Collection<SamplingPlayerView> players) {
        return SamplingRuntimeState.get(server).sample(snapshot, gameTime, players);
    }

    private SamplingPlayerView view(ServerPlayer player) {
        return new SamplingPlayerView(player.getUUID(), MinecraftPlayerGameplayStatisticsView.online(player));
    }
}
