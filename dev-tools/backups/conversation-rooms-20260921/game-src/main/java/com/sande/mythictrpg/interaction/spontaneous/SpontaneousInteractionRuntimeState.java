package com.sande.mythictrpg.interaction.spontaneous;

import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class SpontaneousInteractionRuntimeState {
    public static final long DEFAULT_TIMEOUT_TICKS = 1_200L;
    public static final long MIN_TIMEOUT_TICKS = 100L;
    public static final long MAX_TIMEOUT_TICKS = 12_000L;
    public static final int MAX_IN_FLIGHT_PER_SERVER = 1_024;
    private static final Map<MinecraftServer, SpontaneousInteractionRuntimeState> STATES =
            new IdentityHashMap<>();

    private final MinecraftServer server;
    private final LongSupplier gameTime;
    private final long timeoutTicks;
    private final Map<UUID, Permit> permits = new HashMap<>();
    private boolean discarded;

    private SpontaneousInteractionRuntimeState(MinecraftServer server, LongSupplier gameTime,
            long timeoutTicks) {
        this.server = Objects.requireNonNull(server, "server");
        this.gameTime = Objects.requireNonNull(gameTime, "gameTime");
        validateTimeout(timeoutTicks);
        this.timeoutTicks = timeoutTicks;
    }

    public static SpontaneousInteractionRuntimeState get(MinecraftServer server) {
        requireServerThread(server);
        return STATES.computeIfAbsent(server, key -> new SpontaneousInteractionRuntimeState(
                key, () -> key.overworld().getGameTime(), DEFAULT_TIMEOUT_TICKS));
    }

    static SpontaneousInteractionRuntimeState forTesting(MinecraftServer server,
            LongSupplier gameTime, long timeoutTicks) {
        requireServerThread(server);
        return new SpontaneousInteractionRuntimeState(server, gameTime, timeoutTicks);
    }

    public static void removePlayerIfPresent(MinecraftServer server, UUID playerId) {
        requireServerThread(server);
        SpontaneousInteractionRuntimeState state = STATES.get(server);
        if (state != null) {
            state.removePlayer(playerId);
        }
    }

    public static void discard(MinecraftServer server) {
        requireServerThread(server);
        SpontaneousInteractionRuntimeState state = STATES.remove(server);
        if (state != null) {
            state.discarded = true;
            state.permits.clear();
        }
    }

    AcquireResult tryAcquire(UUID playerId) {
        requireServerThread(server);
        Objects.requireNonNull(playerId, "playerId");
        if (discarded) {
            return AcquireResult.rejected(AcquireStatus.DISCARDED);
        }
        long now = currentTick();
        cleanupExpired(now);
        if (permits.containsKey(playerId)) {
            return AcquireResult.rejected(AcquireStatus.ALREADY_IN_FLIGHT);
        }
        if (permits.size() >= MAX_IN_FLIGHT_PER_SERVER) {
            return AcquireResult.rejected(AcquireStatus.CAPACITY_REJECTED);
        }
        Permit permit = new Permit(playerId, UUID.randomUUID(), now,
                saturatingAdd(now, timeoutTicks));
        permits.put(playerId, permit);
        return AcquireResult.acquired(permit);
    }

    boolean isActive(Permit permit) {
        requireServerThread(server);
        Objects.requireNonNull(permit, "permit");
        if (discarded) {
            return false;
        }
        cleanupExpired(currentTick());
        return permit.equals(permits.get(permit.playerId()));
    }

    void release(Permit permit) {
        requireServerThread(server);
        Objects.requireNonNull(permit, "permit");
        permits.remove(permit.playerId(), permit);
    }

    public void removePlayer(UUID playerId) {
        requireServerThread(server);
        permits.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    int entryCountForTesting() {
        requireServerThread(server);
        cleanupExpired(currentTick());
        return permits.size();
    }

    boolean hasPermitForTesting(UUID playerId) {
        requireServerThread(server);
        cleanupExpired(currentTick());
        return permits.containsKey(playerId);
    }

    static boolean hasStateForTesting(MinecraftServer server) {
        requireServerThread(server);
        return STATES.containsKey(server);
    }

    private void cleanupExpired(long now) {
        Iterator<Permit> iterator = permits.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAtTick() <= now) {
                iterator.remove();
            }
        }
    }

    private long currentTick() {
        long tick = gameTime.getAsLong();
        if (tick < 0L) {
            throw new IllegalStateException("game tick must be non-negative");
        }
        return tick;
    }

    private static void validateTimeout(long timeoutTicks) {
        if (timeoutTicks < MIN_TIMEOUT_TICKS || timeoutTicks > MAX_TIMEOUT_TICKS) {
            throw new IllegalArgumentException("timeoutTicks must be between "
                    + MIN_TIMEOUT_TICKS + " and " + MAX_TIMEOUT_TICKS + ": " + timeoutTicks);
        }
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Spontaneous interaction runtime may only be accessed on the server thread");
        }
    }

    record Permit(UUID playerId, UUID token, long startedAtTick, long expiresAtTick) {
        Permit {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(token, "token");
            if (startedAtTick < 0L || expiresAtTick < startedAtTick) {
                throw new IllegalArgumentException("Invalid permit tick range");
            }
        }
    }

    record AcquireResult(AcquireStatus status, Permit permit) {
        AcquireResult {
            Objects.requireNonNull(status, "status");
            if ((status == AcquireStatus.ACQUIRED) != (permit != null)) {
                throw new IllegalArgumentException("Only ACQUIRED may contain a permit");
            }
        }

        static AcquireResult acquired(Permit permit) {
            return new AcquireResult(AcquireStatus.ACQUIRED,
                    Objects.requireNonNull(permit, "permit"));
        }

        static AcquireResult rejected(AcquireStatus status) {
            if (status == AcquireStatus.ACQUIRED) {
                throw new IllegalArgumentException("Use acquired factory");
            }
            return new AcquireResult(status, null);
        }
    }

    enum AcquireStatus {
        ACQUIRED,
        ALREADY_IN_FLIGHT,
        CAPACITY_REJECTED,
        DISCARDED
    }
}
