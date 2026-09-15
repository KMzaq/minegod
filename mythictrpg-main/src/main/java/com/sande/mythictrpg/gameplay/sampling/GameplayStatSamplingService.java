package com.sande.mythictrpg.gameplay.sampling;

import com.sande.mythictrpg.gameplay.observation.ThresholdCrossingObservationPublisher;
import com.sande.mythictrpg.gameplay.stat.MinecraftPlayerGameplayStatisticsView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Collection;
import java.util.Objects;

public final class GameplayStatSamplingService {
    public static final GameplayStatSamplingService INSTANCE = new GameplayStatSamplingService();

    private volatile WatchedMetricSnapshotProvider productionProvider = WatchedMetricSnapshotProvider.EMPTY;
    private volatile WatchedMetricSnapshot manualSnapshot;

    private GameplayStatSamplingService() {
    }

    public WatchedMetricSnapshot snapshot() {
        WatchedMetricSnapshot override = manualSnapshot;
        if (override != null) {
            return override;
        }
        try {
            WatchedMetricSnapshot production = productionProvider.snapshot();
            return production == null ? WatchedMetricSnapshot.empty() : production;
        } catch (RuntimeException unavailable) {
            return WatchedMetricSnapshot.empty();
        }
    }

    public synchronized void configureProductionProvider(WatchedMetricSnapshotProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (productionProvider != WatchedMetricSnapshotProvider.EMPTY) {
            throw new IllegalStateException("The production watched metric provider is already configured");
        }
        productionProvider = provider;
    }

    public void installManualSnapshot(WatchedMetricSnapshot snapshot) {
        manualSnapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public void clearManualSnapshot() {
        manualSnapshot = null;
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
        WatchedMetricSnapshot current = snapshot();
        if (current.isEmpty()) {
            SamplingRuntimeState.discard(server);
            return;
        }
        var players = server.getPlayerList().getPlayers().stream().map(this::view).toList();
        SamplingResult result = SamplingRuntimeState.get(server).sample(current,
                server.overworld().getGameTime(), players);
        result.crossings().forEach(crossing ->
                ThresholdCrossingObservationPublisher.INSTANCE.publish(server, crossing));
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
