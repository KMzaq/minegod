package com.sande.mythictrpg.data.player;

import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.condition.engine.ConditionChangeDispatcher;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerMythDataService implements PlayerMythQueryService {
    private final MinecraftServer server;
    private final PlayerMythDataRepository repository;

    private PlayerMythDataService(MinecraftServer server) {
        this.server = server;
        this.repository = PlayerMythDataRepository.get(server);
    }

    public static PlayerMythDataService get(MinecraftServer server) {
        return new PlayerMythDataService(server);
    }

    public PlayerMythProfile ensureProfile(UUID playerId) {
        requireServerThread();
        return repository.getOrCreate(playerId);
    }

    public PlayerMythProfile ensureProfile(ServerPlayer player) {
        return ensureProfile(player.getData(ModAttachments.PLAYER_MYTH_VIEW).playerId());
    }

    public PlayerMythProfile setAffinity(UUID playerId, ResourceLocation godId, int value) {
        requireServerThread();
        PlayerMythProfile existing = repository.getOrCreate(playerId);
        PlayerMythProfile changed = existing.withAffinity(godId, value);
        if (changed == existing) {
            return existing;
        }
        repository.put(playerId, changed);
        ConditionChangeDispatcher.publishPlayerDependencyChanged(
                server, BuiltinConditionTypes.PLAYER_AFFINITY_DEPENDENCY, playerId);
        return changed;
    }

    public PlayerMythProfile adjustAffinity(UUID playerId, ResourceLocation godId, int delta) {
        requireServerThread();
        if (delta < -50 || delta > 50 || delta == 0) {
            throw new IllegalArgumentException("AI affinity delta must be between -50 and 50 and not zero");
        }
        PlayerMythProfile existing = repository.getOrCreate(playerId);
        int current = existing.affinities().getOrDefault(godId, 0);
        int changed = Math.max(PlayerMythProfile.MIN_AFFINITY,
                Math.min(PlayerMythProfile.MAX_AFFINITY, current + delta));
        return setAffinity(playerId, godId, changed);
    }

    /** Positive, authored quest reward; deliberately separate from the AI +/-50 action boundary. */
    public PlayerMythProfile grantQuestAffinity(UUID playerId, ResourceLocation godId, int amount) {
        requireServerThread();
        if (amount < 1 || amount > 200) {
            throw new IllegalArgumentException("Quest affinity reward must be between 1 and 200");
        }
        PlayerMythProfile existing = repository.getOrCreate(playerId);
        int current = existing.affinities().getOrDefault(godId, 0);
        return setAffinity(playerId, godId, Math.min(PlayerMythProfile.MAX_AFFINITY, current + amount));
    }

    public PlayerMythProfile setParticipationStatus(UUID playerId, ParticipationStatus status) {
        requireServerThread();
        PlayerMythProfile existing = repository.getOrCreate(playerId);
        PlayerMythProfile changed = existing.withParticipationStatus(status);
        if (changed == existing) {
            return existing;
        }
        repository.put(playerId, changed);
        if (status == ParticipationStatus.ACTIVE) {
            ConditionChangeDispatcher.publishPlayerReady(server, playerId);
        }
        return changed;
    }

    boolean recordEncounteredGod(UUID playerId, ResourceLocation godId) {
        return recordEncounteredGods(playerId, java.util.Set.of(godId));
    }

    boolean recordEncounteredGods(UUID playerId, Collection<ResourceLocation> godIds) {
        requireServerThread();
        PlayerMythProfile existing = repository.find(playerId).orElse(null);
        if (existing == null) {
            return false;
        }
        PlayerMythProfile changed = existing.withEncounteredGods(godIds);
        if (changed == existing) {
            return false;
        }
        repository.put(playerId, changed);
        return true;
    }

    boolean recordIdentifiedGod(UUID playerId, ResourceLocation godId) {
        requireServerThread();
        PlayerMythProfile existing = repository.getOrCreate(playerId);
        PlayerMythProfile changed = existing.withIdentifiedGod(godId);
        if (changed == existing) {
            return false;
        }
        repository.put(playerId, changed);
        return true;
    }

    ItemHistoryRecordResult recordObtainedItem(UUID playerId, ResourceLocation itemId) {
        requireServerThread();
        PlayerMythProfile existing = repository.getOrCreate(playerId);
        PlayerMythProfile changed = existing.withObtainedItem(itemId);
        if (changed == existing) {
            return ItemHistoryRecordResult.ALREADY_RECORDED;
        }
        repository.put(playerId, changed);
        return ItemHistoryRecordResult.NEW_RECORD;
    }

    public boolean unlockTitle(UUID playerId, ResourceLocation titleId) {
        requireServerThread();
        PlayerMythProfile existing = repository.getOrCreate(playerId);
        PlayerMythProfile changed = existing.withUnlockedTitle(titleId);
        if (changed == existing) {
            return false;
        }
        repository.put(playerId, changed);
        return true;
    }

    public GameplayCounterMutationResult incrementGameplayCounters(UUID playerId,
            Map<GameplayMetricKey, Long> increments) {
        requireServerThread();
        if (increments == null || increments.isEmpty()) {
            return GameplayCounterMutationResult.REJECTED_EMPTY_BATCH;
        }
        Map<GameplayMetricKey, Long> requested = new LinkedHashMap<>();
        for (Map.Entry<GameplayMetricKey, Long> entry : increments.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                return GameplayCounterMutationResult.REJECTED_NON_POSITIVE_DELTA;
            }
            requested.put(entry.getKey(), entry.getValue());
        }

        PlayerMythProfile existing = repository.find(playerId).orElseGet(PlayerMythProfile::createActive);
        Map<GameplayMetricKey, Long> changed = new LinkedHashMap<>(existing.customGameplayCounters());
        if (requested.keySet().stream().filter(key -> !changed.containsKey(key)).count()
                + changed.size() > PlayerMythProfile.MAX_CUSTOM_GAMEPLAY_COUNTERS) {
            return GameplayCounterMutationResult.REJECTED_KEY_LIMIT;
        }
        for (Map.Entry<GameplayMetricKey, Long> entry : requested.entrySet()) {
            try {
                changed.put(entry.getKey(), Math.addExact(changed.getOrDefault(entry.getKey(), 0L), entry.getValue()));
            } catch (ArithmeticException exception) {
                return GameplayCounterMutationResult.REJECTED_OVERFLOW;
            }
        }

        repository.put(playerId, existing.withCustomGameplayCounters(changed));
        return GameplayCounterMutationResult.UPDATED;
    }

    @Override
    public boolean isReady() {
        return repository.isReady();
    }

    @Override
    public Optional<PlayerMythProfile> find(UUID playerId) {
        return repository.find(playerId);
    }

    @Override
    public Map<UUID, PlayerMythProfile> activeProfiles() {
        return repository.activeProfiles();
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.setData(ModAttachments.PLAYER_MYTH_VIEW, new PlayerMythDataView(player.getUUID()));
            get(player.server).ensureProfile(player);
            ConditionChangeDispatcher.publishPlayerReady(player.server, player.getUUID());
        }
    }

    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.setData(ModAttachments.PLAYER_MYTH_VIEW, new PlayerMythDataView(player.getUUID()));
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Player Myth data may only be changed on the server thread");
        }
        if (!repository.isReady()) {
            throw new IllegalStateException("Player Myth repository is not writable: "
                    + repository.rejectionReason());
        }
    }
}
