package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Computes automatic appearance eligibility without persisting or triggering an appearance. */
public final class GodAppearanceService {
    public static final GodAppearanceService INSTANCE = new GodAppearanceService();

    private long totalConditionEvaluations;

    private GodAppearanceService() {
    }

    public AppearanceEvaluation evaluateAutomaticAppearance(ServerPlayer player, ResourceLocation godId) {
        requireServerThread(player);
        GodDefinitionManager.ProgressionSnapshot snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        boolean active = PlayerMythDataService.get(player.server).find(player.getUUID())
                .map(profile -> profile.participationStatus() == ParticipationStatus.ACTIVE)
                .orElse(false);
        ConditionContext context = ConditionContexts.forServer(
                player.server, Optional.of(player.getUUID()), snapshot.definitions(), snapshot.generation() > 0);
        return evaluateAutomaticAppearance(snapshot, context, active, godId);
    }

    public boolean canAppear(ServerPlayer player, ResourceLocation godId) {
        return evaluateAutomaticAppearance(player, godId).eligible();
    }

    public List<ResourceLocation> getAutomaticallyEligibleGods(ServerPlayer player) {
        requireServerThread(player);
        GodDefinitionManager.ProgressionSnapshot snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        boolean active = PlayerMythDataService.get(player.server).find(player.getUUID())
                .map(profile -> profile.participationStatus() == ParticipationStatus.ACTIVE)
                .orElse(false);
        if (!active) {
            return List.of();
        }
        ConditionContext context = ConditionContexts.forServer(
                player.server, Optional.of(player.getUUID()), snapshot.definitions(), snapshot.generation() > 0);
        return getAutomaticallyEligibleGods(snapshot, context, true);
    }

    public AppearanceEvaluation evaluateAutomaticAppearance(GodDefinitionManager.ProgressionSnapshot snapshot,
            ConditionContext context, boolean active, ResourceLocation godId) {
        GodDefinition definition = snapshot.definitions().get(godId);
        if (definition == null) {
            return result(godId, Optional.empty(), false, Optional.empty(), false,
                    AppearanceEvaluationReason.UNKNOWN_GOD);
        }
        AppearancePolicy policy = definition.appearancePolicy();
        if (!active) {
            return result(godId, Optional.of(policy), false, Optional.empty(), false,
                    AppearanceEvaluationReason.PLAYER_NOT_ACTIVE);
        }

        Optional<Boolean> unlockStatus = GodAccessService.effectiveUnlockStatus(
                godId, context.world(), context.gods());
        if (unlockStatus.isEmpty() || !unlockStatus.orElseThrow()) {
            return result(godId, Optional.of(policy), false, Optional.empty(), false,
                    AppearanceEvaluationReason.NOT_EFFECTIVELY_UNLOCKED);
        }
        if (policy == AppearancePolicy.EXPLICIT_ONLY) {
            return result(godId, Optional.of(policy), true, Optional.empty(), false,
                    AppearanceEvaluationReason.EXPLICIT_ONLY);
        }

        totalConditionEvaluations++;
        ConditionResult condition = ConditionEngine.INSTANCE.evaluate(
                definition.appearanceConditions().orElseThrow(), context);
        return switch (condition) {
            case MATCH -> result(godId, Optional.of(policy), true, Optional.of(condition), true,
                    AppearanceEvaluationReason.ELIGIBLE);
            case NO_MATCH -> result(godId, Optional.of(policy), true, Optional.of(condition), false,
                    AppearanceEvaluationReason.CONDITION_NO_MATCH);
            case UNKNOWN -> result(godId, Optional.of(policy), true, Optional.of(condition), false,
                    AppearanceEvaluationReason.CONDITION_UNKNOWN);
        };
    }

    List<ResourceLocation> getAutomaticallyEligibleGods(GodDefinitionManager.ProgressionSnapshot snapshot,
            ConditionContext context, boolean active) {
        if (!active) {
            return List.of();
        }
        List<ResourceLocation> eligible = new ArrayList<>();
        snapshot.appearanceIndex().conditionalGods().stream().sorted().forEach(godId -> {
            if (evaluateAutomaticAppearance(snapshot, context, true, godId).eligible()) {
                eligible.add(godId);
            }
        });
        return List.copyOf(eligible);
    }

    public long totalConditionEvaluations() {
        return totalConditionEvaluations;
    }

    private static AppearanceEvaluation result(ResourceLocation godId, Optional<AppearancePolicy> policy,
            boolean unlocked, Optional<ConditionResult> condition, boolean eligible,
            AppearanceEvaluationReason reason) {
        return new AppearanceEvaluation(godId, policy, unlocked, condition, eligible, reason);
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("God appearance eligibility may only be evaluated on the server thread");
        }
    }
}
