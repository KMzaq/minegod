package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class GameplayIngressService {
    public static final GameplayIngressService INSTANCE = new GameplayIngressService();

    private final Map<CoalesceKey, GameplayObservation<?>> pending = new LinkedHashMap<>();
    private GameplayObservationSink sink = GameplayObservationSink.NO_OP;
    private long acceptedCount;
    private long emittedCount;

    private GameplayIngressService() {
    }

    public void setSinkForTesting(GameplayObservationSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public void resetForTesting() {
        pending.clear();
        sink = GameplayObservationSink.NO_OP;
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
        var drained = Map.copyOf(pending);
        pending.clear();
        drained.values().forEach(observation -> {
            sink.accept(server, observation);
            emittedCount++;
        });
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
                    observation.subjectId());
        }
    }
}
