package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class GameplayPromotionRuntimeState {
    public static final int MAX_ENTRIES_PER_PLAYER = 256;
    public static final int MAX_ENTRIES_PER_SERVER = 8_192;
    private static final Map<MinecraftServer, GameplayPromotionRuntimeState> STATES =
            new IdentityHashMap<>();
    private static final Comparator<ExpiryNode> EXPIRY_ORDER = Comparator
            .comparingLong(ExpiryNode::nextEligibleTick)
            .thenComparing(node -> node.playerId().toString())
            .thenComparing(ExpiryNode::promotionRuleId);

    private final MinecraftServer server;
    private final LongSupplier gameTime;
    private final Map<UUID, Map<ResourceLocation, CooldownEntry>> entries = new HashMap<>();
    private final PriorityQueue<ExpiryNode> expiry = new PriorityQueue<>(EXPIRY_ORDER);
    private int entryCount;
    private long generation = -1L;

    private GameplayPromotionRuntimeState(MinecraftServer server, LongSupplier gameTime) {
        this.server = Objects.requireNonNull(server, "server");
        this.gameTime = Objects.requireNonNull(gameTime, "gameTime");
    }

    public static GameplayPromotionRuntimeState get(MinecraftServer server) {
        requireServerThread(server);
        return STATES.computeIfAbsent(server, key -> new GameplayPromotionRuntimeState(
                key, () -> key.overworld().getGameTime()));
    }

    static GameplayPromotionRuntimeState forTesting(MinecraftServer server, LongSupplier gameTime) {
        requireServerThread(server);
        return new GameplayPromotionRuntimeState(server, gameTime);
    }

    public static void removePlayerIfPresent(MinecraftServer server, UUID playerId) {
        requireServerThread(server);
        GameplayPromotionRuntimeState state = STATES.get(server);
        if (state != null) {
            state.removePlayer(playerId);
        }
    }

    public static void discard(MinecraftServer server) {
        requireServerThread(server);
        STATES.remove(server);
    }

    static boolean hasStateForTesting(MinecraftServer server) {
        requireServerThread(server);
        return STATES.containsKey(server);
    }

    public void alignGeneration(long currentGeneration) {
        requireServerThread(server);
        if (currentGeneration < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        if (generation != currentGeneration) {
            clear();
            generation = currentGeneration;
        }
    }

    public boolean isEligible(UUID playerId, ResourceLocation promotionRuleId) {
        requireServerThread(server);
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(promotionRuleId, "promotionRuleId");
        long now = currentTick();
        cleanupExpired(now);
        Map<ResourceLocation, CooldownEntry> playerEntries = entries.get(playerId);
        return playerEntries == null || !playerEntries.containsKey(promotionRuleId);
    }

    public AttemptReservationResult reserve(UUID playerId, ResourceLocation promotionRuleId,
            long cooldownTicks) {
        requireServerThread(server);
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(promotionRuleId, "promotionRuleId");
        if (cooldownTicks <= 0L) {
            throw new IllegalArgumentException("cooldownTicks must be positive");
        }
        long now = currentTick();
        cleanupExpired(now);
        Map<ResourceLocation, CooldownEntry> playerEntries = entries.get(playerId);
        if (playerEntries != null && playerEntries.containsKey(promotionRuleId)) {
            return AttemptReservationResult.COOLDOWN;
        }
        int playerSize = playerEntries == null ? 0 : playerEntries.size();
        if (playerSize >= MAX_ENTRIES_PER_PLAYER || entryCount >= MAX_ENTRIES_PER_SERVER) {
            return AttemptReservationResult.CAPACITY_REJECTED;
        }

        long nextEligibleTick = saturatingAdd(now, cooldownTicks);
        ExpiryNode node = new ExpiryNode(nextEligibleTick, playerId, promotionRuleId);
        if (playerEntries == null) {
            playerEntries = new HashMap<>();
            entries.put(playerId, playerEntries);
        }
        playerEntries.put(promotionRuleId, new CooldownEntry(nextEligibleTick, node));
        expiry.add(node);
        entryCount++;
        return AttemptReservationResult.RECORDED;
    }

    public void removePlayer(UUID playerId) {
        requireServerThread(server);
        Map<ResourceLocation, CooldownEntry> removed = entries.remove(
                Objects.requireNonNull(playerId, "playerId"));
        if (removed == null) {
            return;
        }
        removed.values().forEach(entry -> expiry.remove(entry.node()));
        entryCount -= removed.size();
    }

    int entryCountForTesting() {
        requireServerThread(server);
        return entryCount;
    }

    int playerEntryCountForTesting(UUID playerId) {
        requireServerThread(server);
        Map<ResourceLocation, CooldownEntry> playerEntries = entries.get(playerId);
        return playerEntries == null ? 0 : playerEntries.size();
    }

    OptionalLong nextEligibleTickForTesting(UUID playerId, ResourceLocation promotionRuleId) {
        requireServerThread(server);
        Map<ResourceLocation, CooldownEntry> playerEntries = entries.get(playerId);
        CooldownEntry entry = playerEntries == null ? null : playerEntries.get(promotionRuleId);
        return entry == null ? OptionalLong.empty() : OptionalLong.of(entry.nextEligibleTick());
    }

    long generationForTesting() {
        requireServerThread(server);
        return generation;
    }

    private void cleanupExpired(long now) {
        while (!expiry.isEmpty() && expiry.peek().nextEligibleTick() <= now) {
            ExpiryNode node = expiry.remove();
            Map<ResourceLocation, CooldownEntry> playerEntries = entries.get(node.playerId());
            if (playerEntries == null) {
                continue;
            }
            CooldownEntry current = playerEntries.get(node.promotionRuleId());
            if (current == null || current.node() != node) {
                continue;
            }
            playerEntries.remove(node.promotionRuleId());
            entryCount--;
            if (playerEntries.isEmpty()) {
                entries.remove(node.playerId());
            }
        }
    }

    private void clear() {
        entries.clear();
        expiry.clear();
        entryCount = 0;
    }

    private long currentTick() {
        long tick = gameTime.getAsLong();
        if (tick < 0L) {
            throw new IllegalStateException("game tick must be non-negative");
        }
        return tick;
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Gameplay promotion runtime must run on the server thread");
        }
    }

    private record CooldownEntry(long nextEligibleTick, ExpiryNode node) {
    }

    private record ExpiryNode(long nextEligibleTick, UUID playerId,
                              ResourceLocation promotionRuleId) {
    }
}
