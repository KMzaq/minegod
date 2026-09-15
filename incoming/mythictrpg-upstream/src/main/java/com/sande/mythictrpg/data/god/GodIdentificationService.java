package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/** Evaluates condition-driven identification eligibility without persisting identification. */
public final class GodIdentificationService {
    public static final GodIdentificationService INSTANCE = new GodIdentificationService();

    private GodIdentificationService() {
    }

    public IdentificationEvaluation evaluateEligibility(ServerPlayer player, ResourceLocation godId) {
        requireServerThread(player);
        GodDefinitionManager.ProgressionSnapshot snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        boolean active = PlayerMythDataService.get(player.server).find(player.getUUID())
                .map(profile -> profile.participationStatus() == ParticipationStatus.ACTIVE)
                .orElse(false);
        ConditionContext context = ConditionContexts.forServer(
                player.server, Optional.of(player.getUUID()), snapshot.definitions(), snapshot.generation() > 0);
        return evaluateEligibility(snapshot, context, active, godId);
    }

    IdentificationEvaluation evaluateEligibility(GodDefinitionManager.ProgressionSnapshot snapshot,
            ConditionContext context, boolean active, ResourceLocation godId) {
        GodDefinition definition = snapshot.definitions().get(godId);
        if (definition == null) {
            return result(godId, Optional.empty(), Optional.empty(), false,
                    IdentificationEvaluationReason.UNKNOWN_GOD);
        }
        IdentificationPolicy policy = definition.identificationPolicy();
        if (!active) {
            return result(godId, Optional.of(policy), Optional.empty(), false,
                    IdentificationEvaluationReason.PLAYER_NOT_ACTIVE);
        }
        if (policy == IdentificationPolicy.EXPLICIT_ONLY) {
            return result(godId, Optional.of(policy), Optional.empty(), false,
                    IdentificationEvaluationReason.EXPLICIT_ONLY);
        }

        ConditionResult condition = ConditionEngine.INSTANCE.evaluate(
                definition.identificationConditions().orElseThrow(), context);
        return switch (condition) {
            case MATCH -> result(godId, Optional.of(policy), Optional.of(condition), true,
                    IdentificationEvaluationReason.ELIGIBLE);
            case NO_MATCH -> result(godId, Optional.of(policy), Optional.of(condition), false,
                    IdentificationEvaluationReason.CONDITION_NO_MATCH);
            case UNKNOWN -> result(godId, Optional.of(policy), Optional.of(condition), false,
                    IdentificationEvaluationReason.CONDITION_UNKNOWN);
        };
    }

    private static IdentificationEvaluation result(ResourceLocation godId,
            Optional<IdentificationPolicy> policy, Optional<ConditionResult> condition,
            boolean eligible, IdentificationEvaluationReason reason) {
        return new IdentificationEvaluation(godId, policy, condition, eligible, reason);
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException(
                    "God identification eligibility may only be evaluated on the server thread");
        }
    }
}
