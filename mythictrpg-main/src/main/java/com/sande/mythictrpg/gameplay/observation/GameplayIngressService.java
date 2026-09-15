package com.sande.mythictrpg.gameplay.observation;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class GameplayIngressService {
    public static final GameplayIngressService INSTANCE = new GameplayIngressService();

    private final Map<CoalesceKey, GameplayObservation<?>> pending = new LinkedHashMap<>();
    private GameplayObservationSink productionSink = GameplayObservationSink.NO_OP;
    private GameplayObservationSink testOverride;
    private boolean productionSinkConfigured;
    private long acceptedCount;
    private long emittedCount;

    private GameplayIngressService() {
    }

    static GameplayIngressService forTesting() {
        return new GameplayIngressService();
    }

    public void configureProductionSink(GameplayObservationSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (!productionSinkConfigured) {
            productionSink = sink;
            productionSinkConfigured = true;
            return;
        }
        if (productionSink != sink) {
            throw new IllegalStateException("Gameplay observation production sink is already configured");
        }
    }

    public void setSinkForTesting(GameplayObservationSink sink) {
        testOverride = Objects.requireNonNull(sink, "sink");
    }

    public void clearSinkOverrideForTesting() {
        testOverride = null;
    }

    public void resetForTesting() {
        pending.clear();
        testOverride = null;
        acceptedCount = 0;
        emittedCount = 0;
    }

    public long acceptedCountForTesting() {
        return acceptedCount;
    }

    public long emittedCountForTesting() {
        return emittedCount;
    }

    public int pendingCountForTesting() {
        return pending.size();
    }

    public void accept(MinecraftServer server, GameplayObservation<?> observation) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(observation, "observation");
        requireServerThread(server);
        pending.put(CoalesceKey.from(observation), observation);
        acceptedCount++;
    }

    public int drain(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        requireServerThread(server);
        if (pending.isEmpty()) {
            return 0;
        }
        List<GameplayObservation<?>> drained = List.copyOf(new ArrayList<>(pending.values()));
        pending.clear();
        GameplayObservationSink effectiveSink = testOverride != null ? testOverride : productionSink;
        for (GameplayObservation<?> observation : drained) {
            try {
                effectiveSink.accept(server, observation);
            } catch (RuntimeException exception) {
                MythicTrpg.LOGGER.error("Gameplay observation sink failed for type {} and player {}",
                        observation.type().id(), observation.initiatingPlayerId(), exception);
            } finally {
                emittedCount++;
            }
        }
        return drained.size();
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        drain(event.getServer());
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Gameplay observation ingress must run on the server thread");
        }
    }

    private record CoalesceKey(UUID playerId, ResourceLocation typeId, Optional<ResourceLocation> subjectId) {
        static CoalesceKey from(GameplayObservation<?> observation) {
            return new CoalesceKey(observation.initiatingPlayerId(), observation.type().id(),
                    observation.payload().coalescingDiscriminator());
        }
    }
}
