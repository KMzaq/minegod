package com.sande.mythictrpg.interaction.runtime;

import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class InteractionRuntimeState implements CooldownView, InteractionRuntimeView {
    public static final long SPONTANEOUS_COOLDOWN_TICKS = 200L;
    private static final Map<MinecraftServer, InteractionRuntimeState> STATES = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final LongSupplier gameTime;
    private final Map<UUID, UUID> reservations = new HashMap<>();
    private final Map<UUID, Long> spontaneousCooldownUntil = new HashMap<>();

    private InteractionRuntimeState(MinecraftServer server, LongSupplier gameTime) {
        this.server = server;
        this.gameTime = gameTime;
    }

    public static InteractionRuntimeState get(MinecraftServer server) {
        requireServerThread(server);
        return STATES.computeIfAbsent(server,
                key -> new InteractionRuntimeState(key, () -> key.overworld().getGameTime()));
    }

    public static InteractionRuntimeState forTesting(MinecraftServer server, LongSupplier gameTime) {
        return new InteractionRuntimeState(server, gameTime);
    }

    public static void discard(MinecraftServer server) {
        requireServerThread(server);
        STATES.remove(server);
    }

    public Optional<Reservation> tryReserve(UUID playerId) {
        requireServerThread(server);
        UUID token = UUID.randomUUID();
        if (reservations.putIfAbsent(playerId, token) != null) {
            return Optional.empty();
        }
        return Optional.of(new Reservation(playerId, token));
    }

    public void commit(Reservation reservation, InteractionMode mode) {
        requireReservation(reservation);
        if (mode == InteractionMode.SPONTANEOUS) {
            spontaneousCooldownUntil.put(reservation.playerId(),
                    gameTime.getAsLong() + SPONTANEOUS_COOLDOWN_TICKS);
        }
    }

    public void release(Reservation reservation) {
        requireServerThread(server);
        reservations.remove(reservation.playerId(), reservation.token());
    }

    @Override
    public Optional<ResourceLocation> blockReason(UUID playerId, ResourceLocation godId, InteractionMode mode) {
        requireServerThread(server);
        if (mode == InteractionMode.SPONTANEOUS
                && spontaneousCooldownUntil.getOrDefault(playerId, Long.MIN_VALUE) > gameTime.getAsLong()) {
            return Optional.of(InteractionRuntimeReasons.SPONTANEOUS_COOLDOWN);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ResourceLocation> candidateBlockReason(
            UUID playerId, ResourceLocation godId, InteractionMode mode) {
        return planningBlockReason(playerId, mode);
    }

    @Override
    public Optional<ResourceLocation> planningBlockReason(UUID playerId, InteractionMode mode) {
        requireServerThread(server);
        if (reservations.containsKey(playerId)) {
            return Optional.of(InteractionRuntimeReasons.PLAYER_BUSY);
        }
        return Optional.empty();
    }

    private void requireReservation(Reservation reservation) {
        requireServerThread(server);
        if (!reservation.token().equals(reservations.get(reservation.playerId()))) {
            throw new IllegalStateException("Interaction reservation is not active");
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Interaction runtime state may only be accessed on the server thread");
        }
    }

    public record Reservation(UUID playerId, UUID token) {
    }
}
