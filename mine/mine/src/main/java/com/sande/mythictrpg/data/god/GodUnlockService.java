package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionDependency;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.condition.engine.ConditionChangeDispatcher;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.world.MythicWorldState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Evaluates conditional God unlocks and records one-way world progression. */
public final class GodUnlockService implements ConditionChangeDispatcher.Handler {
    public static final GodUnlockService INSTANCE = new GodUnlockService();
    private long totalConditionEvaluations;

    private GodUnlockService() {
    }

    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        MythicWorldState.get(server);
        PlayerMythDataService.get(server).isReady();
        UnlockEvaluationReport report = evaluateCatchUp(server);
        MythicTrpg.LOGGER.info("God unlock startup catch-up checked {} evaluation(s) and unlocked {} God(s).",
                report.checked(), report.newlyUnlockedCount());
    }

    @Override
    public UnlockEvaluationReport onPlayerDependencyChanged(
            MinecraftServer server, ConditionDependency dependency, UUID playerId) {
        GodDefinitionManager.ProgressionSnapshot snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        return evaluate(server, snapshot, snapshot.unlockIndex().candidates(dependency), Optional.of(playerId), false);
    }

    @Override
    public UnlockEvaluationReport onPlayerReady(MinecraftServer server, UUID playerId) {
        GodDefinitionManager.ProgressionSnapshot snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        return evaluate(server, snapshot, snapshot.unlockIndex().conditionalGods(), Optional.of(playerId), false);
    }

    @Override
    public UnlockEvaluationReport onDefinitionsReloaded(MinecraftServer server) {
        return evaluateCatchUp(server);
    }

    public UnlockEvaluationReport evaluateCatchUp(MinecraftServer server) {
        GodDefinitionManager.ProgressionSnapshot snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        return evaluate(server, snapshot, snapshot.unlockIndex().conditionalGods(), Optional.empty(), true);
    }

    public UnlockEvaluationReport evaluateDependency(
            MinecraftServer server, ConditionDependency dependency, Optional<UUID> targetPlayerId) {
        GodDefinitionManager.ProgressionSnapshot snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        return evaluate(server, snapshot, snapshot.unlockIndex().candidates(dependency), targetPlayerId,
                targetPlayerId.isEmpty());
    }

    public boolean isExplicitlyUnlocked(MinecraftServer server, ResourceLocation godId) {
        return MythicWorldState.get(server).isGodUnlocked(godId);
    }

    public long totalConditionEvaluations() {
        return totalConditionEvaluations;
    }

    private UnlockEvaluationReport evaluate(MinecraftServer server,
            GodDefinitionManager.ProgressionSnapshot snapshot, Set<ResourceLocation> initialCandidates,
            Optional<UUID> preferredTarget, boolean useAllActiveTargets) {
        requireServerThread(server);
        if (snapshot.generation() == 0 || initialCandidates.isEmpty()) {
            return UnlockEvaluationReport.empty();
        }

        MythicWorldState worldState = MythicWorldState.get(server);
        if (worldState.isRejected()) {
            MythicTrpg.LOGGER.error("God unlock evaluation skipped because Mythic world data was rejected: {}",
                    worldState.rejectionReason());
            return UnlockEvaluationReport.empty();
        }

        PlayerMythDataService players = PlayerMythDataService.get(server);
        Map<UUID, ?> activeProfiles = players.isReady() ? players.activeProfiles() : Map.of();
        ArrayDeque<EvaluationKey> work = new ArrayDeque<>();
        Set<EvaluationKey> queued = new LinkedHashSet<>();
        Map<EvaluationKey, Integer> lastEvaluatedRevision = new LinkedHashMap<>();
        int worldRevision = 0;
        enqueueCandidates(initialCandidates, preferredTarget, useAllActiveTargets, snapshot.unlockIndex(),
                activeProfiles.keySet(), worldState, worldRevision, work, queued, lastEvaluatedRevision);

        long evaluationKeys = (long) snapshot.unlockIndex().conditionalGods().size()
                * Math.max(1, activeProfiles.size() + 1);
        long maximumEvaluations = Math.max(1L, evaluationKeys
                * (snapshot.unlockIndex().conditionalGods().size() + 1L));
        int checked = 0;
        ArrayList<ResourceLocation> unlocked = new ArrayList<>();

        while (!work.isEmpty()) {
            EvaluationKey key = work.removeFirst();
            queued.remove(key);
            if (worldState.isGodUnlocked(key.godId())) {
                continue;
            }
            if (++checked > maximumEvaluations) {
                MythicTrpg.LOGGER.error("Stopped God unlock evaluation after exceeding safety limit {}.",
                        maximumEvaluations);
                break;
            }
            lastEvaluatedRevision.put(key, worldRevision);

            GodDefinition definition = snapshot.definitions().get(key.godId());
            if (definition == null || definition.unlockConditions().isEmpty()) {
                continue;
            }
            ConditionResult result = ConditionEngine.INSTANCE.evaluate(
                    definition.unlockConditions().orElseThrow(),
                    ConditionContexts.forServer(server, key.targetPlayerId(), snapshot.definitions(), true));
            if (result != ConditionResult.MATCH || !worldState.unlockGod(key.godId())) {
                continue;
            }

            unlocked.add(key.godId());
            worldRevision++;
            MythicTrpg.LOGGER.info("Automatically unlocked God {}.", key.godId());
            enqueueCandidates(snapshot.unlockIndex().candidates(
                            BuiltinConditionTypes.WORLD_GOD_STATE_DEPENDENCY), Optional.empty(), true,
                    snapshot.unlockIndex(), activeProfiles.keySet(), worldState, worldRevision,
                    work, queued, lastEvaluatedRevision);
        }
        totalConditionEvaluations += checked;
        return new UnlockEvaluationReport(checked, unlocked);
    }

    private static void enqueueCandidates(Set<ResourceLocation> candidates, Optional<UUID> preferredTarget,
            boolean useAllActiveTargets, GodUnlockDependencyIndex index, Set<UUID> activePlayerIds,
            MythicWorldState worldState, int worldRevision, ArrayDeque<EvaluationKey> work,
            Set<EvaluationKey> queued, Map<EvaluationKey, Integer> lastEvaluatedRevision) {
        candidates.stream().sorted().forEach(godId -> {
            if (worldState.isGodUnlocked(godId)) {
                return;
            }
            if (!index.requiresPlayerTarget(godId)) {
                enqueue(new EvaluationKey(godId, Optional.empty()), worldRevision,
                        work, queued, lastEvaluatedRevision);
                return;
            }
            if (useAllActiveTargets) {
                activePlayerIds.stream().sorted().forEach(playerId ->
                        enqueue(new EvaluationKey(godId, Optional.of(playerId)), worldRevision,
                                work, queued, lastEvaluatedRevision));
            } else {
                preferredTarget.filter(activePlayerIds::contains).ifPresent(playerId ->
                        enqueue(new EvaluationKey(godId, Optional.of(playerId)), worldRevision,
                                work, queued, lastEvaluatedRevision));
            }
        });
    }

    private static void enqueue(EvaluationKey key, int worldRevision, ArrayDeque<EvaluationKey> work,
            Set<EvaluationKey> queued, Map<EvaluationKey, Integer> lastEvaluatedRevision) {
        if (lastEvaluatedRevision.getOrDefault(key, -1) < worldRevision && queued.add(key)) {
            work.addLast(key);
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("God unlock evaluation may only run on the server thread");
        }
    }

    private record EvaluationKey(ResourceLocation godId, Optional<UUID> targetPlayerId) {
    }
}
