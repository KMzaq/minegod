package com.sande.mythictrpg.gameplay.activity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class PlayerActivityRuntimeState implements PlayerActivityView {
    private static final Map<MinecraftServer, PlayerActivityRuntimeState> STATES = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final PlayerActivityPolicy policy;
    private final LongSupplier gameTime;
    private final Map<UUID, Entry> entries = new HashMap<>();

    private PlayerActivityRuntimeState(MinecraftServer server, PlayerActivityPolicy policy,
            LongSupplier gameTime) {
        this.server = Objects.requireNonNull(server, "server");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.gameTime = Objects.requireNonNull(gameTime, "gameTime");
    }

    static PlayerActivityRuntimeState get(MinecraftServer server) {
        requireServerThread(server);
        return STATES.computeIfAbsent(server, key -> new PlayerActivityRuntimeState(key,
                PlayerActivityPolicy.PRODUCTION_DEFAULT, () -> key.overworld().getGameTime()));
    }

    static PlayerActivityView viewIfPresent(MinecraftServer server) {
        requireServerThread(server);
        PlayerActivityRuntimeState state = STATES.get(server);
        return state == null ? PlayerActivityView.UNAVAILABLE : state;
    }

    static void removePlayerIfPresent(MinecraftServer server, UUID playerId) {
        requireServerThread(server);
        PlayerActivityRuntimeState state = STATES.get(server);
        if (state != null) {
            state.removePlayer(playerId);
        }
    }

    static PlayerActivityRuntimeState forTesting(MinecraftServer server, PlayerActivityPolicy policy,
            LongSupplier gameTime) {
        requireServerThread(server);
        return new PlayerActivityRuntimeState(server, policy, gameTime);
    }

    public static void discard(MinecraftServer server) {
        requireServerThread(server);
        STATES.remove(server);
    }

    static boolean hasStateForTesting(MinecraftServer server) {
        requireServerThread(server);
        return STATES.containsKey(server);
    }

    UpdateResult observe(PlayerSample sample, long currentGameTick) {
        requireServerThread(server);
        Objects.requireNonNull(sample, "sample");
        requireGameTick(currentGameTick);
        Entry entry = entries.get(sample.playerId());
        if (entry == null) {
            entries.put(sample.playerId(), Entry.baseline(sample, currentGameTick));
            return UpdateResult.BASELINED;
        }
        if (!entry.dimensionId.equals(sample.dimensionId())) {
            entry.reset(sample, currentGameTick);
            return UpdateResult.RESET_ACTIVE;
        }

        boolean activity = entry.vanillaActionToken != sample.vanillaActionToken()
                || positionChanged(entry, sample)
                || rotationChanged(entry, sample);
        if (activity) {
            boolean wasIdle = entry.state == PlayerActivityState.IDLE;
            entry.reset(sample, currentGameTick);
            return wasIdle ? UpdateResult.BECAME_ACTIVE : UpdateResult.ACTIVITY_RECORDED;
        }

        if (entry.state == PlayerActivityState.ACTIVE
                && inactiveTicks(currentGameTick, entry.lastActivityGameTick) >= policy.idleThresholdTicks()) {
            entry.state = PlayerActivityState.IDLE;
            return UpdateResult.BECAME_IDLE;
        }
        return UpdateResult.UNCHANGED;
    }

    void resetActive(PlayerSample sample, long currentGameTick) {
        requireServerThread(server);
        Objects.requireNonNull(sample, "sample");
        requireGameTick(currentGameTick);
        entries.compute(sample.playerId(), (ignored, current) -> {
            if (current == null) {
                return Entry.baseline(sample, currentGameTick);
            }
            current.reset(sample, currentGameTick);
            return current;
        });
    }

    void removePlayer(UUID playerId) {
        requireServerThread(server);
        entries.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    int entryCountForTesting() {
        requireServerThread(server);
        return entries.size();
    }

    @Override
    public Optional<PlayerActivitySnapshot> find(UUID playerId) {
        requireServerThread(server);
        Entry entry = entries.get(Objects.requireNonNull(playerId, "playerId"));
        if (entry == null) {
            return Optional.empty();
        }
        long currentGameTick = Math.max(0L, gameTime.getAsLong());
        return Optional.of(new PlayerActivitySnapshot(entry.state, entry.lastActivityGameTick,
                inactiveTicks(currentGameTick, entry.lastActivityGameTick)));
    }

    static PlayerSample sample(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return new PlayerSample(player.getUUID(), player.level().dimension().location(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.getLastActionTime());
    }

    private boolean positionChanged(Entry entry, PlayerSample sample) {
        double x = sample.x() - entry.x;
        double y = sample.y() - entry.y;
        double z = sample.z() - entry.z;
        return x * x + y * y + z * z >= policy.positionEpsilonSquared();
    }

    private boolean rotationChanged(Entry entry, PlayerSample sample) {
        double yawDifference = Math.abs(Mth.wrapDegrees((float)(sample.yaw() - entry.yaw)));
        double pitchDifference = Math.abs(sample.pitch() - entry.pitch);
        return yawDifference >= policy.rotationEpsilonDegrees()
                || pitchDifference >= policy.rotationEpsilonDegrees();
    }

    private static long inactiveTicks(long currentGameTick, long lastActivityGameTick) {
        return currentGameTick <= lastActivityGameTick ? 0L : currentGameTick - lastActivityGameTick;
    }

    private static void requireGameTick(long gameTick) {
        if (gameTick < 0L) {
            throw new IllegalArgumentException("gameTick must be non-negative");
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Player activity runtime must run on the server thread");
        }
    }

    enum UpdateResult {
        BASELINED,
        RESET_ACTIVE,
        ACTIVITY_RECORDED,
        BECAME_IDLE,
        BECAME_ACTIVE,
        UNCHANGED
    }

    record PlayerSample(UUID playerId, ResourceLocation dimensionId,
                        double x, double y, double z, double yaw, double pitch,
                        long vanillaActionToken) {
        PlayerSample {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(dimensionId, "dimensionId");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(yaw) || !Double.isFinite(pitch)) {
                throw new IllegalArgumentException("Player activity sample values must be finite");
            }
        }
    }

    private static final class Entry {
        private PlayerActivityState state;
        private long lastActivityGameTick;
        private ResourceLocation dimensionId;
        private double x;
        private double y;
        private double z;
        private double yaw;
        private double pitch;
        private long vanillaActionToken;

        private static Entry baseline(PlayerSample sample, long gameTick) {
            Entry entry = new Entry();
            entry.reset(sample, gameTick);
            return entry;
        }

        private void reset(PlayerSample sample, long gameTick) {
            state = PlayerActivityState.ACTIVE;
            lastActivityGameTick = gameTick;
            dimensionId = sample.dimensionId();
            x = sample.x();
            y = sample.y();
            z = sample.z();
            yaw = sample.yaw();
            pitch = sample.pitch();
            vanillaActionToken = sample.vanillaActionToken();
        }
    }
}
